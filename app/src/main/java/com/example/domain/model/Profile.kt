package com.example.domain.model

import java.util.UUID

sealed class ProfileValidationResult {
    object Valid : ProfileValidationResult()
    data class Invalid(val message: String) : ProfileValidationResult()
}

data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val protocol: TunnelProtocol = TunnelProtocol.SSH_WS,
    val isFavorite: Boolean = false,
    val lastUsedTimestamp: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),

    // --- SSHWS Fields ---
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUsername: String = "",
    val sshPassword: String = "",
    val sshPrivateKey: String = "",
    val wsHost: String = "",
    val wsPort: Int = 80,
    val wsPath: String = "/ssh",
    val customPayload: String = "",
    val tlsEnabled: Boolean = false,
    val tlsSni: String = "",
    val allowInsecureCert: Boolean = false,
    val upstreamProxyHost: String = "",
    val upstreamProxyPort: Int = 0,
    val keepaliveIntervalSeconds: Int = 15,

    // --- Xray Fields ---
    val serverAddress: String = "",
    val serverPort: Int = 443,
    val uuid: String = "",
    val password: String = "", // For Trojan
    val security: String = "tls", // "none", "tls", "reality"
    val transport: String = "ws", // "tcp", "ws", "grpc"
    val wsPathXray: String = "/",
    val wsHostHeader: String = "",
    val grpcServiceName: String = "",
    val flow: String = "",
    val encryption: String = "none",
    val sni: String = "",
    val alpn: String = "h2,http/1.1",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val fingerprint: String = "chrome"
) {
    val displayEndpoint: String
        get() = if (protocol.isSsh) {
            if (wsHost.isNotBlank()) "$wsHost:$wsPort" else "$sshHost:$sshPort"
        } else {
            "$serverAddress:$serverPort"
        }

    fun validate(): ProfileValidationResult {
        if (name.isBlank()) {
            return ProfileValidationResult.Invalid("Profile name cannot be empty")
        }

        if (protocol.isSsh) {
            if (sshHost.isBlank()) {
                return ProfileValidationResult.Invalid("SSH Host is required")
            }
            if (sshPort !in 1..65535) {
                return ProfileValidationResult.Invalid("SSH Port must be between 1 and 65535")
            }
            if (sshUsername.isBlank()) {
                return ProfileValidationResult.Invalid("SSH Username is required")
            }
            if (sshPassword.isBlank() && sshPrivateKey.isBlank()) {
                return ProfileValidationResult.Invalid("SSH Password or Private Key is required")
            }
            if (protocol == TunnelProtocol.SSH_WS) {
                if (wsHost.isBlank()) {
                    return ProfileValidationResult.Invalid("WebSocket Host / CDN domain is required")
                }
                if (wsPort !in 1..65535) {
                    return ProfileValidationResult.Invalid("WebSocket Port must be between 1 and 65535")
                }
            }
        } else if (protocol.isXray) {
            if (serverAddress.isBlank()) {
                return ProfileValidationResult.Invalid("Server address is required")
            }
            if (serverPort !in 1..65535) {
                return ProfileValidationResult.Invalid("Server port must be between 1 and 65535")
            }
            if (protocol == TunnelProtocol.TROJAN) {
                if (password.isBlank()) {
                    return ProfileValidationResult.Invalid("Trojan password is required")
                }
            } else {
                if (uuid.isBlank()) {
                    return ProfileValidationResult.Invalid("UUID is required")
                }
            }
            if (security.equals("reality", ignoreCase = true)) {
                if (realityPublicKey.isBlank()) {
                    return ProfileValidationResult.Invalid("REALITY public key is required")
                }
            }
        }

        return ProfileValidationResult.Valid
    }
}
