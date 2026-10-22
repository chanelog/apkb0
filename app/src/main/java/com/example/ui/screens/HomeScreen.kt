package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.domain.model.ConnectionState
import com.example.domain.model.Profile
import com.example.domain.model.TunnelProtocol
import com.example.ui.components.ConnectButton
import com.example.ui.components.EmptyProfileNotice
import com.example.ui.components.ProfileSelectionCard
import com.example.ui.components.TrafficStatsCard
import com.example.ui.dialogs.ImportConfigDialog
import com.example.ui.dialogs.ProfileEditorDialog
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.ElectricTeal
import com.example.ui.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToProfiles: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val stats by viewModel.connectionStats.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val profileCount by viewModel.profileCount.collectAsState()
    val allProfiles by viewModel.filteredProfiles.collectAsState()

    var showProfileEditor by remember { mutableStateOf(false) }
    var editorProtocol by remember { mutableStateOf(TunnelProtocol.SSH_WS) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showQuickSwitchDialog by remember { mutableStateOf(false) }

    // VPN permission launcher
    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.toggleConnect(context)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Branding Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.5.dp, ElectricTeal, CircleShape)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_ogtunnel_logo),
                    contentDescription = "OGTunnel Logo",
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "OGTunnel",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "SSHWS & Xray Client",
                    fontSize = 11.sp,
                    color = CyanGlow,
                    fontWeight = FontWeight.Medium
                )
            }

            // Status chip badge
            Box(
                modifier = Modifier
                    .background(
                        color = when (connectionState) {
                            ConnectionState.CONNECTED -> ElectricTeal.copy(alpha = 0.2f)
                            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Color(0x33FFB300)
                            ConnectionState.ERROR -> Color(0x33FF5252)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(20.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = when (connectionState) {
                            ConnectionState.CONNECTED -> ElectricTeal
                            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Color(0xFFFFB300)
                            ConnectionState.ERROR -> Color(0xFFFF5252)
                            else -> MaterialTheme.colorScheme.outline
                        },
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = connectionState.label.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (connectionState) {
                        ConnectionState.CONNECTED -> ElectricTeal
                        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Color(0xFFFFB300)
                        ConnectionState.ERROR -> Color(0xFFFF5252)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (profileCount == 0) {
            // Mandatory Section 1 Empty State Notice
            EmptyProfileNotice(
                onAddSshws = {
                    editorProtocol = TunnelProtocol.SSH_WS
                    showProfileEditor = true
                },
                onAddXray = {
                    editorProtocol = TunnelProtocol.VLESS
                    showProfileEditor = true
                },
                onImportConfig = {
                    showImportDialog = true
                },
                onOpenSettings = onNavigateToSettings
            )
        } else {
            // Active Profile Card
            if (activeProfile != null) {
                ProfileSelectionCard(
                    profile = activeProfile!!,
                    onSwitchClick = { showQuickSwitchDialog = true },
                    onToggleFavorite = { viewModel.toggleFavorite(it) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Main Connect Button
            ConnectButton(
                connectionState = connectionState,
                onClick = {
                    val prepareIntent = VpnService.prepare(context)
                    if (prepareIntent != null) {
                        vpnPrepareLauncher.launch(prepareIntent)
                    } else {
                        viewModel.toggleConnect(context)
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Live Traffic Stats
            TrafficStatsCard(
                stats = stats,
                isConnected = connectionState == ConnectionState.CONNECTED
            )

            // Last Error Notice if any
            if (stats.lastError != null && connectionState == ConnectionState.ERROR) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x22FF5252), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Error: ${stats.lastError}",
                        color = Color(0xFFFF5252),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    // Dialogs
    if (showProfileEditor) {
        ProfileEditorDialog(
            initialProfile = null,
            initialProtocol = editorProtocol,
            onDismiss = { showProfileEditor = false },
            onSave = { profile ->
                viewModel.saveProfile(profile)
                showProfileEditor = false
            }
        )
    }

    if (showImportDialog) {
        ImportConfigDialog(
            onDismiss = { showImportDialog = false },
            onSaveProfiles = { profiles ->
                profiles.forEach { viewModel.saveProfile(it) }
                showImportDialog = false
            }
        )
    }

    // Quick Profile Switch Dialog
    if (showQuickSwitchDialog) {
        Dialog(onDismissRequest = { showQuickSwitchDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .padding(vertical = 20.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Select Profile",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showQuickSwitchDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    allProfiles.forEach { p ->
                        val isSelected = p.id == activeProfile?.id
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setActiveProfile(p)
                                    showQuickSwitchDialog = false
                                }
                                .padding(vertical = 10.dp, horizontal = 8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = p.name,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) ElectricTeal else MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "${p.protocol.displayName} • ${p.displayEndpoint}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = "Selected", tint = ElectricTeal)
                            }
                        }
                    }
                }
            }
        }
    }
}
