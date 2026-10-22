package com.example.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.example.domain.model.ConnectionState
import com.example.domain.model.ConnectionStats
import com.example.domain.model.Profile
import com.example.utils.OGLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

object VpnController {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectionStats = MutableStateFlow(ConnectionStats())
    val connectionStats: StateFlow<ConnectionStats> = _connectionStats.asStateFlow()

    private val _activeProfile = MutableStateFlow<Profile?>(null)
    val activeProfile: StateFlow<Profile?> = _activeProfile.asStateFlow()

    private var durationJob: Job? = null
    private var pingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private var lastUploadedBytes = 0L
    private var lastDownloadedBytes = 0L

    fun updateState(state: ConnectionState, profile: Profile? = null, error: String? = null) {
        OGLogger.i("VpnController", "VPN State changed: ${state.name} (Profile: ${profile?.name})")
        _connectionState.value = state
        if (profile != null) {
            _activeProfile.value = profile
        }

        if (state == ConnectionState.CONNECTED) {
            startStatsTimers()
        } else if (state.isTerminal) {
            stopStatsTimers()
        }

        if (error != null) {
            _connectionStats.value = _connectionStats.value.copy(lastError = error)
        }
    }

    fun updateTraffic(upload: Long, download: Long) {
        val current = _connectionStats.value
        _connectionStats.value = current.copy(
            uploadBytes = upload,
            downloadBytes = download
        )
    }

    private fun startStatsTimers() {
        stopStatsTimers()
        lastUploadedBytes = 0L
        lastDownloadedBytes = 0L

        var seconds = 0L
        durationJob = scope.launch {
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                delay(1000)
                seconds++
                val cur = _connectionStats.value
                val upDiff = (cur.uploadBytes - lastUploadedBytes).coerceAtLeast(0L)
                val downDiff = (cur.downloadBytes - lastDownloadedBytes).coerceAtLeast(0L)
                lastUploadedBytes = cur.uploadBytes
                lastDownloadedBytes = cur.downloadBytes

                _connectionStats.value = cur.copy(
                    durationSeconds = seconds,
                    uploadSpeedBps = upDiff,
                    downloadSpeedBps = downDiff
                )
            }
        }

        // Measure periodic ping latency to endpoint
        pingJob = scope.launch {
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                val profile = _activeProfile.value
                if (profile != null) {
                    val targetHost = if (profile.protocol.isSsh) {
                        profile.wsHost.ifEmpty { profile.sshHost }
                    } else {
                        profile.serverAddress
                    }
                    val targetPort = if (profile.protocol.isSsh) {
                        if (profile.wsHost.isNotBlank()) profile.wsPort else profile.sshPort
                    } else {
                        profile.serverPort
                    }

                    val latency = measureTcpPing(targetHost, targetPort, 3000)
                    _connectionStats.value = _connectionStats.value.copy(pingMs = latency)
                }
                delay(8000)
            }
        }
    }

    private fun stopStatsTimers() {
        durationJob?.cancel()
        pingJob?.cancel()
        durationJob = null
        pingJob = null
    }

    fun startVpn(context: Context, profile: Profile) {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            // Activity needs to request permission
            OGLogger.w("VpnController", "VpnService.prepare requires user consent")
            return
        }

        updateState(ConnectionState.CONNECTING, profile)
        val intent = Intent(context, OGTunnelVpnService::class.java).apply {
            action = OGTunnelVpnService.ACTION_CONNECT
            putExtra(OGTunnelVpnService.EXTRA_PROFILE_ID, profile.id)
        }
        context.startService(intent)
    }

    fun stopVpn(context: Context) {
        updateState(ConnectionState.DISCONNECTING)
        val intent = Intent(context, OGTunnelVpnService::class.java).apply {
            action = OGTunnelVpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }

    fun measureTcpPing(host: String, port: Int, timeoutMs: Int = 3000): Long? {
        return try {
            val start = System.currentTimeMillis()
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.close()
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            null
        }
    }

    suspend fun testSshAuth(profile: Profile, timeoutMs: Int = 8000): Pair<Boolean, String> {
        return try {
            val targetHost = if (profile.protocol.isSsh && profile.wsHost.isNotBlank()) profile.wsHost else profile.sshHost
            val targetPort = if (profile.protocol.isSsh && profile.wsHost.isNotBlank()) profile.wsPort else profile.sshPort

            val socket = Socket()
            socket.connect(InetSocketAddress(targetHost, targetPort), timeoutMs)

            var transSock: Socket = socket
            if (profile.tlsEnabled) {
                val sc = SSLContext.getDefault()
                val sni = profile.tlsSni.ifEmpty { targetHost }
                val ssl = sc.socketFactory.createSocket(socket, sni, targetPort, true) as SSLSocket
                ssl.startHandshake()
                transSock = ssl
            }

            if (profile.protocol == com.example.domain.model.TunnelProtocol.SSH_WS) {
                val out = transSock.getOutputStream()
                val inp = transSock.getInputStream()
                val wsKey = Base64.getEncoder().encodeToString(ByteArray(16).apply { SecureRandom().nextBytes(this) })
                val header = "GET ${profile.wsPath.ifEmpty { "/ssh" }} HTTP/1.1\r\nHost: $targetHost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $wsKey\r\nSec-WebSocket-Version: 13\r\n\r\n"
                out.write(header.toByteArray())
                out.flush()

                val line = inp.bufferedReader().readLine() ?: ""
                transSock.close()
                if (line.contains("101") || line.contains("200")) {
                    Pair(true, "WebSocket Upgrade Success: $line")
                } else {
                    Pair(false, "WebSocket rejected: $line")
                }
            } else {
                // Direct TCP read identification
                transSock.soTimeout = timeoutMs
                val banner = transSock.getInputStream().bufferedReader().readLine() ?: ""
                transSock.close()
                Pair(true, "SSH Endpoint Reachable: $banner")
            }
        } catch (e: Exception) {
            Pair(false, "SSH Connection Test Failed: ${e.message}")
        }
    }

    suspend fun testDnsLookup(domain: String, dnsServerIp: String = "1.1.1.1"): List<String> {
        return try {
            val addresses = InetAddress.getAllByName(domain)
            addresses.map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
