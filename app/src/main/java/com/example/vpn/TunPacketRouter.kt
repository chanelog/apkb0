package com.example.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.example.utils.OGLogger
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TunPacketRouter(
    private val vpnInterface: ParcelFileDescriptor,
    private val vpnService: VpnService,
    private val localSocksPort: Int,
    private val primaryDns: String = "1.1.1.1",
    private val onTraffic: (up: Long, down: Long) -> Unit
) {
    private val isRunning = AtomicBoolean(false)
    private val threadPool = Executors.newCachedThreadPool()
    private val tcpConnections = ConcurrentHashMap<String, VirtualTcpSession>()

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        OGLogger.i("TunPacketRouter", "Starting TUN packet loop (SOCKS target: 127.0.0.1:$localSocksPort, DNS: $primaryDns)")

        threadPool.execute {
            val fileIn = FileInputStream(vpnInterface.fileDescriptor)
            val fileOut = FileOutputStream(vpnInterface.fileDescriptor)
            val packet = ByteArray(32767)

            try {
                while (isRunning.get()) {
                    val length = fileIn.read(packet)
                    if (length <= 0) break

                    onTraffic(length.toLong(), 0L)
                    handlePacket(packet, length, fileOut)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    OGLogger.w("TunPacketRouter", "TUN read loop terminated: ${e.message}")
                }
            } finally {
                stop()
            }
        }
    }

    private fun handlePacket(packet: ByteArray, length: Int, fileOut: FileOutputStream) {
        if (length < 20) return
        val versionAndIHL = packet[0].toInt() and 0xFF
        val version = versionAndIHL ushr 4
        if (version != 4) return // IPv4 only

        val ihl = (versionAndIHL and 0x0F) * 4
        val protocol = packet[9].toInt() and 0xFF

        val srcIp = "${packet[12].toUByte()}.${packet[13].toUByte()}.${packet[14].toUByte()}.${packet[15].toUByte()}"
        val dstIp = "${packet[16].toUByte()}.${packet[17].toUByte()}.${packet[18].toUByte()}.${packet[19].toUByte()}"

        when (protocol) {
            17 -> { // UDP (e.g. DNS)
                if (length >= ihl + 8) {
                    val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
                    val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)

                    if (dstPort == 53) {
                        val udpPayload = packet.copyOfRange(ihl + 8, length)
                        threadPool.execute {
                            resolveDns(srcIp, srcPort, dstIp, dstPort, udpPayload, fileOut)
                        }
                    }
                }
            }
            6 -> { // TCP
                if (length >= ihl + 20) {
                    val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
                    val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
                    val sessionKey = "$srcIp:$srcPort->$dstIp:$dstPort"

                    val clientSeq = ((packet[ihl + 4].toLong() and 0xFF) shl 24) or
                                    ((packet[ihl + 5].toLong() and 0xFF) shl 16) or
                                    ((packet[ihl + 6].toLong() and 0xFF) shl 8) or
                                    (packet[ihl + 7].toLong() and 0xFF)

                    val flags = packet[ihl + 13].toInt() and 0xFF
                    val isSyn = (flags and 0x02) != 0
                    val isFin = (flags and 0x01) != 0
                    val isRst = (flags and 0x04) != 0

                    if (isSyn) {
                        val session = VirtualTcpSession(
                            srcIp = srcIp,
                            srcPort = srcPort,
                            dstIp = dstIp,
                            dstPort = dstPort,
                            localSocksPort = localSocksPort,
                            vpnService = vpnService,
                            fileOut = fileOut,
                            onTraffic = onTraffic
                        )
                        tcpConnections[sessionKey] = session
                        session.handleSyn(clientSeq)
                    } else if (isFin) {
                        tcpConnections.remove(sessionKey)?.handleFin(clientSeq)
                    } else if (isRst) {
                        tcpConnections.remove(sessionKey)?.close()
                    } else {
                        val tcpHeaderLen = ((packet[ihl + 12].toInt() and 0xF0) ushr 4) * 4
                        val payloadOffset = ihl + tcpHeaderLen
                        if (length > payloadOffset) {
                            val data = packet.copyOfRange(payloadOffset, length)
                            tcpConnections[sessionKey]?.forwardData(data, clientSeq)
                        }
                    }
                }
            }
        }
    }

    private fun resolveDns(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        queryData: ByteArray,
        fileOut: FileOutputStream
    ) {
        var replyData: ByteArray? = null
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            vpnService.protect(socket)
            socket.soTimeout = 2000

            val dnsServer = InetAddress.getByName(primaryDns)
            val sendPacket = DatagramPacket(queryData, queryData.size, dnsServer, 53)
            socket.send(sendPacket)

            val recvBuf = ByteArray(4096)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            socket.receive(recvPacket)
            replyData = recvBuf.copyOf(recvPacket.length)
        } catch (_: Exception) {
            // Mobile operator blocks direct UDP 53; fall back to DNS-over-TCP via SOCKS5 tunnel
            replyData = resolveDnsOverSocks(queryData)
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }

        if (replyData != null) {
            val ipReply = buildUdpIpPacket(
                srcIp = dstIp,
                srcPort = dstPort,
                dstIp = srcIp,
                dstPort = srcPort,
                payload = replyData
            )

            synchronized(fileOut) {
                try {
                    fileOut.write(ipReply)
                    fileOut.flush()
                } catch (_: Exception) {}
            }
            onTraffic(0L, ipReply.size.toLong())
        }
    }

    private fun resolveDnsOverSocks(queryData: ByteArray): ByteArray? {
        var sock: Socket? = null
        try {
            sock = Socket()
            vpnService.protect(sock)
            sock.connect(InetSocketAddress("127.0.0.1", localSocksPort), 3000)
            sock.soTimeout = 5000

            val out = sock.getOutputStream()
            val inp = sock.getInputStream()

            // 1. SOCKS5 Handshake
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val resp = ByteArray(2)
            if (inp.read(resp) != 2 || resp[0] != 0x05.toByte() || resp[1] != 0x00.toByte()) {
                return null
            }

            // 2. SOCKS5 Connect to DNS Server port 53
            val dnsHost = primaryDns.ifEmpty { "1.1.1.1" }
            val dnsIpBytes = InetAddress.getByName(dnsHost).address
            val req = ByteBuffer.allocate(10)
            req.put(0x05.toByte())
            req.put(0x01.toByte()) // CONNECT
            req.put(0x00.toByte()) // RSV
            req.put(0x01.toByte()) // IPv4
            req.put(dnsIpBytes)
            req.putShort(53.toShort())

            out.write(req.array())
            out.flush()

            val connectResp = ByteArray(10)
            val n = inp.read(connectResp)
            if (n < 4 || connectResp[1] != 0x00.toByte()) {
                return null
            }

            // 3. DNS-over-TCP (RFC 7766): 2-byte length prefix
            val qLen = queryData.size
            val tcpDnsReq = ByteArray(2 + qLen)
            tcpDnsReq[0] = (qLen ushr 8).toByte()
            tcpDnsReq[1] = (qLen and 0xFF).toByte()
            System.arraycopy(queryData, 0, tcpDnsReq, 2, qLen)

            out.write(tcpDnsReq)
            out.flush()

            // 4. Read 2-byte response length
            val lenBuf = ByteArray(2)
            var read = 0
            while (read < 2) {
                val r = inp.read(lenBuf, read, 2 - read)
                if (r == -1) return null
                read += r
            }
            val respLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
            if (respLen <= 0 || respLen > 4096) return null

            // 5. Read response payload
            val answer = ByteArray(respLen)
            var totalRead = 0
            while (totalRead < respLen) {
                val r = inp.read(answer, totalRead, respLen - totalRead)
                if (r == -1) break
                totalRead += r
            }
            return if (totalRead == respLen) answer else null
        } catch (_: Exception) {
            return null
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    private fun buildUdpIpPacket(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLength = 20 + 8 + payload.size
        val buf = ByteBuffer.allocate(totalLength)

        val srcBytes = InetAddress.getByName(srcIp).address
        val dstBytes = InetAddress.getByName(dstIp).address

        // IPv4 Header (20 bytes)
        buf.put(0x45.toByte()) // Ver=4, IHL=5
        buf.put(0x00.toByte()) // DSCP
        buf.putShort(totalLength.toShort())
        buf.putShort(0.toShort()) // ID
        buf.putShort(0x4000.toShort()) // Flags (DF)
        buf.put(64.toByte()) // TTL
        buf.put(17.toByte()) // Protocol (UDP)
        buf.putShort(0.toShort()) // Checksum placeholder
        buf.put(srcBytes)
        buf.put(dstBytes)

        // UDP Header (8 bytes)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort((8 + payload.size).toShort())
        buf.putShort(0.toShort()) // Checksum

        buf.put(payload)

        // Compute IP checksum
        val data = buf.array()
        val ipChecksum = computeIpChecksum(data, 0, 20)
        data[10] = (ipChecksum ushr 8).toByte()
        data[11] = (ipChecksum and 0xFF).toByte()

        return data
    }

    private fun computeIpChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            val high = buf[i].toInt() and 0xFF
            val low = if (i + 1 < offset + length) buf[i + 1].toInt() and 0xFF else 0
            sum += (high shl 8) or low
            i += 2
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        for (session in tcpConnections.values) {
            session.close()
        }
        tcpConnections.clear()
        threadPool.shutdownNow()
        OGLogger.i("TunPacketRouter", "TUN packet router stopped")
    }
}

