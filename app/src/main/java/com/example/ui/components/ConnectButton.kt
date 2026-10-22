package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.ConnectionState
import com.example.ui.theme.ElectricTeal
import com.example.ui.theme.StatusBlue
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.ui.theme.StatusYellow

@Composable
fun ConnectButton(
    connectionState: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.CONNECTING) 1.12f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val (glowColor, buttonText) = when (connectionState) {
        ConnectionState.DISCONNECTED -> Pair(Color(0xFF2E3E70), "TAP TO CONNECT")
        ConnectionState.CONNECTING -> Pair(StatusYellow, "CONNECTING...")
        ConnectionState.CONNECTED -> Pair(StatusGreen, "CONNECTED")
        ConnectionState.RECONNECTING -> Pair(StatusBlue, "RECONNECTING...")
        ConnectionState.DISCONNECTING -> Pair(Color(0xFF888888), "DISCONNECTING...")
        ConnectionState.ERROR -> Pair(StatusRed, "ERROR - RETRY")
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(190.dp)
        ) {
            // Outer glowing pulse ring
            if (connectionState.isActiveOrPending) {
                Box(
                    modifier = Modifier
                        .size(175.dp)
                        .scale(pulseScale)
                        .border(
                            width = 2.dp,
                            brush = Brush.radialGradient(listOf(glowColor.copy(alpha = 0.6f), Color.Transparent)),
                            shape = CircleShape
                        )
                )
            }

            // Main circular button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(150.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.background
                            )
                        ),
                        shape = CircleShape
                    )
                    .border(
                        width = 3.dp,
                        brush = Brush.linearGradient(
                            listOf(glowColor, glowColor.copy(alpha = 0.3f))
                        ),
                        shape = CircleShape
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = ElectricTeal),
                        onClick = onClick
                    )
                    .testTag("connect_disconnect_button")
            ) {
                if (connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.DISCONNECTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(72.dp),
                        color = glowColor,
                        strokeWidth = 3.dp
                    )
                } else {
                    Icon(
                        imageVector = if (connectionState == ConnectionState.RECONNECTING) Icons.Default.Sync else Icons.Default.PowerSettingsNew,
                        contentDescription = "Power Button",
                        tint = glowColor,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = buttonText,
            color = glowColor,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            letterSpacing = 1.2.sp
        )
    }
}
