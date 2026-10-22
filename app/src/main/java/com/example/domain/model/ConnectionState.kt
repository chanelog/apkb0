package com.example.domain.model

enum class ConnectionState(val label: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    RECONNECTING("Reconnecting"),
    DISCONNECTING("Disconnecting"),
    ERROR("Error");

    val isTerminal: Boolean
        get() = this == DISCONNECTED || this == ERROR

    val isActiveOrPending: Boolean
        get() = this == CONNECTED || this == CONNECTING || this == RECONNECTING
}

data class ConnectionStats(
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val uploadSpeedBps: Long = 0L,
    val downloadSpeedBps: Long = 0L,
    val durationSeconds: Long = 0L,
    val pingMs: Long? = null,
    val lastError: String? = null
) {
    fun formattedDuration(): String {
        val hours = durationSeconds / 3600
        val minutes = (durationSeconds % 3600) / 60
        val seconds = durationSeconds % 60
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            val clamped = digitGroups.coerceIn(0, units.size - 1)
            val value = bytes / Math.pow(1024.0, clamped.toDouble())
            return String.format("%.2f %s", value, units[clamped])
        }

        fun formatSpeed(bytesPerSec: Long): String {
            if (bytesPerSec <= 0) return "0 KB/s"
            val kb = bytesPerSec / 1024.0
            return if (kb >= 1024) {
                String.format("%.2f MB/s", kb / 1024.0)
            } else {
                String.format("%.1f KB/s", kb)
            }
        }
    }
}
