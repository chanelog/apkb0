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
                        // Forward DNS query
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

                    val flags = packet[ihl + 13].toInt() and 0xFF
                    val isSyn = (flags and 0x02) != 0
                    val isFin = (flags and 0x01) != 0
                    val isRst = (flags and 0x04) != 0

                    if (isSyn) {
                        // Acknowledge SYN and establish tunnel connection
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
                        session.handleSyn(packet, ihl)
                    } else if (isFin || isRst) {
                        tcpConnections.remove(sessionKey)?.close()
                    } else {
                        // Data packet
                        val tcpHeaderLen = ((packet[ihl + 12].toInt() and 0xF0) ushr 4) * 4
                        val payloadOffset = ihl + tcpHeaderLen
                        if (length > payloadOffset) {
                            val data = packet.copyOfRange(payloadOffset, length)
                            tcpConnections[sessionKey]?.forwardData(data)
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
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            vpnService.protect(socket)
            socket.soTimeout = 5000

            val dnsServer = InetAddress.getByName(primaryDns)
            val sendPacket = DatagramPacket(queryData, queryData.size, dnsServer, 53)
            socket.send(sendPacket)

            val recvBuf = ByteArray(4096)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            socket.receive(recvPacket)

            val replyData = recvBuf.copyOf(recvPacket.length)
            val ipReply = buildUdpIpPacket(
                srcIp = dstIp,
                srcPort = dstPort,
                dstIp = srcIp,
                dstPort = srcPort,
                payload = replyData
            )

            synchronized(fileOut) {
                fileOut.write(ipReply)
                fileOut.flush()
            }
            onTraffic(0L, ipReply.size.toLong())

        } catch (e: Exception) {
            // DNS resolution timeout
        } finally {
            socket?.close()
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

        // IPv4 Header (20 bytes)
        buf.put(0x45.toByte()) // Ver=4, IHL=5
        buf.put(0x00.toByte()) // DSCP
        buf.putShort(totalLength.toShort())
        buf.putShort(0.toShort()) // ID
        buf.putShort(0x4000.toShort()) // Flags (DF)
        buf.put(64.toByte()) // TTL
        buf.put(17.toByte()) // Protocol (UDP)
        buf.putShort(0.toShort()) // Checksum placeholder

        val srcBytes = InetAddress.getByName(srcIp).address
        val dstBytes = InetAddress.getByName(dstIp).address
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

    fun handleSyn(packet: ByteArray, ihl: Int) {
        val clientSeq = ((packet[ihl + 4].toLong() and 0xFF) shl 24) or
                        ((packet[ihl + 5].toLong() and 0xFF) shl 16) or
                        ((packet[ihl + 6].toLong() and 0xFF) shl 8) or
                        (packet[ihl + 7].toLong() and 0xFF)

        // Send SYN-ACK back to TUN interface
        val synAck = buildTcpPacket(
            srcIp = dstIp,
            srcPort = dstPort,
            dstIp = srcIp,
            dstPort = srcPort,
            seq = 1000L,
            ack = clientSeq + 1,
            flags = 0x12 // SYN + ACK
        )

        synchronized(fileOut) {
            fileOut.write(synAck)
            fileOut.flush()
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

                // Pipe inbound responses from SOCKS server back into TUN as TCP packets
                val buf = ByteArray(16384)
                var currentSeq = 1001L
                while (!isClosed.get()) {
                    val len = inp.read(buf)
                    if (len == -1) break

                    val tcpDataPacket = buildTcpDataPacket(
                        srcIp = dstIp,
                        srcPort = dstPort,
                        dstIp = srcIp,
                        dstPort = srcPort,
                        seq = currentSeq,
                        ack = clientSeq + 1,
                        payload = buf.copyOf(len)
                    )
                    currentSeq += len

                    synchronized(fileOut) {
                        fileOut.write(tcpDataPacket)
                        fileOut.flush()
                    }
                    onTraffic(0L, tcpDataPacket.size.toLong())
                }
            } catch (_: Exception) {
            } finally {
                close()
            }
        }.start()
    }

    fun forwardData(data: ByteArray) {
        try {
            clientSocket?.outputStream?.let {
                it.write(data)
                it.flush()
                onTraffic(data.size.toLong(), 0L)
            }
        } catch (_: Exception) {}
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

        // IP Header
        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLength.toShort())
        buf.putShort(1.toShort())
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(6.toByte()) // TCP
        buf.putShort(0.toShort())
        buf.put(InetAddress.getByName(srcIp).address)
        buf.put(InetAddress.getByName(dstIp).address)

        // TCP Header
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seq.toInt())
        buf.putInt(ack.toInt())
        buf.put(0x50.toByte()) // Data offset 5 (20 bytes)
        buf.put(flags.toByte())
        buf.putShort(65535.toShort()) // Window
        buf.putShort(0.toShort()) // Checksum
        buf.putShort(0.toShort()) // Urgent pointer

        val array = buf.array()
        val ipChecksum = computeChecksum(array, 0, 20)
        array[10] = (ipChecksum ushr 8).toByte()
        array[11] = (ipChecksum and 0xFF).toByte()

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

        // IP Header
        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLength.toShort())
        buf.putShort(1.toShort())
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(6.toByte()) // TCP
        buf.putShort(0.toShort())
        buf.put(InetAddress.getByName(srcIp).address)
        buf.put(InetAddress.getByName(dstIp).address)

        // TCP Header
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seq.toInt())
        buf.putInt(ack.toInt())
        buf.put(0x50.toByte()) // Data offset 5
        buf.put(0x18.toByte()) // PSH + ACK
        buf.putShort(65535.toShort())
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())

        buf.put(payload)

        val array = buf.array()
        val ipChecksum = computeChecksum(array, 0, 20)
        array[10] = (ipChecksum ushr 8).toByte()
        array[11] = (ipChecksum and 0xFF).toByte()

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
}
