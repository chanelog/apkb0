package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.xray.XrayConfigGenerator
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.ElectricTeal
import com.example.ui.viewmodel.MainViewModel
import com.example.utils.OGLogger

@Composable
fun ToolsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activeProfile by viewModel.activeProfile.collectAsState()

    // Tool states
    var dnsDomain by remember { mutableStateOf("google.com") }
    var dnsServer by remember { mutableStateOf("1.1.1.1") }
    val dnsResult by viewModel.dnsLookupResult.collectAsState()
    val dnsLoading by viewModel.dnsLookupLoading.collectAsState()

    var pingHost by remember { mutableStateOf(activeProfile?.serverAddress ?: activeProfile?.sshHost ?: "1.1.1.1") }
    var pingPort by remember { mutableStateOf((activeProfile?.serverPort ?: activeProfile?.sshPort ?: 443).toString()) }
    val pingResult by viewModel.tcpPingResult.collectAsState()
    val pingLoading by viewModel.tcpPingLoading.collectAsState()

    val sshAuthResult by viewModel.sshAuthResult.collectAsState()
    val sshAuthLoading by viewModel.sshAuthLoading.collectAsState()

    var xrayValidationResult by remember { mutableStateOf<String?>(null) }

    // Network inspection
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNetwork = cm.activeNetwork
    val caps = cm.getNetworkCapabilities(activeNetwork)
    val networkType = when {
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi Network"
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular Mobile Data"
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN Interface Active"
        else -> "Disconnected / Offline"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Network Tools & Diagnostics",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Real protocol probes, latency metrics, and reachability tests",
            fontSize = 12.sp,
            color = CyanGlow
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Device Network Info Card
        ToolCard(
            title = "CURRENT NETWORK STATE",
            icon = Icons.Default.Wifi
        ) {
            Text("Active Link: $networkType", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Validated: ${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true} • Upstream Bandwidth: ${caps?.linkUpstreamBandwidthKbps ?: 0} Kbps",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 1. TCP Reachability Test
        ToolCard(
            title = "TCP REACHABILITY TEST",
            icon = Icons.Default.NetworkPing
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = pingHost,
                    onValueChange = { pingHost = it },
                    label = { Text("Target Host") },
                    modifier = Modifier.weight(2f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = pingPort,
                    onValueChange = { pingPort = it },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val port = pingPort.toIntOrNull() ?: 443
                    viewModel.performTcpPing(pingHost.trim(), port)
                },
                enabled = !pingLoading && pingHost.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricTeal,
                    contentColor = MaterialTheme.colorScheme.background
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tcp_ping_button")
            ) {
                if (pingLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.background, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Test TCP Connection", fontWeight = FontWeight.Bold)
                }
            }

            if (pingResult != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = pingResult!!,
                    color = if (pingResult!!.startsWith("Reachable")) ElectricTeal else Color(0xFFFF5252),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. DNS Lookup Test
        ToolCard(
            title = "DNS RESOLUTION TEST",
            icon = Icons.Default.Dns
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = dnsDomain,
                    onValueChange = { dnsDomain = it },
                    label = { Text("Domain Name") },
                    modifier = Modifier.weight(2f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = dnsServer,
                    onValueChange = { dnsServer = it },
                    label = { Text("DNS Server") },
                    modifier = Modifier.weight(1.2f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.performDnsLookup(dnsDomain.trim(), dnsServer.trim()) },
                enabled = !dnsLoading && dnsDomain.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = CyanGlow
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dns_lookup_button")
            ) {
                if (dnsLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CyanGlow, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Resolve Domain", fontWeight = FontWeight.Bold)
                }
            }

            if (dnsResult.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Column {
                    Text("Resolved IP Addresses:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    dnsResult.forEach { ip ->
                        Text("• $ip", fontSize = 12.sp, color = ElectricTeal, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3. SSH Authentication Test (without VPN)
        ToolCard(
            title = "SSH AUTHENTICATION TEST",
            icon = Icons.Default.Key
        ) {
            val p = activeProfile
            if (p != null && p.protocol.isSsh) {
                Text(
                    text = "Target: ${p.name} (${p.displayEndpoint})",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.performSshAuthTest(p) },
                    enabled = !sshAuthLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = CyanGlow
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, CyanGlow.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .testTag("ssh_auth_test_button")
                ) {
                    if (sshAuthLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CyanGlow, strokeWidth = 2.dp)
                    } else {
                        Text("Probe SSH Handshake & Auth", fontWeight = FontWeight.Bold)
                    }
                }
                if (sshAuthResult != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = sshAuthResult!!,
                        fontSize = 12.sp,
                        color = if (sshAuthResult!!.startsWith("SUCCESS")) ElectricTeal else Color(0xFFFF5252)
                    )
                }
            } else {
                Text(
                    text = "Select an active SSHWS profile to run an SSH authentication probe.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. Xray Configuration Validator
        ToolCard(
            title = "XRAY CONFIGURATION VALIDATOR",
            icon = Icons.Default.CheckCircle
        ) {
            val p = activeProfile
            if (p != null && p.protocol.isXray) {
                Text("Validates syntax and inbounds/outbounds structure for active Xray profile '${p.name}'.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        try {
                            val config = XrayConfigGenerator.generateConfig(p)
                            xrayValidationResult = "VALID: Generated ${config.toString().length} bytes JSON config with inbounds (socks/http) and outbounds (${p.protocol.displayName})."
                        } catch (e: Exception) {
                            xrayValidationResult = "INVALID: ${e.message}"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = CyanGlow
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Validate Active Xray Config", fontWeight = FontWeight.Bold)
                }
                if (xrayValidationResult != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = xrayValidationResult!!,
                        fontSize = 12.sp,
                        color = if (xrayValidationResult!!.startsWith("VALID")) ElectricTeal else Color(0xFFFF5252)
                    )
                }
            } else {
                Text(
                    text = "Select an active Xray profile (VLESS/VMess/Trojan) to validate its generated JSON.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Diagnostic Report Export
        Button(
            onClick = {
                val report = OGLogger.exportDiagnostics()
                val cmService = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cmService.setPrimaryClip(ClipData.newPlainText("OGTunnel Diagnostic Report", report))
                Toast.makeText(context, "Diagnostic report copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Copy Full Diagnostic Report")
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
private fun ToolCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = title, tint = CyanGlow, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}
