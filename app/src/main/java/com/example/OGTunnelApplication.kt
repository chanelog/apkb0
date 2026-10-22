package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.data.local.OGTunnelDatabase
import com.example.data.preferences.SettingsManager
import com.example.utils.OGLogger

class OGTunnelApplication : Application() {

    lateinit var database: OGTunnelDatabase
        private set

    lateinit var settingsManager: SettingsManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        OGLogger.init()
        OGLogger.i("Application", "OGTunnel application initialized")

        database = OGTunnelDatabase.getDatabase(this)
        settingsManager = SettingsManager(this)

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val vpnChannel = NotificationChannel(
                VPN_NOTIFICATION_CHANNEL_ID,
                "OGTunnel VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active VPN connection status and traffic stats"
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                ALERT_NOTIFICATION_CHANNEL_ID,
                "OGTunnel Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for connection errors and reconnect attempts"
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(vpnChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    companion object {
        const val VPN_NOTIFICATION_CHANNEL_ID = "ogtunnel_vpn_channel"
        const val ALERT_NOTIFICATION_CHANNEL_ID = "ogtunnel_alert_channel"

        lateinit var instance: OGTunnelApplication
            private set
    }
}
