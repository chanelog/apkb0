package com.example.data.config

import android.net.Uri
import android.util.Base64
import com.example.domain.model.Profile
import com.example.domain.model.TunnelProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

sealed class ParseResult {
    data class Success(val profiles: List<Profile>) : ParseResult()
    data class Error(val message: String) : ParseResult()
}

object ConfigParser {

    fun parse(input: String): ParseResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return ParseResult.Error("Input configuration is empty")
        }

        return try {
            when {
                trimmed.startsWith("vless://", ignoreCase = true) -> {
                    val profile = parseVless(trimmed)
                    ParseResult.Success(listOf(profile))
                }
                trimmed.startsWith("vmess://", ignoreCase = true) -> {
                    val profile = parseVmess(trimmed)
                    ParseResult.Success(listOf(profile))
                }
                trimmed.startsWith("trojan://", ignoreCase = true) -> {
                    val profile = parseTrojan(trimmed)
                    ParseResult.Success(listOf(profile))
                }
                trimmed.startsWith("ssh://", ignoreCase = true) -> {
                    val profile = parseSshUri(trimmed)
                    ParseResult.Success(listOf(profile))
                }
                trimmed.startsWith("{") || trimmed.startsWith("[") -> {
                    parseJsonConfig(trimmed)
                }
                else -> {
                    // Try parsing multiple lines or base64 decoded input
                    parseMultiLineOrBase64(trimmed)
                }
            }
        } catch (e: Exception) {
            ParseResult.Error("Failed to parse configuration: ${e.message}")
        }
    }

    private fun parseVless(rawUri: String): Profile {
        val uri = Uri.parse(rawUri)
        val userInfo = uri.userInfo ?: throw IllegalArgumentException("Missing UUID in VLESS URI")
        val host = uri.host ?: throw IllegalArgumentException("Missing host in VLESS URI")
        val port = if (uri.port != -1) uri.port else 443
        val fragment = uri.fragment?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) } ?: "VLESS - $host"

        val type = uri.getQueryParameter("type") ?: "tcp"
        val security = uri.getQueryParameter("security") ?: "none"
        val path = uri.getQueryParameter("path")?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) } ?: "/"
        val hostHeader = uri.getQueryParameter("host") ?: ""
        val sni = uri.getQueryParameter("sni") ?: hostHeader
        val flow = uri.getQueryParameter("flow") ?: ""
        val encryption = uri.getQueryParameter("encryption") ?: "none"
        val serviceName = uri.getQueryParameter("serviceName") ?: ""
        val pbk = uri.getQueryParameter("pbk") ?: "" // reality public key
        val sid = uri.getQueryParameter("sid") ?: "" // reality short id
        val fp = uri.getQueryParameter("fp") ?: "chrome"

        return Profile(
            name = fragment,
            protocol = TunnelProtocol.VLESS,
            serverAddress = host,
            serverPort = port,
            uuid = userInfo,
            security = security,
            transport = type,
            wsPathXray = path,
            wsHostHeader = hostHeader,
            grpcServiceName = serviceName,
            flow = flow,
            encryption = encryption,
            sni = sni,
            realityPublicKey = pbk,
            realityShortId = sid,
            fingerprint = fp
        )
    }

    private fun parseVmess(rawUri: String): Profile {
        val base64Data = rawUri.substring(8).trim()
        val decodedJson = String(Base64.decode(base64Data, Base64.DEFAULT or Base64.URL_SAFE), StandardCharsets.UTF_8)
        val json = JSONObject(decodedJson)

        val name = json.optString("ps", "VMess Profile")
        val host = json.optString("add", "")
        val port = json.optInt("port", 443)
        val uuid = json.optString("id", "")
        val net = json.optString("net", "ws")
        val path = json.optString("path", "/")
        val hostHeader = json.optString("host", "")
        val tls = json.optString("tls", "")
        val sni = json.optString("sni", hostHeader)

        if (host.isEmpty() || uuid.isEmpty()) {
            throw IllegalArgumentException("Invalid VMess JSON: missing host or id")
        }

        return Profile(
            name = name,
            protocol = TunnelProtocol.VMESS,
            serverAddress = host,
            serverPort = port,
            uuid = uuid,
            security = if (tls.equals("tls", ignoreCase = true)) "tls" else "none",
            transport = net,
            wsPathXray = path,
            wsHostHeader = hostHeader,
            sni = sni
        )
    }

    private fun parseTrojan(rawUri: String): Profile {
        val uri = Uri.parse(rawUri)
        val password = uri.userInfo ?: throw IllegalArgumentException("Missing password in Trojan URI")
        val host = uri.host ?: throw IllegalArgumentException("Missing host in Trojan URI")
        val port = if (uri.port != -1) uri.port else 443
        val fragment = uri.fragment?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) } ?: "Trojan - $host"

        val type = uri.getQueryParameter("type") ?: "tcp"
        val security = uri.getQueryParameter("security") ?: "tls"
        val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer") ?: host
        val path = uri.getQueryParameter("path")?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) } ?: "/"
        val hostHeader = uri.getQueryParameter("host") ?: ""

        return Profile(
            name = fragment,
            protocol = TunnelProtocol.TROJAN,
            serverAddress = host,
            serverPort = port,
            password = password,
            security = security,
            transport = type,
            wsPathXray = path,
            wsHostHeader = hostHeader,
            sni = sni
        )
    }

    private fun parseSshUri(rawUri: String): Profile {
        val uri = Uri.parse(rawUri)
        val host = uri.host ?: throw IllegalArgumentException("Missing host in SSH URI")
        val port = if (uri.port != -1) uri.port else 22
        val userInfo = uri.userInfo ?: ""
        val parts = userInfo.split(":")
        val username = parts.getOrNull(0) ?: ""
        val password = parts.getOrNull(1) ?: ""
        val fragment = uri.fragment?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) } ?: "SSH - $host"

        val wsHost = uri.getQueryParameter("ws_host") ?: ""
        val wsPort = uri.getQueryParameter("ws_port")?.toIntOrNull() ?: 80
        val wsPath = uri.getQueryParameter("ws_path") ?: "/ssh"
        val tls = uri.getQueryParameter("tls") == "1" || uri.getQueryParameter("tls").equals("true", ignoreCase = true)
        val sni = uri.getQueryParameter("sni") ?: ""
        val mode = uri.getQueryParameter("mode") ?: "ws"

        val protocol = when (mode.lowercase()) {
            "tcp" -> TunnelProtocol.SSH_TCP
            "upgrade" -> TunnelProtocol.SSH_HTTP_UPGRADE
            else -> TunnelProtocol.SSH_WS
        }

        return Profile(
            name = fragment,
            protocol = protocol,
            sshHost = host,
            sshPort = port,
            sshUsername = username,
            sshPassword = password,
            wsHost = wsHost.ifEmpty { host },
            wsPort = wsPort,
            wsPath = wsPath,
            tlsEnabled = tls,
            tlsSni = sni
        )
    }

    private fun parseJsonConfig(jsonStr: String): ParseResult {
        return try {
            if (jsonStr.trim().startsWith("[")) {
                // Array of profiles
                val jsonArray = JSONArray(jsonStr)
                val profiles = mutableListOf<Profile>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    profiles.add(parseProfileJsonObject(obj))
                }
                ParseResult.Success(profiles)
            } else {
                val jsonObject = JSONObject(jsonStr)
                if (jsonObject.has("profiles")) {
                    // Backup export wrapper
                    val arr = jsonObject.getJSONArray("profiles")
                    val profiles = mutableListOf<Profile>()
                    for (i in 0 until arr.length()) {
                        profiles.add(parseProfileJsonObject(arr.getJSONObject(i)))
                    }
                    ParseResult.Success(profiles)
                } else if (jsonObject.has("outbounds")) {
                    // Raw Xray config file
                    val profile = parseXrayJsonConfig(jsonObject)
                    ParseResult.Success(listOf(profile))
                } else {
                    val profile = parseProfileJsonObject(jsonObject)
                    ParseResult.Success(listOf(profile))
                }
            }
        } catch (e: Exception) {
            ParseResult.Error("JSON parsing failed: ${e.message}")
        }
    }

    private fun parseProfileJsonObject(obj: JSONObject): Profile {
        val proto = try {
            TunnelProtocol.valueOf(obj.optString("protocol", "SSH_WS"))
        } catch (e: Exception) {
            TunnelProtocol.SSH_WS
        }

        return Profile(
            name = obj.optString("name", "Imported Profile"),
            protocol = proto,
            sshHost = obj.optString("sshHost", ""),
            sshPort = obj.optInt("sshPort", 22),
            sshUsername = obj.optString("sshUsername", ""),
            sshPassword = obj.optString("sshPassword", ""),
            sshPrivateKey = obj.optString("sshPrivateKey", ""),
            wsHost = obj.optString("wsHost", ""),
            wsPort = obj.optInt("wsPort", 80),
            wsPath = obj.optString("wsPath", "/ssh"),
            customPayload = obj.optString("customPayload", ""),
            tlsEnabled = obj.optBoolean("tlsEnabled", false),
            tlsSni = obj.optString("tlsSni", ""),
            allowInsecureCert = obj.optBoolean("allowInsecureCert", false),
            serverAddress = obj.optString("serverAddress", ""),
            serverPort = obj.optInt("serverPort", 443),
            uuid = obj.optString("uuid", ""),
            password = obj.optString("password", ""),
            security = obj.optString("security", "tls"),
            transport = obj.optString("transport", "ws"),
            wsPathXray = obj.optString("wsPathXray", "/"),
            wsHostHeader = obj.optString("wsHostHeader", ""),
            sni = obj.optString("sni", ""),
            realityPublicKey = obj.optString("realityPublicKey", ""),
            realityShortId = obj.optString("realityShortId", "")
        )
    }

    private fun parseXrayJsonConfig(root: JSONObject): Profile {
        val outbounds = root.optJSONArray("outbounds") ?: throw IllegalArgumentException("No outbounds in Xray JSON")
        val mainOutbound = outbounds.getJSONObject(0)
        val protocolStr = mainOutbound.optString("protocol", "").lowercase()
        val settings = mainOutbound.optJSONObject("settings") ?: JSONObject()
        val streamSettings = mainOutbound.optJSONObject("streamSettings") ?: JSONObject()

        val network = streamSettings.optString("network", "tcp")
        val security = streamSettings.optString("security", "none")
        val tlsSettings = streamSettings.optJSONObject("tlsSettings")
        val realitySettings = streamSettings.optJSONObject("realitySettings")
        val wsSettings = streamSettings.optJSONObject("wsSettings")

        val sni = tlsSettings?.optString("serverName")
            ?: realitySettings?.optString("serverName")
            ?: ""
        val wsPath = wsSettings?.optString("path", "/") ?: "/"
        val hostHeader = wsSettings?.optJSONObject("headers")?.optString("Host", "") ?: ""

        val proto = when (protocolStr) {
            "vless" -> TunnelProtocol.VLESS
            "vmess" -> TunnelProtocol.VMESS
            "trojan" -> TunnelProtocol.TROJAN
            else -> TunnelProtocol.VLESS
        }

        var server = ""
        var port = 443
        var uuid = ""
        var password = ""

        if (proto == TunnelProtocol.VLESS || proto == TunnelProtocol.VMESS) {
            val vnext = settings.optJSONArray("vnext")
            if (vnext != null && vnext.length() > 0) {
                val srv = vnext.getJSONObject(0)
                server = srv.optString("address", "")
                port = srv.optInt("port", 443)
                val users = srv.optJSONArray("users")
                if (users != null && users.length() > 0) {
                    uuid = users.getJSONObject(0).optString("id", "")
                }
            }
        } else if (proto == TunnelProtocol.TROJAN) {
            val servers = settings.optJSONArray("servers")
            if (servers != null && servers.length() > 0) {
                val srv = servers.getJSONObject(0)
                server = srv.optString("address", "")
                port = srv.optInt("port", 443)
                password = srv.optString("password", "")
            }
        }

        return Profile(
            name = "Xray - $server:$port",
            protocol = proto,
            serverAddress = server,
            serverPort = port,
            uuid = uuid,
            password = password,
            security = security,
            transport = network,
            wsPathXray = wsPath,
            wsHostHeader = hostHeader,
            sni = sni,
            realityPublicKey = realitySettings?.optString("publicKey", "") ?: "",
            realityShortId = realitySettings?.optString("shortId", "") ?: ""
        )
    }

    private fun parseMultiLineOrBase64(input: String): ParseResult {
        val lines = input.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val collected = mutableListOf<Profile>()
        for (line in lines) {
            val res = parse(line)
            if (res is ParseResult.Success) {
                collected.addAll(res.profiles)
            }
        }
        return if (collected.isNotEmpty()) {
            ParseResult.Success(collected)
        } else {
            ParseResult.Error("Unrecognized configuration format. Please provide a valid vless://, vmess://, trojan://, ssh:// or JSON format.")
        }
    }
}
