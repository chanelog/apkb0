package com.example

import com.example.data.config.ConfigExporter
import com.example.data.config.ConfigParser
import com.example.data.config.ParseResult
import com.example.domain.model.ConnectionState
import com.example.domain.model.ConnectionStats
import com.example.domain.model.Profile
import com.example.domain.model.ProfileValidationResult
import com.example.domain.model.TunnelProtocol
import com.example.engine.xray.XrayConfigGenerator
import com.example.utils.OGLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OGTunnelTest {

    @Test
    fun testSshProfileValidation() {
        val emptyProfile = Profile(
            name = "",
            protocol = TunnelProtocol.SSH_WS
        )
        val res1 = emptyProfile.validate()
        assertTrue(res1 is ProfileValidationResult.Invalid)

        val invalidPortProfile = Profile(
            name = "Test SSH",
            protocol = TunnelProtocol.SSH_WS,
            sshHost = "1.2.3.4",
            sshPort = 999999,
            sshUsername = "root",
            sshPassword = "password",
            wsHost = "cdn.example.com",
            wsPort = 80
        )
        val res2 = invalidPortProfile.validate()
        assertTrue(res2 is ProfileValidationResult.Invalid)

        val validProfile = Profile(
            name = "Valid SSHWS",
            protocol = TunnelProtocol.SSH_WS,
            sshHost = "1.2.3.4",
            sshPort = 22,
            sshUsername = "vpnuser",
            sshPassword = "secretpassword",
            wsHost = "cdn.example.com",
            wsPort = 443,
            wsPath = "/ssh",
            tlsEnabled = true
        )
        val res3 = validProfile.validate()
        assertTrue(res3 is ProfileValidationResult.Valid)
    }

    @Test
    fun testXrayProfileValidation() {
        val invalidXray = Profile(
            name = "VLESS Test",
            protocol = TunnelProtocol.VLESS,
            serverAddress = "example.com",
            serverPort = 443,
            uuid = "", // missing UUID
            security = "none"
        )
        assertTrue(invalidXray.validate() is ProfileValidationResult.Invalid)

        val validVless = Profile(
            name = "Valid VLESS",
            protocol = TunnelProtocol.VLESS,
            serverAddress = "my.vless.server",
            serverPort = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
            security = "tls",
            transport = "ws"
        )
        assertTrue(validVless.validate() is ProfileValidationResult.Valid)

        val realityWithoutKey = Profile(
            name = "Reality Missing Key",
            protocol = TunnelProtocol.VLESS,
            serverAddress = "my.reality.server",
            serverPort = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
            security = "reality",
            realityPublicKey = ""
        )
        assertTrue(realityWithoutKey.validate() is ProfileValidationResult.Invalid)
    }

    @Test
    fun testConfigParser_VlessUri() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=ws&security=tls&path=%2Fcustomws&sni=sni.example.com#MyVlessNode"
        val result = ConfigParser.parse(uri)
        assertTrue(result is ParseResult.Success)

        val profiles = (result as ParseResult.Success).profiles
        assertEquals(1, profiles.size)
        val p = profiles[0]
        assertEquals("MyVlessNode", p.name)
        assertEquals(TunnelProtocol.VLESS, p.protocol)
        assertEquals("example.com", p.serverAddress)
        assertEquals(443, p.serverPort)
        assertEquals("11111111-2222-3333-4444-555555555555", p.uuid)
        assertEquals("ws", p.transport)
        assertEquals("tls", p.security)
        assertEquals("/customws", p.wsPathXray)
        assertEquals("sni.example.com", p.sni)
    }

    @Test
    fun testConfigParser_TrojanUri() {
        val uri = "trojan://supersecretpass@trojan.example.com:443?security=tls&sni=sni.example.com#MyTrojanNode"
        val result = ConfigParser.parse(uri)
        assertTrue(result is ParseResult.Success)

        val p = (result as ParseResult.Success).profiles[0]
        assertEquals("MyTrojanNode", p.name)
        assertEquals(TunnelProtocol.TROJAN, p.protocol)
        assertEquals("trojan.example.com", p.serverAddress)
        assertEquals("supersecretpass", p.password)
        assertEquals("tls", p.security)
    }

    @Test
    fun testConfigParser_SshUri() {
        val uri = "ssh://myuser:mypass@ssh.example.com:22?ws_host=cdn.example.com&ws_port=80&ws_path=/ssh#MySshWs"
        val result = ConfigParser.parse(uri)
        assertTrue(result is ParseResult.Success)

        val p = (result as ParseResult.Success).profiles[0]
        assertEquals("MySshWs", p.name)
        assertEquals(TunnelProtocol.SSH_WS, p.protocol)
        assertEquals("ssh.example.com", p.sshHost)
        assertEquals("myuser", p.sshUsername)
        assertEquals("mypass", p.sshPassword)
        assertEquals("cdn.example.com", p.wsHost)
        assertEquals(80, p.wsPort)
    }

    @Test
    fun testConfigExporter_CredentialRedaction() {
        val profile = Profile(
            name = "Secret Profile",
            protocol = TunnelProtocol.SSH_WS,
            sshHost = "1.2.3.4",
            sshUsername = "root",
            sshPassword = "SuperSecretPassword123",
            uuid = "11111111-2222-3333-4444-555555555555"
        )

        val jsonRedacted = ConfigExporter.exportToJson(listOf(profile), redactCredentials = true)
        assertFalse(jsonRedacted.contains("SuperSecretPassword123"))
        assertTrue(jsonRedacted.contains("***REDACTED***"))

        val jsonPlain = ConfigExporter.exportToJson(listOf(profile), redactCredentials = false)
        assertTrue(jsonPlain.contains("SuperSecretPassword123"))
    }

    @Test
    fun testXrayConfigGenerator() {
        val profile = Profile(
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

        val config = XrayConfigGenerator.generateConfig(profile, localSocksPort = 10808)
        assertNotNull(config)
        assertTrue(config.has("inbounds"))
        assertTrue(config.has("outbounds"))
        val outbounds = config.getJSONArray("outbounds")
        val proxyOutbound = outbounds.getJSONObject(0)
        assertEquals("vless", proxyOutbound.getString("protocol"))
        val stream = proxyOutbound.getJSONObject("streamSettings")
        assertEquals("reality", stream.getString("security"))
    }

    @Test
    fun testConnectionStatsFormatting() {
        assertEquals("0 B", ConnectionStats.formatBytes(0))
        assertEquals("1.00 KB", ConnectionStats.formatBytes(1024))
        assertEquals("1.00 MB", ConnectionStats.formatBytes(1024 * 1024))

        val stats = ConnectionStats(durationSeconds = 3665)
        assertEquals("01:01:05", stats.formattedDuration())
    }

    @Test
    fun testLoggerRedaction() {
        OGLogger.init()
        OGLogger.i("Test", "Attempting connection with password=mySecretPasswordNow!")
        val logs = OGLogger.logsFlow.value
        assertTrue(logs.isNotEmpty())
        val msg = logs.last().message
        assertFalse(msg.contains("mySecretPasswordNow!"))
        assertTrue(msg.contains("***REDACTED***"))
    }
}
