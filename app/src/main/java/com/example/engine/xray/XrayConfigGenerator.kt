package com.example.engine.xray

import com.example.domain.model.Profile
import com.example.domain.model.TunnelProtocol
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigGenerator {

    /**
     * Generates a complete, compliant Xray-core configuration JSON
     * supporting VLESS, VMess, and Trojan protocols with TCP, WS, gRPC transports
     * and TLS / REALITY security.
     */
    fun generateConfig(
        profile: Profile,
        localSocksPort: Int = 10808,
        localHttpPort: Int = 10809,
        dnsServers: List<String> = listOf("1.1.1.1", "8.8.8.8")
    ): JSONObject {
        val root = JSONObject()

        // 1. Log configuration
        val log = JSONObject().apply {
            put("loglevel", "warning")
        }
        root.put("log", log)

        // 2. DNS configuration
        val dns = JSONObject().apply {
            val serversArray = JSONArray()
            dnsServers.forEach { serversArray.put(it) }
            serversArray.put("localhost")
            put("servers", serversArray)
        }
        root.put("dns", dns)

        // 3. Inbounds: Local SOCKS5 & HTTP inbound for device routing
        val inbounds = JSONArray()

        val socksInbound = JSONObject().apply {
            put("tag", "socks-in")
            put("port", localSocksPort)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().apply {
                    put("http")
                    put("tls")
                })
            })
        }
        inbounds.put(socksInbound)

        val httpInbound = JSONObject().apply {
            put("tag", "http-in")
            put("port", localHttpPort)
            put("listen", "127.0.0.1")
            put("protocol", "http")
            put("settings", JSONObject().apply {
                put("allowTransparent", false)
            })
        }
        inbounds.put(httpInbound)

        root.put("inbounds", inbounds)

        // 4. Outbounds: Main proxy outbound + direct + block
        val outbounds = JSONArray()
        val proxyOutbound = buildProxyOutbound(profile)
        outbounds.put(proxyOutbound)

        val directOutbound = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().apply {
                put("domainStrategy", "UseIP")
            })
        }
        outbounds.put(directOutbound)

        val blockOutbound = JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject())
        }
        outbounds.put(blockOutbound)

        root.put("outbounds", outbounds)

        // 5. Routing
        val routing = JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            val rules = JSONArray()

            // Route local DNS to direct or out
            val dnsRule = JSONObject().apply {
                put("type", "field")
                put("outboundTag", "proxy")
                put("network", "tcp,udp")
            }
            rules.put(dnsRule)

            put("rules", rules)
        }
        root.put("routing", routing)

        return root
    }

    private fun buildProxyOutbound(profile: Profile): JSONObject {
        val outbound = JSONObject()
        outbound.put("tag", "proxy")

        val protocolName = when (profile.protocol) {
            TunnelProtocol.VLESS -> "vless"
            TunnelProtocol.VMESS -> "vmess"
            TunnelProtocol.TROJAN -> "trojan"
            else -> "vless"
        }
        outbound.put("protocol", protocolName)

        val settings = JSONObject()
        when (profile.protocol) {
            TunnelProtocol.VLESS -> {
                val vnext = JSONArray()
                val serverObj = JSONObject().apply {
                    put("address", profile.serverAddress)
                    put("port", profile.serverPort)
                    val users = JSONArray()
                    val user = JSONObject().apply {
                        put("id", profile.uuid)
                        put("encryption", profile.encryption.ifEmpty { "none" })
                        if (profile.flow.isNotBlank()) {
                            put("flow", profile.flow)
                        }
                    }
                    users.put(user)
                    put("users", users)
                }
                vnext.put(serverObj)
                settings.put("vnext", vnext)
            }
            TunnelProtocol.VMESS -> {
                val vnext = JSONArray()
                val serverObj = JSONObject().apply {
                    put("address", profile.serverAddress)
                    put("port", profile.serverPort)
                    val users = JSONArray()
                    val user = JSONObject().apply {
                        put("id", profile.uuid)
                        put("alterId", 0)
                        put("security", if (profile.encryption.isNotBlank()) profile.encryption else "auto")
                    }
                    users.put(user)
                    put("users", users)
                }
                vnext.put(serverObj)
                settings.put("vnext", vnext)
            }
            TunnelProtocol.TROJAN -> {
                val servers = JSONArray()
                val serverObj = JSONObject().apply {
                    put("address", profile.serverAddress)
                    put("port", profile.serverPort)
                    put("password", profile.password)
                }
                servers.put(serverObj)
                settings.put("servers", servers)
            }
            else -> {}
        }
        outbound.put("settings", settings)

        // Stream Settings (network, security, wsSettings, grpcSettings, tlsSettings, realitySettings)
        val streamSettings = JSONObject()
        streamSettings.put("network", profile.transport.lowercase().ifEmpty { "tcp" })

        val sec = profile.security.lowercase().ifEmpty { "none" }
        streamSettings.put("security", sec)

        when (sec) {
            "tls" -> {
                val tlsSettings = JSONObject().apply {
                    val serverName = profile.sni.ifEmpty { profile.serverAddress }
                    if (serverName.isNotBlank()) put("serverName", serverName)
                    put("allowInsecure", false)
                    if (profile.alpn.isNotBlank()) {
                        val alpnArr = JSONArray()
                        profile.alpn.split(",").map { it.trim() }.forEach { alpnArr.put(it) }
                        put("alpn", alpnArr)
                    }
                    if (profile.fingerprint.isNotBlank()) {
                        put("fingerprint", profile.fingerprint)
                    }
                }
                streamSettings.put("tlsSettings", tlsSettings)
            }
            "reality" -> {
                val realitySettings = JSONObject().apply {
                    val serverName = profile.sni.ifEmpty { profile.serverAddress }
                    if (serverName.isNotBlank()) put("serverName", serverName)
                    if (profile.fingerprint.isNotBlank()) put("fingerprint", profile.fingerprint)
                    put("show", false)
                    put("publicKey", profile.realityPublicKey)
                    put("shortId", profile.realityShortId)
                    put("spiderX", "")
                }
                streamSettings.put("realitySettings", realitySettings)
            }
        }

        // Transport details
        when (profile.transport.lowercase()) {
            "ws" -> {
                val wsSettings = JSONObject().apply {
                    put("path", profile.wsPathXray.ifEmpty { "/" })
                    if (profile.wsHostHeader.isNotBlank()) {
                        val headers = JSONObject()
                        headers.put("Host", profile.wsHostHeader)
                        put("headers", headers)
                    }
                }
                streamSettings.put("wsSettings", wsSettings)
            }
            "grpc" -> {
                val grpcSettings = JSONObject().apply {
                    put("serviceName", profile.grpcServiceName)
                    put("multiMode", false)
                }
                streamSettings.put("grpcSettings", grpcSettings)
            }
        }

        outbound.put("streamSettings", streamSettings)
        return outbound
    }
}
