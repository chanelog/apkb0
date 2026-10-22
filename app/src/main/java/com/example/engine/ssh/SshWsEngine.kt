package com.example.engine.ssh

import com.example.domain.model.Profile
import com.example.domain.model.TunnelProtocol
import com.example.utils.OGLogger
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SshWsEngine(
    private val profile: Profile,
    private val timeoutSeconds: Int = 15,
    private val onTrafficUpdate: (uploadBytes: Long, downloadBytes: Long) -> Unit,
    private val socketProtector: ((Socket) -> Unit)? = null
) {

    private val isRunning = AtomicBoolean(false)
    private var jschSession: Session? = null
    private var socksServer: Socks5LocalServer? = null
    private var rawTransportSocket: Socket? = null

    val totalUploaded = AtomicLong(0L)
    val totalDownloaded = AtomicLong(0L)

    var localSocksPort: Int = 10808
        private set

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        OGLogger.i("SshWsEngine", "Starting SSH connection for profile: ${profile.name} (${profile.protocol.displayName})")

        try {
            val jsch = JSch()

            if (profile.sshPrivateKey.isNotBlank()) {
                OGLogger.i("SshWsEngine", "Configuring SSH private key identity")
                jsch.addIdentity("custom_identity", profile.sshPrivateKey.toByteArray(), null, null)
            }

            // Create custom socket factory to handle direct TCP, WebSocket handshake, or TLS
            val customSocketFactory = object : SocketFactory {
                private var connectedSocket: Socket? = null

                override fun createSocket(host: String, port: Int): Socket {
                    OGLogger.i("SshWsEngine", "Establishing transport socket: protocol=${profile.protocol}")
                    val sock = establishTransportSocket()
                    connectedSocket = sock
                    return sock
                }

                override fun getInputStream(socket: Socket): InputStream {
                    return CountingInputStream(socket.getInputStream()) { bytes ->
                        totalDownloaded.addAndGet(bytes)
                        onTrafficUpdate(totalUploaded.get(), totalDownloaded.get())
                    }
                }

                override fun getOutputStream(socket: Socket): OutputStream {
                    return CountingOutputStream(socket.getOutputStream()) { bytes ->
                        totalUploaded.addAndGet(bytes)
                        onTrafficUpdate(totalUploaded.get(), totalDownloaded.get())
                    }
                }
            }

            val session = jsch.getSession(profile.sshUsername, profile.sshHost, profile.sshPort)
            jschSession = session
            session.setSocketFactory(customSocketFactory)
            if (profile.sshPassword.isNotBlank()) {
                session.setPassword(profile.sshPassword)
            }

            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            session.timeout = timeoutSeconds * 1000
            session.serverAliveInterval = profile.keepaliveIntervalSeconds.coerceAtLeast(5)

            OGLogger.i("SshWsEngine", "Connecting JSch session to ${profile.sshHost}:${profile.sshPort}...")
            session.connect(timeoutSeconds * 1000)

            if (!session.isConnected) {
                throw IllegalStateException("SSH authentication completed but session is not connected")
            }

            OGLogger.i("SshWsEngine", "SSH session authenticated successfully (Server Version: ${session.serverVersion})")

            // Start local SOCKS5 proxy server for TUN routing
            startLocalSocksServer(session)

            OGLogger.i("SshWsEngine", "SshWsEngine ready. Local SOCKS5 listening on 127.0.0.1:$localSocksPort")

        } catch (e: Exception) {
            OGLogger.e("SshWsEngine", "SSH engine startup failed: ${e.message}", e)
            stop()
            throw e
        }
    }

    private fun establishTransportSocket(): Socket {
        val targetHost: String
        val targetPort: Int

        when (profile.protocol) {
            TunnelProtocol.SSH_WS, TunnelProtocol.SSH_HTTP_UPGRADE -> {
                targetHost = profile.wsHost.ifEmpty { profile.sshHost }
                targetPort = profile.wsPort
            }
            else -> {
                targetHost = profile.sshHost
                targetPort = profile.sshPort
            }
        }

        OGLogger.i("SshWsEngine", "Connecting TCP to transport endpoint $targetHost:$targetPort (TLS: ${profile.tlsEnabled})")

        var baseSocket = Socket()
        socketProtector?.invoke(baseSocket)
        baseSocket.connect(InetSocketAddress(targetHost, targetPort), timeoutSeconds * 1000)
        baseSocket.tcpNoDelay = true

        rawTransportSocket = baseSocket

        var transportSocket = baseSocket
        if (profile.tlsEnabled) {
            val sslContext = if (profile.allowInsecureCert) {
                OGLogger.w("SshWsEngine", "SECURITY WARNING: Custom certificate validation disabled for this profile!")
                createInsecureSslContext()
            } else {
                SSLContext.getDefault()
            }

            val sniHost = profile.tlsSni.ifEmpty { targetHost }
            OGLogger.i("SshWsEngine", "Wrapping with TLS socket (SNI: $sniHost)")
            val sslSocket = sslContext.socketFactory.createSocket(
                baseSocket,
                sniHost,
                targetPort,
                true
            ) as SSLSocket

            sslSocket.startHandshake()
            transportSocket = sslSocket
            rawTransportSocket = sslSocket
        }

        if (profile.protocol == TunnelProtocol.SSH_WS || profile.protocol == TunnelProtocol.SSH_HTTP_UPGRADE) {
            performWebSocketHandshake(transportSocket, targetHost, targetPort)
        }

        return transportSocket
    }

    private fun performWebSocketHandshake(socket: Socket, host: String, port: Int) {
        val out = socket.getOutputStream()
        val inp = socket.getInputStream()

        val request = if (profile.customPayload.isNotBlank()) {
            expandPayloadMacros(profile.customPayload, host, port)
        } else {
            val wsKeyBytes = ByteArray(16)
            SecureRandom().nextBytes(wsKeyBytes)
            val wsKey = Base64.getEncoder().encodeToString(wsKeyBytes)

            buildString {
                append("GET ${profile.wsPath.ifEmpty { "/ssh" }} HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $wsKey\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("User-Agent: OGTunnel/1.0\r\n")
                append("\r\n")
            }
        }

        OGLogger.d("SshWsEngine", "Sending WebSocket/Upgrade Handshake header")
        out.write(request.toByteArray(Charsets.UTF_8))
        out.flush()

        // Read response header until empty line
        val responseHeader = readHttpHeader(inp)
        OGLogger.i("SshWsEngine", "Received HTTP Handshake Response: ${responseHeader.lines().firstOrNull()}")

        val statusLine = responseHeader.lines().firstOrNull() ?: ""
        if (!statusLine.contains("101") && !statusLine.contains("200")) {
            throw IllegalStateException("WebSocket Upgrade rejected by server: $statusLine")
        }
    }

    private fun readHttpHeader(inputStream: InputStream): String {
        val sb = StringBuilder()
        var lastFour = 0
        while (true) {
            val b = inputStream.read()
            if (b == -1) break
            sb.append(b.toChar())
            lastFour = (lastFour shl 8) or (b and 0xFF)
            if (lastFour == 0x0D0A0D0A) { // \r\n\r\n
                break
            }
        }
        return sb.toString()
    }

    private fun expandPayloadMacros(template: String, host: String, port: Int): String {
        return template
            .replace("[host]", host)
            .replace("[port]", port.toString())
            .replace("[host_port]", "$host:$port")
            .replace("[protocol]", "HTTP/1.1")
            .replace("[crlf]", "\r\n")
            .replace("[cr]", "\r")
            .replace("[lf]", "\n")
    }

    private fun createInsecureSslContext(): SSLContext {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sc = SSLContext.getInstance("TLS")
        sc.init(null, trustAll, SecureRandom())
        return sc
    }

    private fun startLocalSocksServer(session: Session) {
        // Find an open port
        val serverSocket = ServerSocket(0)
        localSocksPort = serverSocket.localPort

        socksServer = Socks5LocalServer(
            serverSocket = serverSocket,
            session = session,
            socketProtector = socketProtector,
            onTraffic = { up, down ->
                totalUploaded.addAndGet(up)
                totalDownloaded.addAndGet(down)
                onTrafficUpdate(totalUploaded.get(), totalDownloaded.get())
            }
        )
        socksServer?.start()
    }

    fun stop() {
        isRunning.set(false)
        try {
            socksServer?.stop()
        } catch (_: Exception) {}
        try {
            jschSession?.disconnect()
        } catch (_: Exception) {}
        try {
            rawTransportSocket?.close()
        } catch (_: Exception) {}
        OGLogger.i("SshWsEngine", "SshWsEngine stopped cleanly")
    }
}

private class CountingInputStream(
    private val delegate: InputStream,
    private val onRead: (Long) -> Unit
) : InputStream() {
    override fun read(): Int {
        val b = delegate.read()
        if (b != -1) onRead(1L)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = delegate.read(b, off, len)
        if (count > 0) onRead(count.toLong())
        return count
    }

    override fun close() = delegate.close()
    override fun available() = delegate.available()
}

private class CountingOutputStream(
    private val delegate: OutputStream,
    private val onWrite: (Long) -> Unit
) : OutputStream() {
    override fun write(b: Int) {
        delegate.write(b)
        onWrite(1L)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        if (len > 0) onWrite(len.toLong())
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}
