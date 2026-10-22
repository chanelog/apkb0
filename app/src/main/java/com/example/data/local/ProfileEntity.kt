package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.Profile
import com.example.domain.model.TunnelProtocol

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val protocol: String,
    val isFavorite: Boolean,
    val lastUsedTimestamp: Long,
    val createdAt: Long,

    // SSHWS
    val sshHost: String,
    val sshPort: Int,
    val sshUsername: String,
    val sshPassword: String,
    val sshPrivateKey: String,
    val wsHost: String,
    val wsPort: Int,
    val wsPath: String,
    val customPayload: String,
    val tlsEnabled: Boolean,
    val tlsSni: String,
    val allowInsecureCert: Boolean,
    val upstreamProxyHost: String,
    val upstreamProxyPort: Int,
    val keepaliveIntervalSeconds: Int,

    // Xray
    val serverAddress: String,
    val serverPort: Int,
    val uuid: String,
    val password: String,
    val security: String,
    val transport: String,
    val wsPathXray: String,
    val wsHostHeader: String,
    val grpcServiceName: String,
    val flow: String,
    val encryption: String,
    val sni: String,
    val alpn: String,
    val realityPublicKey: String,
    val realityShortId: String,
    val fingerprint: String
) {
    fun toDomain(): Profile {
        return Profile(
            id = id,
            name = name,
            protocol = try { TunnelProtocol.valueOf(protocol) } catch (e: Exception) { TunnelProtocol.SSH_WS },
            isFavorite = isFavorite,
            lastUsedTimestamp = lastUsedTimestamp,
            createdAt = createdAt,
            sshHost = sshHost,
            sshPort = sshPort,
            sshUsername = sshUsername,
            sshPassword = sshPassword,
            sshPrivateKey = sshPrivateKey,
            wsHost = wsHost,
            wsPort = wsPort,
            wsPath = wsPath,
            customPayload = customPayload,
            tlsEnabled = tlsEnabled,
            tlsSni = tlsSni,
            allowInsecureCert = allowInsecureCert,
            upstreamProxyHost = upstreamProxyHost,
            upstreamProxyPort = upstreamProxyPort,
            keepaliveIntervalSeconds = keepaliveIntervalSeconds,
            serverAddress = serverAddress,
            serverPort = serverPort,
            uuid = uuid,
            password = password,
            security = security,
            transport = transport,
            wsPathXray = wsPathXray,
            wsHostHeader = wsHostHeader,
            grpcServiceName = grpcServiceName,
            flow = flow,
            encryption = encryption,
            sni = sni,
            alpn = alpn,
            realityPublicKey = realityPublicKey,
            realityShortId = realityShortId,
            fingerprint = fingerprint
        )
    }

    companion object {
        fun fromDomain(p: Profile): ProfileEntity {
            return ProfileEntity(
                id = p.id,
                name = p.name,
                protocol = p.protocol.name,
                isFavorite = p.isFavorite,
                lastUsedTimestamp = p.lastUsedTimestamp,
                createdAt = p.createdAt,
                sshHost = p.sshHost,
                sshPort = p.sshPort,
                sshUsername = p.sshUsername,
                sshPassword = p.sshPassword,
                sshPrivateKey = p.sshPrivateKey,
                wsHost = p.wsHost,
                wsPort = p.wsPort,
                wsPath = p.wsPath,
                customPayload = p.customPayload,
                tlsEnabled = p.tlsEnabled,
                tlsSni = p.tlsSni,
                allowInsecureCert = p.allowInsecureCert,
                upstreamProxyHost = p.upstreamProxyHost,
                upstreamProxyPort = p.upstreamProxyPort,
                keepaliveIntervalSeconds = p.keepaliveIntervalSeconds,
                serverAddress = p.serverAddress,
                serverPort = p.serverPort,
                uuid = p.uuid,
                password = p.password,
                security = p.security,
                transport = p.transport,
                wsPathXray = p.wsPathXray,
                wsHostHeader = p.wsHostHeader,
                grpcServiceName = p.grpcServiceName,
                flow = p.flow,
                encryption = p.encryption,
                sni = p.sni,
                alpn = p.alpn,
                realityPublicKey = p.realityPublicKey,
                realityShortId = p.realityShortId,
                fingerprint = p.fingerprint
            )
        }
    }
}
