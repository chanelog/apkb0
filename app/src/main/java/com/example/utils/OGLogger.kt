package com.example.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel {
    DEBUG, INFO, SUCCESS, WARN, ERROR;

    companion object {
        val WARNING = WARN
    }
}

data class LogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val eventCode: String? = null,
    val details: String? = null
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

    val formattedDate: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

object OGLogger {
    private const val MAX_LOGS = 2000
    private val logList = CopyOnWriteArrayList<LogEntry>()
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    // Patterns for redacting sensitive information
    private val PASSWORD_PARAM_PATTERN = Regex("""(?i)(password|pass|pwd|secret)=([^&;\s]+)""")
    private val PASSWORD_JSON_PATTERN = Regex("""(?i)"(password|secret|pass|key)":\s*"[^"]+"""")
    private val UUID_PATTERN = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
    private val PRIVATE_KEY_PATTERN = Regex("""(?s)-----BEGIN [A-Z ]+PRIVATE KEY-----.*?-----END [A-Z ]+PRIVATE KEY-----""")
    private val BEARER_TOKEN_PATTERN = Regex("""(?i)Bearer\s+[A-Za-z0-9\-._~+/]+=*""")

    fun init() {
        logList.clear()
        _logsFlow.value = emptyList()
    }

    fun redact(message: String): String {
        var sanitized = message
        sanitized = PASSWORD_PARAM_PATTERN.replace(sanitized) { matchResult ->
            "${matchResult.groupValues[1]}=***REDACTED***"
        }
        sanitized = PASSWORD_JSON_PATTERN.replace(sanitized) { matchResult ->
            "\"${matchResult.groupValues[1]}\": \"***REDACTED***\""
        }
        sanitized = PRIVATE_KEY_PATTERN.replace(sanitized, "***REDACTED_PRIVATE_KEY***")
        sanitized = BEARER_TOKEN_PATTERN.replace(sanitized, "Bearer ***REDACTED_TOKEN***")
        // Obfuscate UUIDs partially (show first 4 and last 4)
        sanitized = UUID_PATTERN.replace(sanitized) { matchResult ->
            val u = matchResult.value
            "${u.take(4)}...${u.takeLast(4)}"
        }
        return sanitized
    }

    private fun addEntry(
        level: LogLevel,
        tag: String,
        message: String,
        eventCode: String? = null,
        details: String? = null
    ) {
        val sanitizedMsg = redact(message)
        val sanitizedDetails = details?.let { redact(it) }
        val entry = LogEntry(
            level = level,
            tag = tag,
            message = sanitizedMsg,
            eventCode = eventCode,
            details = sanitizedDetails
        )

        while (logList.size >= MAX_LOGS) {
            logList.removeAt(0)
        }
        logList.add(entry)
        _logsFlow.value = logList.toList()

        when (level) {
            LogLevel.DEBUG -> Log.d("OGTunnel_$tag", sanitizedMsg)
            LogLevel.INFO, LogLevel.SUCCESS -> Log.i("OGTunnel_$tag", sanitizedMsg)
            LogLevel.WARN -> Log.w("OGTunnel_$tag", sanitizedMsg)
            LogLevel.ERROR -> Log.e("OGTunnel_$tag", sanitizedMsg)
        }
    }

    fun d(tag: String, message: String) = addEntry(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String, eventCode: String? = null) = addEntry(LogLevel.INFO, tag, message, eventCode)
    fun success(tag: String, message: String, eventCode: String? = null) = addEntry(LogLevel.SUCCESS, tag, message, eventCode)
    fun w(tag: String, message: String, eventCode: String? = null) = addEntry(LogLevel.WARN, tag, message, eventCode)
    fun e(tag: String, message: String, throwable: Throwable? = null, eventCode: String? = null) {
        val fullMsg = if (throwable != null) {
            "$message: ${throwable.message}\n${Log.getStackTraceString(throwable)}"
        } else {
            message
        }
        addEntry(LogLevel.ERROR, tag, fullMsg, eventCode)
    }

    fun clear() {
        logList.clear()
        _logsFlow.value = emptyList()
    }

    fun exportDiagnostics(): String {
        val sb = StringBuilder()
        sb.append("=== OGTunnel Diagnostic Report ===\n")
        sb.append("Generated at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
        sb.append("App: OGTunnel Android (SSHWS & Xray VPN Client)\n")
        sb.append("OS: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
        sb.append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.SUPPORTED_ABIS.firstOrNull()})\n")
        sb.append("Total Log Entries: ${logList.size}\n\n")
        sb.append("--- BEGIN LOGS ---\n")
        for (entry in logList) {
            val codeStr = if (entry.eventCode != null) " [${entry.eventCode}]" else ""
            sb.append("[${entry.formattedTime}] [${entry.level}] [${entry.tag}]$codeStr: ${entry.message}\n")
            if (entry.details != null) {
                sb.append("    Details: ${entry.details}\n")
            }
        }
        sb.append("--- END LOGS ---\n")
        return sb.toString()
    }
}
