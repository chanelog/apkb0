package com.example.data.config

import android.net.Uri
import android.util.Base64
import com.example.domain.model.Profile
import com.example.domain.model.TunnelProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object ConfigExporter {

    fun exportToUri(profile: Profile): String {
        return when (profile.protocol) {
            TunnelProtocol.VLESS -> exportVlessUri(profile)
            TunnelProtocol.VMESS -> exportVmessUri(profile)
            TunnelProtocol.TROJAN -> exportTrojanUri(profile)
            TunnelProtocol.SSH_WS, TunnelProtocol.SSH_WS_NTLS, TunnelProtocol.SSH_WS_TLS, TunnelProtocol.SSH_TCP, TunnelProtocol.SSH_HTTP_UPGRADE, TunnelProtocol.SSH_OVER_TLS -> exportSshUri(profile)
        }
    }

    private fun exportVlessUri(p: Profile): String {
        val encodedName = URLEncoder.encode(p.name, StandardCharsets.UTF_8.name())
        val encodedPath = URLEncoder.encode(p.wsPathXray, StandardCharsets.UTF_8.name())
        val sb = StringBuilder("vless://${p.uuid}@${p.serverAddress}:${p.serverPort}?")
        sb.append("type=${p.transport}")
        sb.append("&security=${p.security}")
        if (p.transport == "ws") {
            sb.append("&path=$encodedPath")
            if (p.wsHostHeader.isNotEmpty()) sb.append("&host=${p.wsHostHeader}")
        }
        if (p.sni.isNotEmpty()) sb.append("&sni=${p.sni}")
        if (p.flow.isNotEmpty()) sb.append("&flow=${p.flow}")
        if (p.encryption.isNotEmpty() && p.encryption != "none") sb.append("&encryption=${p.encryption}")
        if (p.security == "reality") {
            if (p.realityPublicKey.isNotEmpty()) sb.append("&pbk=${p.realityPublicKey}")
            if (p.realityShortId.isNotEmpty()) sb.append("&sid=${p.realityShortId}")
            if (p.fingerprint.isNotEmpty()) sb.append("&fp=${p.fingerprint}")
        }
        sb.append("#$encodedName")
        return sb.toString()
    }

    private fun exportVmessUri(p: Profile): String {
        val json = JSONObject().apply {
            put("v", "2")
            put("ps", p.name)
            put("add", p.serverAddress)
            put("port", p.serverPort)
            put("id", p.uuid)
            put("aid", 0)
            put("scy", "auto")
            put("net", p.transport)
            put("type", "none")
            put("host", p.wsHostHeader)
            put("path", p.wsPathXray)
            put("tls", if (p.security.equals("tls", ignoreCase = true)) "tls" else "")
            put("sni", p.sni)
            put("alpn", p.alpn)
        }
        val encoded = Base64.encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        return "vmess://$encoded"
    }

    private fun exportTrojanUri(p: Profile): String {
        val encodedName = URLEncoder.encode(p.name, StandardCharsets.UTF_8.name())
        val encodedPath = URLEncoder.encode(p.wsPathXray, StandardCharsets.UTF_8.name())
        val sb = StringBuilder("trojan://${p.password}@${p.serverAddress}:${p.serverPort}?")
        sb.append("security=${p.security}")
        sb.append("&type=${p.transport}")
        if (p.sni.isNotEmpty()) sb.append("&sni=${p.sni}")
        if (p.transport == "ws") {
            sb.append("&path=$encodedPath")
            if (p.wsHostHeader.isNotEmpty()) sb.append("&host=${p.wsHostHeader}")
        }
        sb.append("#$encodedName")
        return sb.toString()
    }

    private fun exportSshUri(p: Profile): String {
        val encodedName = URLEncoder.encode(p.name, StandardCharsets.UTF_8.name())
        val userInfo = "${p.sshUsername}:${p.sshPassword}"
        val mode = when (p.protocol) {
            TunnelProtocol.SSH_TCP -> "tcp"
            TunnelProtocol.SSH_HTTP_UPGRADE -> "upgrade"
            else -> "ws"
        }
        val sb = StringBuilder("ssh://$userInfo@${p.sshHost}:${p.sshPort}?")
        sb.append("mode=$mode")
        if (p.wsHost.isNotEmpty()) sb.append("&ws_host=${p.wsHost}")
        if (p.wsPort > 0) sb.append("&ws_port=${p.wsPort}")
        if (p.wsPath.isNotEmpty()) sb.append("&ws_path=${URLEncoder.encode(p.wsPath, StandardCharsets.UTF_8.name())}")
        if (p.tlsEnabled) sb.append("&tls=1")
        if (p.tlsSni.isNotEmpty()) sb.append("&sni=${p.tlsSni}")
        sb.append("#$encodedName")
        return sb.toString()
    }

    fun exportToJson(profiles: List<Profile>, redactCredentials: Boolean = false): String {
        val root = JSONObject()
        root.put("app", "OGTunnel")
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("redacted", redactCredentials)

        val array = JSONArray()
        for (p in profiles) {
            val obj = JSONObject().apply {
                put("name", p.name)
                put("protocol", p.protocol.name)
                put("sshHost", p.sshHost)
                put("sshPort", p.sshPort)
                put("sshUsername", p.sshUsername)
                put("sshPassword", if (redactCredentials) "***REDACTED***" else p.sshPassword)
                put("sshPrivateKey", if (redactCredentials) "***REDACTED***" else p.sshPrivateKey)
                put("wsHost", p.wsHost)
                put("wsPort", p.wsPort)
                put("wsPath", p.wsPath)
                put("customPayload", p.customPayload)
                put("tlsEnabled", p.tlsEnabled)
                put("tlsSni", p.tlsSni)
                put("allowInsecureCert", p.allowInsecureCert)
                put("serverAddress", p.serverAddress)
                put("serverPort", p.serverPort)
                put("uuid", if (redactCredentials) "***REDACTED***" else p.uuid)
                put("password", if (redactCredentials) "***REDACTED***" else p.password)
                put("security", p.security)
                put("transport", p.transport)
                put("wsPathXray", p.wsPathXray)
                put("wsHostHeader", p.wsHostHeader)
                put("sni", p.sni)
                put("realityPublicKey", p.realityPublicKey)
                put("realityShortId", p.realityShortId)
            }
            array.put(obj)
        }
        root.put("profiles", array)
        return root.toString(2)
    }
}
