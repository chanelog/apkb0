package com.example.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.OGTunnelApplication
import com.example.R
import com.example.data.preferences.SplitTunnelMode
import com.example.domain.model.ConnectionState
import com.example.domain.model.Profile
import com.example.domain.model.ProfileValidationResult
import com.example.engine.ssh.SshWsEngine
import com.example.engine.xray.XrayCoreManager
import com.example.utils.OGLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class OGTunnelVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var sshEngine: SshWsEngine? = null
    private var xrayManager: XrayCoreManager? = null
    private var tunRouter: TunPacketRouter? = null

    private var activeProfile: Profile? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var reconnectJob: Job? = null
    private val retryCount = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        OGLogger.i("VpnService", "OGTunnelVpnService created")
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_CONNECT -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                if (profileId != null) {
                    serviceScope.launch {
                        startTunnel(profileId)
                    }
                }
            }
            ACTION_DISCONNECT -> {
                serviceScope.launch {
                    stopTunnel("User requested disconnect")
                }
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun startTunnel(profileId: String) {
        val app = OGTunnelApplication.instance
        val profile = app.database.profileDao().getProfileById(profileId)?.toDomain()
        if (profile == null) {
            OGLogger.e("VpnService", "Profile not found for ID: $profileId")
            VpnController.updateState(ConnectionState.FAILED, error = "Profile not found")
            stopSelf()
            return
        }

        activeProfile = profile

        // 1. Validating Profile
        VpnController.updateState(ConnectionState.VALIDATING, profile)
        val validationResult = profile.validate()
        if (validationResult is ProfileValidationResult.Invalid) {
            OGLogger.e("VpnService", "Profile validation error: ${validationResult.message}")
            VpnController.updateState(ConnectionState.FAILED, profile, error = validationResult.message)
            stopSelf()
            return
        }

        startForegroundNotification("Initializing ${profile.name}...")

        try {
            val settings = app.settingsManager.settingsFlow.first()

            // 2. Start Engine (SSHWS or Xray)
            val localSocksPort = 10808
            val actualSocksPort: Int

            if (profile.protocol.isSsh) {
                VpnController.updateState(ConnectionState.CONNECTING_TRANSPORT, profile)
                sshEngine = SshWsEngine(
                    profile = profile,
                    timeoutSeconds = settings.connectionTimeoutSeconds,
                    onTrafficUpdate = { up, down ->
                        VpnController.updateTraffic(up, down)
                    },
                    onStateUpdate = { state, msg ->
                        VpnController.updateState(state, profile)
                        if (msg != null) startForegroundNotification(msg)
                    },
                    socketProtector = { socket ->
                        val ok = protect(socket)
                        if (!ok) {
                            OGLogger.w("VpnService", "Socket protection warning: protect() returned false for $socket")
                        }
                    }
                )
                sshEngine?.start()
                actualSocksPort = sshEngine?.localSocksPort ?: localSocksPort
            } else {
                VpnController.updateState(ConnectionState.STARTING_ENGINE, profile)
                xrayManager = XrayCoreManager(
                    context = this,
                    profile = profile,
                    localSocksPort = localSocksPort,
                    localHttpPort = 10809,
                    onCrashOrExit = { code, msg ->
                        handleEngineCrash(code, msg)
                    }
                )
                val started = xrayManager?.start() == true
                if (!started) {
                    throw IllegalStateException("Xray core engine failed to start")
                }
                actualSocksPort = localSocksPort
            }

            // 3. Establish TUN Interface
            VpnController.updateState(ConnectionState.ESTABLISHING_VPN, profile)
            startForegroundNotification("Configuring TUN interface...")

            val builder = Builder()
                .setSession("OGTunnel - ${profile.name}")
                .setMtu(settings.mtu)
                .addAddress("10.8.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(settings.primaryDns)
                .addDnsServer(settings.secondaryDns)

            // Prevent VPN routing loops: Always exclude self app
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                OGLogger.w("VpnService", "Failed to exclude own package: ${e.message}")
            }

            // Handle Split Tunneling
            when (settings.splitTunnelMode) {
                SplitTunnelMode.INCLUDE_ONLY -> {
                    for (pkg in settings.splitTunnelPackages) {
                        try {
                            builder.addAllowedApplication(pkg)
                        } catch (e: Exception) {
                            OGLogger.w("VpnService", "Cannot route app $pkg: ${e.message}")
                        }
                    }
                }
                SplitTunnelMode.EXCLUDE_ONLY -> {
                    for (pkg in settings.splitTunnelPackages) {
                        if (pkg != packageName) {
                            try {
                                builder.addDisallowedApplication(pkg)
                            } catch (e: Exception) {
                                OGLogger.w("VpnService", "Cannot bypass app $pkg: ${e.message}")
                            }
                        }
                    }
                }
                SplitTunnelMode.ALL_APPS -> {}
            }

            val pfd = builder.establish()
                ?: throw IllegalStateException("Failed to establish TUN interface. Permission might be revoked.")
            vpnInterface = pfd

            // 4. Start TUN Packet Router
            tunRouter = TunPacketRouter(
                vpnInterface = pfd,
                vpnService = this,
                localSocksPort = actualSocksPort,
                primaryDns = settings.primaryDns,
                onTraffic = { up, down ->
                    VpnController.updateTraffic(up, down)
                }
            )
            tunRouter?.start()

            // 5. PRIORITY 2: VERIFY REAL CONNECTIVITY THROUGH TUNNEL BEFORE DECLARING CONNECTED
            VpnController.updateState(ConnectionState.TESTING_CONNECTIVITY, profile)
            startForegroundNotification("Verifying internet access through tunnel...")
            OGLogger.i("Diagnostics", "Performing bounded end-to-end connectivity test through tunnel (SOCKS: $actualSocksPort)...")

            val (verified, diagDetails) = verifyTunnelConnectivity(actualSocksPort, settings.primaryDns)
            if (!verified) {
                val errorMsg = "Internet verification failed through tunnel: $diagDetails"
                OGLogger.e("Diagnostics", errorMsg)
                VpnController.updateState(ConnectionState.FAILED, profile, error = errorMsg)
                stopTunnel("Connectivity test failed")
                return
            }

            OGLogger.success("Diagnostics", "Internet verified through tunnel ($diagDetails)")
            VpnController.updateState(ConnectionState.CONNECTED, profile)
            app.database.profileDao().updateLastUsed(profile.id, System.currentTimeMillis())
            retryCount.set(0)

            startForegroundNotification("Connected: ${profile.name} (${profile.protocol.displayName})")
            registerNetworkMonitor()

            OGLogger.success("VpnService", "VPN established and verified successfully. Routing device traffic through OGTunnel.")

        } catch (e: Exception) {
            OGLogger.e("VpnService", "VPN startup error: ${e.message}", e)
            VpnController.updateState(ConnectionState.FAILED, profile, error = e.message ?: "Startup failed")
            stopTunnel("Startup failed")
        }
    }

    private suspend fun verifyTunnelConnectivity(socksPort: Int, dnsServer: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var success = false
        var diagMsg = ""

        // Test HTTP/204 through SOCKS5 proxy
        val testUrls = listOf(
            "http://connectivitycheck.gstatic.com/generate_204",
            "http://cp.cloudflare.com/generate_204"
        )

        for (testUrl in testUrls) {
            try {
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
                val conn = URL(testUrl).openConnection(proxy) as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.instanceFollowRedirects = false
                val code = conn.responseCode
                val latency = System.currentTimeMillis() - start
                if (code in 200..299) {
                    success = true
                    diagMsg = "HTTP $code from $testUrl (${latency}ms)"
                    break
                }
            } catch (e: Exception) {
                diagMsg = "HTTP probe ($testUrl): ${e.message}"
            }
        }

        if (!success) {
            // Fallback: Test SOCKS5 TCP connect to DNS Server port 53
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress("127.0.0.1", socksPort), 3000)
                val out = sock.getOutputStream()
                val inp = sock.getInputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val resp = ByteArray(2)
                inp.read(resp)

                val dnsIpBytes = InetAddress.getByName(dnsServer.ifEmpty { "1.1.1.1" }).address
                val req = ByteBuffer.allocate(10)
                req.put(0x05.toByte())
                req.put(0x01.toByte()) // CONNECT
                req.put(0x00.toByte())
                req.put(0x01.toByte()) // IPv4
                req.put(dnsIpBytes)
                req.putShort(53.toShort())
                out.write(req.array())
                out.flush()

                val connectResp = ByteArray(10)
                val n = inp.read(connectResp)
                sock.close()
                if (n >= 4 && connectResp[1] == 0x00.toByte()) {
                    val latency = System.currentTimeMillis() - start
                    success = true
                    diagMsg = "SOCKS5 DNS endpoint $dnsServer:53 reachable (${latency}ms)"
                }
            } catch (e: Exception) {
                diagMsg = "SOCKS5 probe failed: ${e.message}"
            }
        }

        Pair(success, diagMsg)
    }

    private fun handleEngineCrash(code: Int, msg: String) {
        OGLogger.e("VpnService", "Engine crash detected: $msg")
        serviceScope.launch {
            val app = OGTunnelApplication.instance
            val settings = app.settingsManager.settingsFlow.first()

            if (settings.autoReconnect && retryCount.get() < settings.maxReconnectAttempts) {
                val attempt = retryCount.incrementAndGet()
                VpnController.updateState(ConnectionState.RECONNECTING, activeProfile)
                val backoffMs = (attempt * 2000L).coerceAtMost(10000L)
                OGLogger.w("VpnService", "Auto-reconnect attempt $attempt of ${settings.maxReconnectAttempts} in ${backoffMs}ms...")
                delay(backoffMs)
                activeProfile?.let { startTunnel(it.id) }
            } else {
                VpnController.updateState(ConnectionState.FAILED, activeProfile, error = "Engine crashed: $msg")
                stopTunnel("Engine crashed")
            }
        }
    }

    private fun registerNetworkMonitor() {
        if (networkCallback != null) return

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                OGLogger.w("VpnService", "Underlying physical network lost")
                VpnController.updateState(ConnectionState.RECONNECTING, activeProfile)
            }

            override fun onAvailable(network: Network) {
                OGLogger.i("VpnService", "Physical network available again")
                if (VpnController.connectionState.value == ConnectionState.RECONNECTING) {
                    reconnectJob?.cancel()
                    reconnectJob = serviceScope.launch {
                        delay(1000)
                        activeProfile?.let { startTunnel(it.id) }
                    }
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }

    private fun unregisterNetworkMonitor() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            networkCallback = null
        }
    }

    private fun startForegroundNotification(statusText: String) {
        val disconnectIntent = Intent(this, OGTunnelVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, OGTunnelApplication.VPN_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("OGTunnel VPN")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_ogtunnel_logo)
            .setContentIntent(openAppPendingIntent)
            .addAction(R.drawable.ic_ogtunnel_logo, "Disconnect", disconnectPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopTunnel(reason: String) {
        OGLogger.i("VpnService", "Stopping VPN tunnel: $reason")
        unregisterNetworkMonitor()

        tunRouter?.stop()
        tunRouter = null

        sshEngine?.stop()
        sshEngine = null

        xrayManager?.stop()
        xrayManager = null

        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null

        VpnController.updateState(ConnectionState.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel("Service destroyed")
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "com.example.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpn.ACTION_DISCONNECT"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val NOTIFICATION_ID = 1001
    }
}
