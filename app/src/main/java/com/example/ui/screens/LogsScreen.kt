package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.ElectricTeal
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.ui.theme.StatusYellow
import com.example.ui.viewmodel.MainViewModel
import com.example.utils.LogEntry
import com.example.utils.LogLevel
import com.example.utils.OGLogger

@Composable
fun LogsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val logs by viewModel.filteredLogs.collectAsState()
    val filterLevel by viewModel.logFilterLevel.collectAsState()
    val filterComponent by viewModel.logFilterComponent.collectAsState()
    val searchQuery by viewModel.logSearchQuery.collectAsState()

    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size, autoScroll) {
        if (autoScroll && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Diagnostics & Logs",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = ElectricTeal,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Safe Diagnostics (Credentials Redacted)",
                            fontSize = 11.sp,
                            color = CyanGlow
                        )
                    }
                }

                // Auto-scroll toggle
                IconButton(
                    onClick = { autoScroll = !autoScroll },
                    modifier = Modifier.testTag("toggle_autoscroll_button")
                ) {
                    Icon(
                        imageVector = if (autoScroll) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                        contentDescription = if (autoScroll) "Pause Auto-scroll" else "Resume Auto-scroll",
                        tint = if (autoScroll) ElectricTeal else Color.Gray
                    )
                }

                // Export Diagnostic Report
                IconButton(
                    onClick = {
                        val report = OGLogger.exportDiagnostics()
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, report)
                            type = "text/plain"
                        }
                        val shareIntent = Intent.createChooser(sendIntent, "Export Diagnostic Report")
                        context.startActivity(shareIntent)
                    },
                    modifier = Modifier.testTag("export_diagnostics_button")
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Export Report", tint = CyanGlow)
                }

                // Copy all
                IconButton(
                    onClick = {
                        val fullLog = logs.joinToString("\n") { entry ->
                            val code = if (entry.eventCode != null) " [${entry.eventCode}]" else ""
                            "[${entry.formattedTime}] [${entry.level}] [${entry.tag}]$code: ${entry.message}"
                        }
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("OGTunnel Logs", fullLog))
                        Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("copy_logs_button")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs", tint = ElectricTeal)
                }

                // Clear
                IconButton(
                    onClick = { viewModel.clearLogs() },
                    modifier = Modifier.testTag("clear_logs_button")
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Logs", tint = Color(0xFFFF5252))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Search input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.logSearchQuery.value = it },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CyanGlow) },
                placeholder = { Text("Filter log messages...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_logs_input"),
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Component Filter Chips (Horizontal Scrollable)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                listOf("ALL", "SSH", "Transport", "TLS", "HTTP", "Xray", "TunPacketRouter", "VpnService", "Diagnostics").forEach { comp ->
                    FilterChip(
                        selected = filterComponent == comp || (filterComponent == null && comp == "ALL"),
                        onClick = { viewModel.logFilterComponent.value = comp },
                        label = { Text(comp, fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Level Filter Chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                FilterChip(
                    selected = filterLevel == null,
                    onClick = { viewModel.logFilterLevel.value = null },
                    label = { Text("ALL LEVELS", fontSize = 11.sp) }
                )
                listOf(LogLevel.INFO, LogLevel.SUCCESS, LogLevel.WARN, LogLevel.ERROR, LogLevel.DEBUG).forEach { lvl ->
                    FilterChip(
                        selected = filterLevel == lvl,
                        onClick = { viewModel.logFilterLevel.value = if (filterLevel == lvl) null else lvl },
                        label = { Text(lvl.name, fontSize = 11.sp) }
                    )
                }
            }
        }

        // Log Console View
        if (logs.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                Text(
                    text = "No log records found matching current filters",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs, key = { it.id }) { entry ->
                    LogLineItem(entry = entry)
                }
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun LogLineItem(entry: LogEntry) {
    val levelColor = when (entry.level) {
        LogLevel.DEBUG -> Color(0xFF9E9E9E)
        LogLevel.INFO -> ElectricTeal
        LogLevel.SUCCESS -> StatusGreen
        LogLevel.WARN -> StatusYellow
        LogLevel.ERROR -> StatusRed
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.formattedTime,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "[${entry.level.name}]",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = levelColor
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "<${entry.tag}>",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = CyanGlow
                )
                if (entry.eventCode != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "#${entry.eventCode}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFAAAAAA)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = entry.message,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 15.sp
            )
            if (entry.details != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = entry.details,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 14.sp
                )
            }
        }
    }
}
