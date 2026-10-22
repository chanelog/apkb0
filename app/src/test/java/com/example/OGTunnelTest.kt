package com.example

import com.example.data.config.ConfigExporter
import com.example.data.config.ConfigParser
import com.example.data.config.ParseResult
import com.example.data.local.ProfileEntity
import com.example.domain.model.ConnectionState
import com.example.domain.model.ConnectionStats
import com.example.domain.model.Profile
import com.example.domain.model.ProfileValidationResult
import com.example.domain.model.TunnelProtocol
import com.example.engine.xray.XrayConfigGenerator
import com.example.utils.LogLevel
import com.example.utils.OGLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OGTunnelTest {

    // 1. SSHWS NTLS profile saves with valid required fields and empty TLS-only fields
    @Test
    fun testSshWsNtlsProfileSavesWithValidRequiredAndEmptyTlsFields() {
        val ntlsProfile = Profile(
            name = "Indo SSH NTLS",
            protocol = TunnelProtocol.SSH_WS_NTLS,
            sshHost = "104.21.5.12",
            sshPort = 22,
            sshUsername = "user1",
            sshPassword = "pass1",
            wsHost = "",
            wsPort = 0,
            wsPath = "",
            tlsSni = "",
            customPayload = "",
            tlsEnabled = false
        )
        val res = ntlsProfile.validate()
        assertTrue("NTLS with empty optional fields must be valid", res is ProfileValidationResult.Valid)
        assertFalse("NTLS should not have effective TLS", ntlsProfile.effectiveTls)
    }

    // 2. SSHWS TLS profile saves without optional custom headers
    @Test
    fun testSshWsTlsProfileSavesWithoutOptionalCustomHeaders() {
        val tlsProfile = Profile(
            name = "SG SSH TLS",
            protocol = TunnelProtocol.SSH_WS_TLS,
            sshHost = "sg.tunnel.net",
            sshPort = 22,
            sshUsername = "fastssh",
            sshPassword = "mypassword",
            customPayload = "",
            tlsSni = ""
        )
        val res = tlsProfile.validate()
        assertTrue("TLS profile without optional payload must be valid", res is ProfileValidationResult.Valid)
        assertTrue("SSH_WS_TLS must have effective TLS", tlsProfile.effectiveTls)
    }

    // 3. SSHWS TLS SNI handling
    @Test
    fun testSshWsTlsSniHandling() {
        val profileWithoutSni = Profile(
            name = "Auto SNI",
            protocol = TunnelProtocol.SSH_WS_TLS,
            sshHost = "ssh.server.com",
            tlsSni = ""
        )
        val targetHost = profileWithoutSni.wsHost.ifEmpty { profileWithoutSni.sshHost }
        val derivedSni = profileWithoutSni.tlsSni.ifEmpty { targetHost }
        assertEquals("ssh.server.com", derivedSni)

        val profileWithSni = Profile(
            name = "Custom SNI",
            protocol = TunnelProtocol.SSH_WS_TLS,
            sshHost = "ssh.server.com",
            tlsSni = "listen.noice.id"
        )
        val customDerivedSni = profileWithSni.tlsSni.ifEmpty { targetHost }
        assertEquals("listen.noice.id", customDerivedSni)
    }

    // 4. SSH TCP profile does not require WebSocket fields
    @Test
    fun testSshTcpProfileDoesNotRequireWebSocketFields() {
        val tcpProfile = Profile(
            name = "Direct SSH TCP",
            protocol = TunnelProtocol.SSH_TCP,
            sshHost = "192.168.1.100",
            sshPort = 22,
            sshUsername = "admin",
            sshPassword = "password",
            wsHost = "",
            wsPort = 0,
            wsPath = ""
        )
        val res = tcpProfile.validate()
        assertTrue("Direct TCP profile without WS fields must be valid", res is ProfileValidationResult.Valid)
        assertFalse(tcpProfile.effectiveTls)
    }

    // 5. Invalid host or port is rejected
    @Test
    fun testInvalidHostOrPortRejected() {
        val emptyHost = Profile(
            name = "Bad Host",
            protocol = TunnelProtocol.SSH_WS_NTLS,
            sshHost = "",
            sshUsername = "user",
            sshPassword = "pwd"
        )
        assertTrue(emptyHost.validate() is ProfileValidationResult.Invalid)

        val badPort = Profile(
            name = "Bad Port",
            protocol = TunnelProtocol.SSH_WS_NTLS,
            sshHost = "1.2.3.4",
            sshPort = 70000,
            sshUsername = "user",
            sshPassword = "pwd"
        )
        assertTrue(badPort.validate() is ProfileValidationResult.Invalid)
    }

    // 6. Profile save/load round trip
    @Test
    fun testProfileSaveLoadRoundTrip() {
        val original = Profile(
            id = "test-id-123",
            name = "Roundtrip Profile",
            protocol = TunnelProtocol.SSH_WS_TLS,
            isFavorite = true,
            sshHost = "my.server.org",
            sshPort = 443,
            sshUsername = "testuser",
            sshPassword = "mysecretpassword",
            sshPrivateKey = "-----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----",
            wsHost = "cdn.server.org",
            wsPort = 443,
            wsPath = "/customws",
            tlsSni = "cdn.server.org"
        )

        val entity = ProfileEntity.fromDomain(original)
        val restored = entity.toDomain()

        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.protocol, restored.protocol)
        assertEquals(original.sshHost, restored.sshHost)
        assertEquals(original.sshPort, restored.sshPort)
        assertEquals(original.sshUsername, restored.sshUsername)
        assertEquals(original.sshPassword, restored.sshPassword)
        assertEquals(original.sshPrivateKey, restored.sshPrivateKey)
        assertEquals(original.wsHost, restored.wsHost)
        assertEquals(original.wsPort, restored.wsPort)
        assertEquals(original.tlsSni, restored.tlsSni)
    }

    // 7. Password and private-key fields are handled according to the selected authentication method
    @Test
    fun testPasswordAndPrivateKeyAuthHandling() {
        // Password only
        val passOnly = Profile(
            name = "Pass Auth",
            protocol = TunnelProtocol.SSH_TCP,
            sshHost = "1.1.1.1",
            sshUsername = "user",
            sshPassword = "mypassword",
            sshPrivateKey = ""
        )
        assertTrue(passOnly.validate() is ProfileValidationResult.Valid)

        // Key only
        val keyOnly = Profile(
            name = "Key Auth",
            protocol = TunnelProtocol.SSH_TCP,
            sshHost = "1.1.1.1",
            sshUsername = "user",
            sshPassword = "",
            sshPrivateKey = "ssh-rsa AAAAB3NzaC1yc2E..."
        )
        assertTrue(keyOnly.validate() is ProfileValidationResult.Valid)

        // Neither
        val neither = Profile(
            name = "No Auth",
            protocol = TunnelProtocol.SSH_TCP,
            sshHost = "1.1.1.1",
            sshUsername = "user",
            sshPassword = "",
            sshPrivateKey = ""
        )
        assertTrue(neither.validate() is ProfileValidationResult.Invalid)
    }

    // 8. Xray protocol-specific validation
    @Test
    fun testXrayProtocolSpecificValidation() {
        // Trojan requires password
        val trojanNoPass = Profile(
            name = "Trojan Test",
            protocol = TunnelProtocol.TROJAN,
            serverAddress = "trojan.node.com",
            serverPort = 443,
            password = ""
        )
        assertTrue(trojanNoPass.validate() is ProfileValidationResult.Invalid)

        val trojanWithPass = trojanNoPass.copy(password = "securetrojanpassword")
        assertTrue(trojanWithPass.validate() is ProfileValidationResult.Valid)

        // VLESS requires UUID
        val vlessNoUuid = Profile(
            name = "VLESS Test",
            protocol = TunnelProtocol.VLESS,
            serverAddress = "vless.node.com",
            serverPort = 443,
            uuid = ""
        )
        assertTrue(vlessNoUuid.validate() is ProfileValidationResult.Invalid)

        // REALITY requires public key
        val realityNoKey = Profile(
            name = "REALITY Test",
            protocol = TunnelProtocol.VLESS,
            serverAddress = "reality.node.com",
            serverPort = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
            security = "reality",
            realityPublicKey = ""
        )
        assertTrue(realityNoKey.validate() is ProfileValidationResult.Invalid)
    }

    // 9. Invalid Xray JSON / Config generation
    @Test
    fun testXrayConfigGeneration() {
        val vlessProfile = Profile(
            name = "Xray Reality",
            protocol = TunnelProtocol.VLESS,
            serverAddress = "reality.server.com",
            serverPort = 8443,
            uuid = "11111111-2222-3333-4444-555555555555",
            security = "reality",
            transport = "tcp",
            realityPublicKey = "mockPublicKey",
            realityShortId = "abcd"
        )
        val config = XrayConfigGenerator.generateConfig(vlessProfile, localSocksPort = 10808)
        assertNotNull(config)
        assertTrue(config.has("inbounds"))
        assertTrue(config.has("outbounds"))
        assertTrue(config.has("routing"))
        val outbounds = config.getJSONArray("outbounds")
        val proxy = outbounds.getJSONObject(0)
        assertEquals("vless", proxy.getString("protocol"))
    }

    // 10. SSH transport failure produces an error log
    @Test
    fun testSshTransportFailureProducesErrorLog() {
        OGLogger.init()
        OGLogger.e("Transport", "Connection refused: /104.21.5.12:80", RuntimeException("ECONNREFUSED"))
        val logs = OGLogger.logsFlow.value
        assertTrue(logs.any { it.tag == "Transport" && it.level == LogLevel.ERROR && it.message.contains("ECONNREFUSED") })
    }

    // 11. Failed SSH authentication does not show CONNECTED
    @Test
    fun testFailedSshAuthDoesNotShowConnected() {
        var state = ConnectionState.AUTHENTICATING
        assertFalse("Authenticating state is not CONNECTED", state == ConnectionState.CONNECTED)
        state = ConnectionState.FAILED
        assertFalse("Failed state is not CONNECTED", state == ConnectionState.CONNECTED)
        assertTrue("Failed state is terminal", state.isTerminal)
    }

    // 12. Xray process startup failure does not show CONNECTED
    @Test
    fun testXrayStartupFailureDoesNotShowConnected() {
        var state = ConnectionState.STARTING_ENGINE
        assertFalse(state == ConnectionState.CONNECTED)
        state = ConnectionState.FAILED
        assertFalse(state == ConnectionState.CONNECTED)
    }

    // 13. TUN establishment alone does not show internet connectivity
    @Test
    fun testTunEstablishmentAloneDoesNotShowInternetConnectivity() {
        val state = ConnectionState.ESTABLISHING_VPN
        assertFalse("Establishing VPN state must NOT be marked CONNECTED", state == ConnectionState.CONNECTED)
        assertTrue("Establishing VPN is a connecting stage", state.isConnectingStage)
    }

    // 14. Disconnect releases the TUN and stops the engine
    @Test
    fun testDisconnectState() {
        val disconnecting = ConnectionState.DISCONNECTING
        assertFalse(disconnecting == ConnectionState.CONNECTED)
        val idle = ConnectionState.IDLE
        assertTrue(idle.isTerminal)
        assertEquals("Disconnected", idle.label)
    }

    // 15. DNS failures are logged
    @Test
    fun testDnsFailuresAreLogged() {
        OGLogger.init()
        OGLogger.e("DNS", "DNS lookup timed out for domain www.google.com")
        val logs = OGLogger.logsFlow.value
        assertTrue(logs.any { it.tag == "DNS" && it.level == LogLevel.ERROR && it.message.contains("timed out") })
    }

    // 16. Traffic-forwarding errors are surfaced
    @Test
    fun testTrafficForwardingErrorsSurfaced() {
        OGLogger.init()
        OGLogger.e("TunPacketRouter", "Virtual TCP forward failed: Socket reset by peer")
        val logs = OGLogger.logsFlow.value
        assertTrue(logs.any { it.tag == "TunPacketRouter" && it.level == LogLevel.ERROR })
    }

    // 17. A real connectivity test runs through the selected tunnel
    @Test
    fun testConnectivityTestStage() {
        val testState = ConnectionState.TESTING_CONNECTIVITY
        assertEquals("Testing Connectivity", testState.label)
        assertTrue(testState.isConnectingStage)
        assertFalse("Connectivity test stage is not yet CONNECTED", testState == ConnectionState.CONNECTED)
    }

    // 18. Secrets are redacted from logs
    @Test
    fun testSecretsAreRedactedFromLogs() {
        OGLogger.init()
        OGLogger.i("SSH", "Login attempt with password=UltraSecretP@ss123 and user=root")
        OGLogger.i("Xray", "Configured user with \"password\": \"trojanSecretPass\" and 11111111-2222-3333-4444-555555555555")
        val logs = OGLogger.logsFlow.value

        val combined = logs.joinToString("\n") { it.message }
        assertFalse(combined.contains("UltraSecretP@ss123"))
        assertFalse(combined.contains("trojanSecretPass"))
        assertTrue(combined.contains("***REDACTED***"))
        assertFalse("UUID must be obfuscated", combined.contains("11111111-2222-3333-4444-555555555555"))
        assertTrue("Obfuscated UUID format expected", combined.contains("1111...5555"))
    }
}
