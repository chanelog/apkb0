package com.example.engine.ssh

import com.example.utils.OGLogger
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class Socks5LocalServer(
    private val serverSocket: ServerSocket,
    private val session: Session,
    private val socketProtector: ((Socket) -> Unit)? = null,
    private val onTraffic: (up: Long, down: Long) -> Unit
) {
    private val isRunning = AtomicBoolean(false)
    private val threadPool = Executors.newCachedThreadPool()

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        threadPool.execute {
            try {
                while (isRunning.get() && !serverSocket.isClosed) {
                    val clientSocket = serverSocket.accept()
                    threadPool.execute {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    OGLogger.w("Socks5LocalServer", "Server socket exception: ${e.message}")
                }
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
        var channel: ChannelDirectTCPIP? = null
        try {
            clientSocket.soTimeout = 15000
            val inp = clientSocket.getInputStream()
            val out = clientSocket.getOutputStream()

            // 1. Negotiation Handshake
            val version = inp.read()
            if (version != 5) {
                clientSocket.close()
                return
            }
            val numMethods = inp.read()
            val methods = ByteArray(numMethods.coerceAtLeast(1))
            inp.read(methods)

            // Reply: Version 5, No Authentication Required (0x00)
            out.write(byteArrayOf(0x05, 0x00))
            out.flush()

            // 2. Request
            val ver = inp.read()
            val cmd = inp.read()
            val rsv = inp.read() // reserved
            val atyp = inp.read()

            if (ver != 5 || cmd != 1) { // 1 = CONNECT
                // Command not supported
                out.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                out.flush()
                clientSocket.close()
                return
            }

            val targetHost: String
            when (atyp) {
                1 -> { // IPv4
                    val ip = ByteArray(4)
                    inp.read(ip)
                    targetHost = InetAddress.getByAddress(ip).hostAddress ?: ""
                }
                3 -> { // Domain name
                    val len = inp.read()
                    val domainBytes = ByteArray(len)
                    inp.read(domainBytes)
                    targetHost = String(domainBytes, Charsets.UTF_8)
                }
                4 -> { // IPv6
                    val ip = ByteArray(16)
                    inp.read(ip)
                    targetHost = InetAddress.getByAddress(ip).hostAddress ?: ""
                }
                else -> {
                    clientSocket.close()
                    return
                }
            }

            val portHigh = inp.read()
            val portLow = inp.read()
            val targetPort = (portHigh shl 8) or (portLow and 0xFF)

            // Open direct-tcpip channel on SSH session
            channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setHost(targetHost)
            channel.setPort(targetPort)
            channel.connect(10000)

            // Send SOCKS5 success reply
            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
            out.flush()

            val channelInp = channel.inputStream
            val channelOut = channel.outputStream

            // Relay data bidirectionally
            val t1 = Thread {
                pipe(inp, channelOut) { bytes -> onTraffic(bytes, 0L) }
            }
            val t2 = Thread {
                pipe(channelInp, out) { bytes -> onTraffic(0L, bytes) }
            }

            t1.start()
            t2.start()
            t1.join()
            t2.join()

        } catch (e: Exception) {
            // normal connection close or timeout
        } finally {
            try { channel?.disconnect() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit) {
        val buf = ByteArray(16384)
        try {
            while (isRunning.get()) {
                val len = input.read(buf)
                if (len == -1) break
                output.write(buf, 0, len)
                output.flush()
                onBytes(len.toLong())
            }
        } catch (_: Exception) {
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket.close()
        } catch (_: Exception) {}
        threadPool.shutdownNow()
    }
}
