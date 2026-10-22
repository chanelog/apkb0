package com.example.domain.model

enum class TunnelProtocol(val displayName: String, val category: ProtocolCategory) {
    SSH_WS("SSH WebSocket", ProtocolCategory.SSHWS),
    SSH_TCP("SSH Direct TCP", ProtocolCategory.SSHWS),
    SSH_HTTP_UPGRADE("SSH HTTP Upgrade", ProtocolCategory.SSHWS),
    VLESS("VLESS", ProtocolCategory.XRAY),
    VMESS("VMess", ProtocolCategory.XRAY),
    TROJAN("Trojan", ProtocolCategory.XRAY);

    val isSsh: Boolean get() = category == ProtocolCategory.SSHWS
    val isXray: Boolean get() = category == ProtocolCategory.XRAY
}

enum class ProtocolCategory(val displayName: String) {
    ALL("All Profiles"),
    SSHWS("SSHWS Accounts"),
    XRAY("Xray Accounts"),
    FAVORITES("Favorites")
}
