package com.example.engine.ssh

import android.os.Build
import com.example.domain.model.Profile
import com.example.domain.model.TunnelProtocol
import com.example.utils.OGLogger
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
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
    val requestedLocalSocksPort: Int = 10808,
    private val timeoutSeconds: Int = 15,
    private val onTrafficUpdate: (uploadBytes: Long, downloadBytes: Long) -> Unit,
    private val socketProtector: ((Socket) -> Unit)? = null
) {

    private val isRunning = AtomicBoolean(false)
    private val hasBannerBeenLogged = AtomicBoolean(false)
    private var jschSession: Session? = null
    private var socksServer: Socks5LocalServer? = null
    private var rawTransportSocket: Socket? = null
    private var httpPingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    val totalUploaded = AtomicLong(0L)
    val totalDownloaded = AtomicLong(0L)

    var localSocksPort: Int = requestedLocalSocksPort
        private set

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        OGLogger.i(
            "System",
            "Running on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        )

        try {
            val jsch = JSch()

            if (profile.sshPrivateKey.isNotBlank()) {
                OGLogger.i("SSH", "Configuring SSH private key identity")
                jsch.addIdentity("custom_identity", profile.sshPrivateKey.toByteArray(), null, null)
            }

            // Create custom socket factory to handle direct TCP, WebSocket handshake, or TLS
            val customSocketFactory = object : SocketFactory {
                override fun createSocket(host: String, port: Int): Socket {
                    return establishTransportSocket()
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

            // Set UserInfo callback to capture native SSH Server Banner / Message (MOTD)
            val customUserInfo = object : UserInfo {
                override fun showMessage(message: String?) {
                    if (!message.isNullOrBlank()) {
                        hasBannerBeenLogged.set(true)
                        OGLogger.i("SSH", "Server Message:\n$message")
                    }
                }

                override fun getPassphrase(): String? = null
                override fun getPassword(): String = profile.sshPassword
                override fun promptPassword(message: String?): Boolean = true
                override fun promptPassphrase(message: String?): Boolean = true
                override fun promptYesNo(message: String?): Boolean = true
            }
            session.userInfo = customUserInfo

            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            session.timeout = timeoutSeconds * 1000
            session.serverAliveInterval = profile.keepaliveIntervalSeconds.coerceAtLeast(5)

            session.connect(timeoutSeconds * 1000)

            if (!session.isConnected) {
                throw IllegalStateException("SSH authentication completed but session is not connected")
            }

            OGLogger.i("SSH", session.serverVersion)
            OGLogger.i("SSH", "Auth complete")
            OGLogger.i("SSH", "Connected")

            if (!hasBannerBeenLogged.get()) {
                fetchServerBanner(session)
            }

            // Start local SOCKS5 proxy server for TUN routing
            startLocalSocksServer(session)

            // Start background HTTP ping loop to verify actual internet connectivity
            startHttpPingLoop()

        } catch (e: Exception) {
            OGLogger.e("SSH", "Connection failed: ${e.message}", e)
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

        OGLogger.i("Transport", "Connecting to $targetHost port $targetPort")

        val baseSocket = Socket()
        socketProtector?.invoke(baseSocket)
        baseSocket.connect(InetSocketAddress(targetHost, targetPort), timeoutSeconds * 1000)
        baseSocket.tcpNoDelay = true

        rawTransportSocket = baseSocket
        var transportSocket = baseSocket

        if (profile.tlsEnabled) {
            val sniHost = profile.tlsSni.ifEmpty { targetHost }
            OGLogger.i("TLS", "Handshaking using $sniHost")

            val sslContext = if (profile.allowInsecureCert) {
                OGLogger.w("TLS", "Security Notice: Insecure certificate verification active")
                createInsecureSslContext()
            } else {
                SSLContext.getDefault()
            }

            val sslSocket = sslContext.socketFactory.createSocket(
                baseSocket,
                sniHost,
                targetPort,
                true
            ) as SSLSocket

            sslSocket.startHandshake()
            OGLogger.i("TLS", "Handshaked")
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

        val displayPayload = request.replace("\r\n", "[crlf]")
        OGLogger.i("HTTP", "Sending Payload: $displayPayload")
        out.write(request.toByteArray(Charsets.UTF_8))
        out.flush()

        // Read response header until empty line
        val responseHeader = readHttpHeader(inp)
        val statusLine = responseHeader.lines().firstOrNull() ?: ""
        OGLogger.i("HTTP", "Response: $statusLine")

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

    private fun fetchServerBanner(session: Session) {
        scope.launch {
            try {
                val execChannel = session.openChannel("exec") as? ChannelExec ?: return@launch
                execChannel.setCommand("cat /etc/issue.net 2>/dev/null || cat /etc/motd 2>/dev/null")
                val inp = execChannel.inputStream
                execChannel.connect(3000)
                val reader = inp.bufferedReader()
                val bannerBuilder = StringBuilder()
                var linesCount = 0
                while (linesCount < 50) {
                    val currentLine = reader.readLine() ?: break
                    if (currentLine.isNotBlank()) {
                        bannerBuilder.appendLine(currentLine)
                        linesCount++
                    }
                }
                execChannel.disconnect()
                val banner = bannerBuilder.toString().trim()
                if (banner.isNotEmpty() && !hasBannerBeenLogged.getAndSet(true)) {
                    OGLogger.i("SSH", "Server Message:\n$banner")
                }
            } catch (_: Exception) {}
        }
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
        val serverSocket = try {
            ServerSocket(requestedLocalSocksPort, 50, InetAddress.getByName("127.0.0.1"))
        } catch (_: Exception) {
            ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        }
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

    private fun startHttpPingLoop() {
        httpPingJob?.cancel()
        httpPingJob = scope.launch {
            delay(1500)
            while (isActive && isRunning.get()) {
                try {
                    val start = System.currentTimeMillis()
                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localSocksPort))
                    val url = URL("http://connectivitycheck.gstatic.com/generate_204")
                    val conn = url.openConnection(proxy) as HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.instanceFollowRedirects = false
                    val code = conn.responseCode
                    val latency = System.currentTimeMillis() - start
                    if (code == 204 || code == 200) {
                        OGLogger.i("HTTP", "HTTP Ping $code No Content (${latency}ms)")
                    }
                } catch (_: Exception) {}
                delay(5000)
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        httpPingJob?.cancel()
        httpPingJob = null
        try {
            socksServer?.stop()
        } catch (_: Exception) {}
        try {
            jschSession?.disconnect()
        } catch (_: Exception) {}
        try {
            rawTransportSocket?.close()
        } catch (_: Exception) {}
        OGLogger.i("SSH", "Disconnected")
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
