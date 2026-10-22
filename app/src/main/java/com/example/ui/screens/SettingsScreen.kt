package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.data.preferences.SplitTunnelMode
import com.example.data.preferences.ThemeMode
import com.example.ui.dialogs.AppPickerSheet
import com.example.ui.dialogs.ImportConfigDialog
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.ElectricTeal
import com.example.ui.viewmodel.MainViewModel

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()

    var showAppPicker by remember { mutableStateOf(false) }
    var showClearProfilesDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    // DNS state
    var primaryDns by remember(settings.primaryDns) { mutableStateOf(settings.primaryDns) }
    var secondaryDns by remember(settings.secondaryDns) { mutableStateOf(settings.secondaryDns) }
    var mtuText by remember(settings.mtu) { mutableStateOf(settings.mtu.toString()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Settings",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Tunnel parameters, DNS, split tunneling, and appearance",
            fontSize = 12.sp,
            color = CyanGlow
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Appearance
        SettingsSection(title = "APPEARANCE", icon = Icons.Default.Palette) {
            Text("Theme Mode", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM).forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(mode.name) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Connection Reliability
        SettingsSection(title = "CONNECTION RELIABILITY", icon = Icons.Default.Sync) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-Reconnect", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Automatically reconnect when physical network recovers", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.autoReconnect,
                    onCheckedChange = { viewModel.setAutoReconnect(it) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Max Reconnect Retries: ${settings.maxReconnectAttempts}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            Slider(
                value = settings.maxReconnectAttempts.toFloat(),
                onValueChange = { viewModel.setMaxReconnectAttempts(it.toInt()) },
                valueRange = 1f..10f,
                steps = 8
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text("Connection Timeout: ${settings.connectionTimeoutSeconds} seconds", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            Slider(
                value = settings.connectionTimeoutSeconds.toFloat(),
                onValueChange = { viewModel.setConnectionTimeout(it.toInt()) },
                valueRange = 5f..60f,
                steps = 10
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // DNS & MTU Settings
        SettingsSection(title = "DNS & NETWORK INTERFACE", icon = Icons.Default.Dns) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = primaryDns,
                    onValueChange = {
                        primaryDns = it
                        viewModel.setDns(primaryDns, secondaryDns)
                    },
                    label = { Text("Primary DNS") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = secondaryDns,
                    onValueChange = {
                        secondaryDns = it
                        viewModel.setDns(primaryDns, secondaryDns)
                    },
                    label = { Text("Secondary DNS") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = mtuText,
                onValueChange = {
                    mtuText = it
                    it.toIntOrNull()?.let { mtu -> viewModel.setMtu(mtu) }
                },
                label = { Text("TUN MTU (1280 - 9000)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Split Tunneling
        SettingsSection(title = "SPLIT TUNNELING", icon = Icons.Default.Apps) {
            Text("Mode", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    SplitTunnelMode.ALL_APPS to "All Apps",
                    SplitTunnelMode.INCLUDE_ONLY to "Only Selected",
                    SplitTunnelMode.EXCLUDE_ONLY to "Bypass Selected"
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = settings.splitTunnelMode == mode,
                        onClick = { viewModel.setSplitTunnelMode(mode) },
                        label = { Text(label, fontSize = 11.sp) }
                    )
                }
            }

            if (settings.splitTunnelMode != SplitTunnelMode.ALL_APPS) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAppPicker = true }
                        .padding(vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Selected Applications",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${settings.splitTunnelPackages.size} app(s) configured",
                            fontSize = 11.sp,
                            color = CyanGlow
                        )
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = CyanGlow)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Backup, Restore & Profile Reset
        SettingsSection(title = "PROFILE BACKUP & MANAGEMENT", icon = Icons.Default.Backup) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { showBackupDialog = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Backup JSON", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = { showRestoreDialog = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Restore JSON", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { showClearProfilesDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FF5252), contentColor = Color(0xFFFF5252)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear All Saved Profiles", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // About & Privacy
        SettingsSection(title = "ABOUT OGTUNNEL", icon = Icons.Default.Info) {
            Text("OGTunnel SSHWS & Xray Client", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("Version 1.0 (Build 1) • Production Architecture", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "• Strict Zero-Telemetry: No outbound usage tracking or analytic beacons.\n" +
                "• Autonomous Client: OGTunnel does not provide VPN servers or accounts.\n" +
                "• Dual Tunnel Engine: Pure Kotlin packet router, JSch SSHWS transport, and standard Xray core integration (MPL 2.0).",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(30.dp))
    }

    // App Picker
    if (showAppPicker) {
        AppPickerSheet(
            selectedPackages = settings.splitTunnelPackages,
            onDismiss = { showAppPicker = false },
            onSelectionChanged = { updated ->
                viewModel.setSplitTunnelPackages(updated)
            }
        )
    }

    // Clear Profiles Dialog
    if (showClearProfilesDialog) {
        AlertDialog(
            onDismissRequest = { showClearProfilesDialog = false },
            title = { Text("Clear All Profiles") },
            text = { Text("This will permanently remove all SSHWS and Xray profiles from device storage. Are you sure?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllProfiles()
                        showClearProfilesDialog = false
                    }
                ) {
                    Text("Delete Everything", color = Color(0xFFFF5252))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearProfilesDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Backup Dialog with Privacy Warning
    if (showBackupDialog) {
        var redactCredentials by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text("Export Profiles Backup") },
            text = {
                Column {
                    Text("Choose whether to include unredacted passwords, private keys, and UUIDs:")
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = !redactCredentials,
                            onCheckedChange = { redactCredentials = !it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (redactCredentials) "Redact Credentials (Safe)" else "Include Credentials (Contains Secrets!)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (redactCredentials) ElectricTeal else Color(0xFFFFB300)
                        )
                    }
                    if (!redactCredentials) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Warning: The exported text will contain sensitive passwords and UUIDs. Do not share publicly.",
                            fontSize = 11.sp,
                            color = Color(0xFFFF5252)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val json = viewModel.exportAllProfilesJson(redactCredentials)
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("OGTunnel Backup", json))
                        Toast.makeText(context, "Backup JSON copied to clipboard", Toast.LENGTH_SHORT).show()
                        showBackupDialog = false
                    }
                ) {
                    Text("Copy JSON", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Restore Dialog
    if (showRestoreDialog) {
        ImportConfigDialog(
            onDismiss = { showRestoreDialog = false },
            onSaveProfiles = { list ->
                list.forEach { viewModel.saveProfile(it) }
                showRestoreDialog = false
                Toast.makeText(context, "Restored ${list.size} profile(s)", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun SettingsSection(
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
                Icon(imageVector = icon, contentDescription = null, tint = CyanGlow, modifier = Modifier.size(18.dp))
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
