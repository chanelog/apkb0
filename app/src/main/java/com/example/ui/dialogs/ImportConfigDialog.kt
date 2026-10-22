package com.example.ui.dialogs

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.config.ConfigParser
import com.example.data.config.ParseResult
import com.example.domain.model.Profile
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.ElectricTeal

@Composable
fun ImportConfigDialog(
    onDismiss: () -> Unit,
    onSaveProfiles: (List<Profile>) -> Unit
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var parsedProfiles by remember { mutableStateOf<List<Profile>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var customName by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
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
                        text = "Import Configuration",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Paste a vless://, vmess://, trojan://, ssh:// link or JSON configuration.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = inputText,
                    onValueChange = {
                        inputText = it
                        parsedProfiles = null
                        errorMessage = null
                    },
                    label = { Text("Configuration Text") },
                    placeholder = { Text("vless://... or vmess://... or JSON") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = clipboard.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val text = clip.getItemAt(0).text?.toString() ?: ""
                                    inputText = text
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste from Clipboard", tint = CyanGlow)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Parse / Preview Action Button
                if (parsedProfiles == null) {
                    Button(
                        onClick = {
                            when (val result = ConfigParser.parse(inputText)) {
                                is ParseResult.Success -> {
                                    parsedProfiles = result.profiles
                                    if (result.profiles.size == 1) {
                                        customName = result.profiles[0].name
                                    }
                                    errorMessage = null
                                }
                                is ParseResult.Error -> {
                                    errorMessage = result.message
                                    parsedProfiles = null
                                }
                            }
                        },
                        enabled = inputText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = CyanGlow
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("preview_config_button")
                    ) {
                        Icon(Icons.Default.Preview, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Preview Configuration")
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x22FF5252), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFFF5252))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(errorMessage!!, color = Color(0xFFFF5252), fontSize = 12.sp)
                    }
                }

                // Preview Card
                if (parsedProfiles != null) {
                    val list = parsedProfiles!!
                    Spacer(modifier = Modifier.height(12.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .border(1.dp, ElectricTeal.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = ElectricTeal)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Detected ${list.size} profile(s)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (list.size == 1) {
                                val p = list[0]
                                OutlinedTextField(
                                    value = customName,
                                    onValueChange = { customName = it },
                                    label = { Text("Profile Name") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Protocol: ${p.protocol.displayName}", fontSize = 12.sp, color = CyanGlow)
                                Text("Endpoint: ${p.displayEndpoint}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (p.protocol.isXray) {
                                    Text("Transport: ${p.transport.uppercase()} | Security: ${p.security.uppercase()}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                list.take(3).forEach { p ->
                                    Text("• ${p.name} (${p.protocol.displayName})", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (list.size > 3) {
                                    Text("...and ${list.size - 3} more", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                parsedProfiles = null
                                errorMessage = null
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Reset")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                val toSave = if (list.size == 1 && customName.isNotBlank()) {
                                    listOf(list[0].copy(name = customName.trim()))
                                } else {
                                    list
                                }
                                onSaveProfiles(toSave)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricTeal,
                                contentColor = MaterialTheme.colorScheme.background
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("confirm_import_button")
                        ) {
                            Text("Save (${list.size})", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
