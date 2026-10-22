package com.example.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.domain.model.Profile
import com.example.domain.model.ProfileValidationResult
import com.example.domain.model.TunnelProtocol
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.ElectricTeal

@Composable
fun ProfileEditorDialog(
    initialProfile: Profile?,
    initialProtocol: TunnelProtocol = TunnelProtocol.SSH_WS_TLS,
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit
) {
    val isEdit = initialProfile != null
    var selectedProtocol by remember {
        mutableStateOf(
            initialProfile?.protocol ?: when (initialProtocol) {
                TunnelProtocol.SSH_WS -> if (initialProfile?.tlsEnabled == true) TunnelProtocol.SSH_WS_TLS else TunnelProtocol.SSH_WS_NTLS
                else -> initialProtocol
            }
        )
    }
    var name by remember { mutableStateOf(initialProfile?.name ?: "") }

    // SSHWS fields
    var sshHost by remember { mutableStateOf(initialProfile?.sshHost ?: "") }
    var sshPort by remember { mutableStateOf((initialProfile?.sshPort ?: 22).toString()) }
    var sshUser by remember { mutableStateOf(initialProfile?.sshUsername ?: "") }
    var sshPass by remember { mutableStateOf(initialProfile?.sshPassword ?: "") }
    var sshPrivateKey by remember { mutableStateOf(initialProfile?.sshPrivateKey ?: "") }
    var wsHost by remember { mutableStateOf(initialProfile?.wsHost ?: "") }
    var wsPort by remember {
        mutableStateOf(
            if (initialProfile != null && initialProfile.wsPort > 0) initialProfile.wsPort.toString()
            else if (selectedProtocol == TunnelProtocol.SSH_WS_TLS) "443" else "80"
        )
    }
    var wsPath by remember { mutableStateOf(initialProfile?.wsPath ?: "/ssh") }
    var customPayload by remember { mutableStateOf(initialProfile?.customPayload ?: "") }
    var tlsSni by remember { mutableStateOf(initialProfile?.tlsSni ?: "") }
    var allowInsecureCert by remember { mutableStateOf(initialProfile?.allowInsecureCert ?: false) }

    // Xray fields
    var serverAddress by remember { mutableStateOf(initialProfile?.serverAddress ?: "") }
    var serverPort by remember { mutableStateOf((initialProfile?.serverPort ?: 443).toString()) }
    var uuid by remember { mutableStateOf(initialProfile?.uuid ?: "") }
    var password by remember { mutableStateOf(initialProfile?.password ?: "") }
    var security by remember { mutableStateOf(initialProfile?.security ?: "tls") }
    var transport by remember { mutableStateOf(initialProfile?.transport ?: "ws") }
    var wsPathXray by remember { mutableStateOf(initialProfile?.wsPathXray ?: "/") }
    var wsHostHeader by remember { mutableStateOf(initialProfile?.wsHostHeader ?: "") }
    var grpcService by remember { mutableStateOf(initialProfile?.grpcServiceName ?: "") }
    var sni by remember { mutableStateOf(initialProfile?.sni ?: "") }
    var realityPublicKey by remember { mutableStateOf(initialProfile?.realityPublicKey ?: "") }
    var realityShortId by remember { mutableStateOf(initialProfile?.realityShortId ?: "") }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var activeTab by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isEdit) "Edit Profile" else "Create Profile",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Profile Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name") },
                    placeholder = { Text("e.g. My Fast Tunnel") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Explicit Connection Mode Selector
                Text("Connection Mode", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    listOf(
                        TunnelProtocol.SSH_WS_TLS,
                        TunnelProtocol.SSH_WS_NTLS,
                        TunnelProtocol.SSH_TCP,
                        TunnelProtocol.SSH_HTTP_UPGRADE,
                        TunnelProtocol.SSH_OVER_TLS,
                        TunnelProtocol.VLESS,
                        TunnelProtocol.VMESS,
                        TunnelProtocol.TROJAN
                    ).forEach { proto ->
                        FilterChip(
                            selected = selectedProtocol == proto,
                            onClick = {
                                selectedProtocol = proto
                                if (proto == TunnelProtocol.SSH_WS_TLS && wsPort == "80") wsPort = "443"
                                if (proto == TunnelProtocol.SSH_WS_NTLS && wsPort == "443") wsPort = "80"
                            },
                            label = { Text(proto.displayName, fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Tab navigation for configurations
                TabRow(
                    selectedTabIndex = activeTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        text = { Text("Server & Auth", fontSize = 12.sp) }
                    )
                    Tab(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        text = { Text("Transport", fontSize = 12.sp) }
                    )
                    Tab(
                        selected = activeTab == 2,
                        onClick = { activeTab = 2 },
                        text = { Text("TLS / Security", fontSize = 12.sp) }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (selectedProtocol.isSsh) {
                    // --- SSH CONFIGURATION TABS ---
                    when (activeTab) {
                        0 -> {
                            OutlinedTextField(
                                value = sshHost,
                                onValueChange = { sshHost = it },
                                label = { Text("SSH Host / Server IP (Required)") },
                                placeholder = { Text("e.g. 104.21.5.12 or myhost.com") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = sshPort,
                                onValueChange = { sshPort = it },
                                label = { Text("SSH Port") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = sshUser,
                                onValueChange = { sshUser = it },
                                label = { Text("SSH Username (Required)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = sshPass,
                                onValueChange = { sshPass = it },
                                label = { Text("SSH Password") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = sshPrivateKey,
                                onValueChange = { sshPrivateKey = it },
                                label = { Text("Private Key (Optional alternative to password)") },
                                placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----...") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2
                            )
                        }
                        1 -> {
                            if (selectedProtocol == TunnelProtocol.SSH_TCP || selectedProtocol == TunnelProtocol.SSH_OVER_TLS) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Info, contentDescription = null, tint = CyanGlow)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Direct SSH mode connects straight to SSH port without WebSocket CDN encapsulation.",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                OutlinedTextField(
                                    value = wsHost,
                                    onValueChange = { wsHost = it },
                                    label = { Text("WebSocket Host / Bug SNI (Optional)") },
                                    placeholder = { Text("Defaults to SSH host if empty (e.g. listen.noice.id)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = wsPort,
                                    onValueChange = { wsPort = it },
                                    label = { Text("WebSocket Port (Optional, e.g. 80 or 443)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = wsPath,
                                    onValueChange = { wsPath = it },
                                    label = { Text("WebSocket Path (Optional, defaults to /ssh)") },
                                    placeholder = { Text("/ssh") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = customPayload,
                                    onValueChange = { customPayload = it },
                                    label = { Text("Custom HTTP Payload (Optional)") },
                                    placeholder = { Text("GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2
                                )
                            }
                        }
                        2 -> {
                            if (selectedProtocol == TunnelProtocol.SSH_WS_NTLS || selectedProtocol == TunnelProtocol.SSH_TCP) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Info, contentDescription = null, tint = CyanGlow)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "TLS is disabled for this profile (${selectedProtocol.displayName}). Handshake runs in plain text / NTLS mode.",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                OutlinedTextField(
                                    value = tlsSni,
                                    onValueChange = { tlsSni = it },
                                    label = { Text("TLS SNI / Server Name (Optional)") },
                                    placeholder = { Text("Defaults to target host if empty") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Allow Insecure Certificates", fontSize = 13.sp, color = Color(0xFFFF5252))
                                        Text(
                                            "Disable certificate verification (for self-signed servers)",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(checked = allowInsecureCert, onCheckedChange = { allowInsecureCert = it })
                                }
                            }
                        }
                    }
                } else {
                    // --- XRAY CONFIGURATION TABS ---
                    when (activeTab) {
                        0 -> {
                            OutlinedTextField(
                                value = serverAddress,
                                onValueChange = { serverAddress = it },
                                label = { Text("Server Host or IP (Required)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = serverPort,
                                onValueChange = { serverPort = it },
                                label = { Text("Server Port") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            if (selectedProtocol == TunnelProtocol.TROJAN) {
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text("Trojan Password (Required)") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            } else {
                                OutlinedTextField(
                                    value = uuid,
                                    onValueChange = { uuid = it },
                                    label = { Text("User UUID (Required)") },
                                    placeholder = { Text("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        }
                        1 -> {
                            Text("Transport Protocol", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("ws", "tcp", "grpc").forEach { t ->
                                    FilterChip(
                                        selected = transport == t,
                                        onClick = { transport = t },
                                        label = { Text(t.uppercase()) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            if (transport == "ws") {
                                OutlinedTextField(
                                    value = wsPathXray,
                                    onValueChange = { wsPathXray = it },
                                    label = { Text("WebSocket Path (Optional, defaults to /)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = wsHostHeader,
                                    onValueChange = { wsHostHeader = it },
                                    label = { Text("WebSocket Host Header (Optional)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            } else if (transport == "grpc") {
                                OutlinedTextField(
                                    value = grpcService,
                                    onValueChange = { grpcService = it },
                                    label = { Text("gRPC Service Name (Optional)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        }
                        2 -> {
                            Text("Security Protocol", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("tls", "reality", "none").forEach { s ->
                                    FilterChip(
                                        selected = security == s,
                                        onClick = { security = s },
                                        label = { Text(s.uppercase()) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = sni,
                                onValueChange = { sni = it },
                                label = { Text("SNI / Server Name (Optional)") },
                                placeholder = { Text("Defaults to server address if empty") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            if (security == "reality") {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = realityPublicKey,
                                    onValueChange = { realityPublicKey = it },
                                    label = { Text("REALITY Public Key (Required for REALITY)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = realityShortId,
                                    onValueChange = { realityShortId = it },
                                    label = { Text("REALITY Short ID (Optional)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        }
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF5252))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(errorMessage!!, color = Color(0xFFFF5252), fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            val candidate = Profile(
                                id = initialProfile?.id ?: java.util.UUID.randomUUID().toString(),
                                name = name.trim().ifEmpty { "${selectedProtocol.displayName} Profile" },
                                protocol = selectedProtocol,
                                isFavorite = initialProfile?.isFavorite ?: false,
                                sshHost = sshHost.trim(),
                                sshPort = sshPort.toIntOrNull() ?: 22,
                                sshUsername = sshUser.trim(),
                                sshPassword = sshPass,
                                sshPrivateKey = sshPrivateKey.trim(),
                                wsHost = wsHost.trim(),
                                wsPort = wsPort.toIntOrNull() ?: if (selectedProtocol == TunnelProtocol.SSH_WS_TLS) 443 else 80,
                                wsPath = wsPath.trim().ifEmpty { "/ssh" },
                                customPayload = customPayload.trim(),
                                tlsEnabled = selectedProtocol == TunnelProtocol.SSH_WS_TLS || selectedProtocol == TunnelProtocol.SSH_OVER_TLS,
                                tlsSni = tlsSni.trim(),
                                allowInsecureCert = allowInsecureCert,
                                serverAddress = serverAddress.trim(),
                                serverPort = serverPort.toIntOrNull() ?: 443,
                                uuid = uuid.trim(),
                                password = password.trim(),
                                security = security,
                                transport = transport,
                                wsPathXray = wsPathXray.trim().ifEmpty { "/" },
                                wsHostHeader = wsHostHeader.trim(),
                                grpcServiceName = grpcService.trim(),
                                sni = sni.trim(),
                                realityPublicKey = realityPublicKey.trim(),
                                realityShortId = realityShortId.trim()
                            )

                            when (val result = candidate.validate()) {
                                is ProfileValidationResult.Valid -> {
                                    onSave(candidate)
                                    onDismiss()
                                }
                                is ProfileValidationResult.Invalid -> {
                                    errorMessage = result.message
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricTeal,
                            contentColor = MaterialTheme.colorScheme.background
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("save_profile_button")
                    ) {
                        Text(if (isEdit) "Update" else "Save Profile", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