class VirtualTcpSession(
    private val srcIp: String,
    private val srcPort: Int,
    private val dstIp: String,
    private val dstPort: Int,
    private val localSocksPort: Int,
    private val vpnService: VpnService,
    private val fileOut: FileOutputStream,
    private val onTraffic: (up: Long, down: Long) -> Unit
) {
    private var clientSocket: Socket? = null
    private val isClosed = AtomicBoolean(false)
    private var ourSeq: Long = 1000L
    private var expectedClientSeq: Long = 0L
    private val pendingDataBuffer = ConcurrentLinkedQueue<ByteArray>()

    fun handleSyn(clientSeq: Long) {
        synchronized(this) {
            expectedClientSeq = (clientSeq + 1) and 0xFFFFFFFFL
            ourSeq = 1000L

            // Send SYN-ACK back to TUN interface
            val synAck = buildTcpPacket(
                srcIp = dstIp,
                srcPort = dstPort,
                dstIp = srcIp,
                dstPort = srcPort,
                seq = ourSeq,
                ack = expectedClientSeq,
                flags = 0x12 // SYN + ACK
            )
            ourSeq = (ourSeq + 1) and 0xFFFFFFFFL

            synchronized(fileOut) {
                try {
                    fileOut.write(synAck)
                    fileOut.flush()
                } catch (_: Exception) {}
            }
        }

        // Connect to local SOCKS5 proxy
        Thread {
            try {
                val sock = Socket()
                vpnService.protect(sock)
                sock.connect(InetSocketAddress("127.0.0.1", localSocksPort), 5000)
                clientSocket = sock

                // SOCKS5 Handshake with local forwarder
                val out = sock.getOutputStream()
                val inp = sock.getInputStream()

                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val resp = ByteArray(2)
                inp.read(resp)

                // SOCKS5 Connect to target IP & Port
                val dstIpBytes = InetAddress.getByName(dstIp).address
                val req = ByteBuffer.allocate(10)
                req.put(0x05.toByte())
                req.put(0x01.toByte()) // CONNECT
                req.put(0x00.toByte()) // RSV
                req.put(0x01.toByte()) // IPv4
                req.put(dstIpBytes)
                req.putShort(dstPort.toShort())

                out.write(req.array())
                out.flush()

                val connectResp = ByteArray(10)
                inp.read(connectResp)

                // Flush any buffered client data
                synchronized(this) {
                    while (pendingDataBuffer.isNotEmpty()) {
                        val pending = pendingDataBuffer.poll() ?: break
                        out.write(pending)
                        onTraffic(pending.size.toLong(), 0L)
                    }
                    out.flush()
                }

                // Pipe inbound responses from SOCKS server back into TUN as TCP packets
                val buf = ByteArray(16384)
                while (!isClosed.get()) {
                    val len = inp.read(buf)
                    if (len == -1) break

                    val tcpDataPacket = synchronized(this) {
                        val pkt = buildTcpDataPacket(
                            srcIp = dstIp,
                            srcPort = dstPort,
                            dstIp = srcIp,
                            dstPort = srcPort,
                            seq = ourSeq,
                            ack = expectedClientSeq,
                            payload = buf.copyOf(len)
                        )
                        ourSeq = (ourSeq + len) and 0xFFFFFFFFL
                        pkt
                    }

                    synchronized(fileOut) {
                        fileOut.write(tcpDataPacket)
                        fileOut.flush()
                    }
                    onTraffic(0L, tcpDataPacket.size.toLong())
                }

                // Send FIN packet when remote finishes
                synchronized(this) {
                    if (!isClosed.get()) {
                        val finPacket = buildTcpPacket(
                            srcIp = dstIp,
                            srcPort = dstPort,
                            dstIp = srcIp,
                            dstPort = srcPort,
                            seq = ourSeq,
                            ack = expectedClientSeq,
                            flags = 0x11 // FIN + ACK
                        )
                        ourSeq = (ourSeq + 1) and 0xFFFFFFFFL
                        synchronized(fileOut) {
                            try {
                                fileOut.write(finPacket)
                                fileOut.flush()
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                close()
            }
        }.start()
    }

    fun forwardData(data: ByteArray, clientSeq: Long) {
        synchronized(this) {
            expectedClientSeq = (clientSeq + data.size) and 0xFFFFFFFFL

            // Send immediate ACK back to client
            val ackPacket = buildTcpPacket(
                srcIp = dstIp,
                srcPort = dstPort,
                dstIp = srcIp,
                dstPort = srcPort,
                seq = ourSeq,
                ack = expectedClientSeq,
                flags = 0x10 // ACK
            )
            synchronized(fileOut) {
                try {
                    fileOut.write(ackPacket)
                    fileOut.flush()
                } catch (_: Exception) {}
            }

            val sock = clientSocket
            if (sock != null && !sock.isClosed) {
                try {
                    sock.outputStream.write(data)
                    sock.outputStream.flush()
                    onTraffic(data.size.toLong(), 0L)
                } catch (_: Exception) {}
            } else {
                pendingDataBuffer.add(data)
            }
        }
    }

    fun handleFin(clientSeq: Long) {
        synchronized(this) {
            expectedClientSeq = (clientSeq + 1) and 0xFFFFFFFFL
            val finAck = buildTcpPacket(
                srcIp = dstIp,
                srcPort = dstPort,
                dstIp = srcIp,
                dstPort = srcPort,
                seq = ourSeq,
                ack = expectedClientSeq,
                flags = 0x10 // ACK
            )
            synchronized(fileOut) {
                try {
                    fileOut.write(finAck)
                    fileOut.flush()
                } catch (_: Exception) {}
            }
        }
        close()
    }

    fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        try { clientSocket?.close() } catch (_: Exception) {}
    }

    private fun buildTcpPacket(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int
    ): ByteArray {
        val totalLength = 40 // 20 IP + 20 TCP
        val buf = ByteBuffer.allocate(totalLength)

        val srcBytes = InetAddress.getByName(srcIp).address
        val dstBytes = InetAddress.getByName(dstIp).address

        // IP Header
        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLength.toShort())
        buf.putShort(1.toShort())
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(6.toByte()) // TCP
        buf.putShort(0.toShort())
        buf.put(srcBytes)
        buf.put(dstBytes)

        // TCP Header
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seq.toInt())
        buf.putInt(ack.toInt())
        buf.put(0x50.toByte()) // Data offset 5 (20 bytes)
        buf.put(flags.toByte())
        buf.putShort(65535.toShort()) // Window
        buf.putShort(0.toShort()) // Checksum placeholder
        buf.putShort(0.toShort()) // Urgent pointer

        val array = buf.array()

        // 1. IP Checksum
        val ipChecksum = computeChecksum(array, 0, 20)
        array[10] = (ipChecksum ushr 8).toByte()
        array[11] = (ipChecksum and 0xFF).toByte()

        // 2. TCP Checksum
        val tcpChecksum = computeTcpChecksum(srcBytes, dstBytes, array, 20, 20)
        array[36] = (tcpChecksum ushr 8).toByte()
        array[37] = (tcpChecksum and 0xFF).toByte()

        return array
    }

    private fun buildTcpDataPacket(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        seq: Long,
        ack: Long,
        payload: ByteArray
    ): ByteArray {
        val totalLength = 40 + payload.size
        val buf = ByteBuffer.allocate(totalLength)

        val srcBytes = InetAddress.getByName(srcIp).address
        val dstBytes = InetAddress.getByName(dstIp).address

        // IP Header
        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLength.toShort())
        buf.putShort(1.toShort())
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(6.toByte()) // TCP
        buf.putShort(0.toShort())
        buf.put(srcBytes)
        buf.put(dstBytes)

        // TCP Header
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seq.toInt())
        buf.putInt(ack.toInt())
        buf.put(0x50.toByte()) // Data offset 5
        buf.put(0x18.toByte()) // PSH + ACK
        buf.putShort(65535.toShort())
        buf.putShort(0.toShort()) // Checksum placeholder
        buf.putShort(0.toShort())

        buf.put(payload)

        val array = buf.array()

        // 1. IP Checksum
        val ipChecksum = computeChecksum(array, 0, 20)
        array[10] = (ipChecksum ushr 8).toByte()
        array[11] = (ipChecksum and 0xFF).toByte()

        // 2. TCP Checksum
        val tcpChecksum = computeTcpChecksum(srcBytes, dstBytes, array, 20, 20 + payload.size)
        array[36] = (tcpChecksum ushr 8).toByte()
        array[37] = (tcpChecksum and 0xFF).toByte()

        return array
    }

    private fun computeChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            val high = buf[i].toInt() and 0xFF
            val low = if (i + 1 < offset + length) buf[i + 1].toInt() and 0xFF else 0
            sum += (high shl 8) or low
            i += 2
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun computeTcpChecksum(
        srcBytes: ByteArray,
        dstBytes: ByteArray,
        packet: ByteArray,
        tcpOffset: Int,
        tcpLength: Int
    ): Int {
        var sum = 0L

        // IPv4 Pseudo Header:
        // Source IP (4 bytes)
        for (i in 0 until 4 step 2) {
            sum += ((srcBytes[i].toInt() and 0xFF) shl 8) or (srcBytes[i + 1].toInt() and 0xFF)
        }
        // Destination IP (4 bytes)
        for (i in 0 until 4 step 2) {
            sum += ((dstBytes[i].toInt() and 0xFF) shl 8) or (dstBytes[i + 1].toInt() and 0xFF)
        }
        // Zero (1 byte) + Protocol (1 byte: 6)
        sum += 6L
        // TCP Length (2 bytes)
        sum += tcpLength.toLong()

        // TCP Segment (header + payload)
        var i = 0
        while (i < tcpLength) {
            if (i == 16) {
                // Skip existing checksum field
                i += 2
                continue
            }
            val high = packet[tcpOffset + i].toInt() and 0xFF
            val low = if (i + 1 < tcpLength) packet[tcpOffset + i + 1].toInt() and 0xFF else 0
            sum += ((high shl 8) or low).toLong()
            i += 2
        }

        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = (sum.inv() and 0xFFFF).toInt()
        return if (checksum == 0) 0xFFFF else checksum
    }
}
