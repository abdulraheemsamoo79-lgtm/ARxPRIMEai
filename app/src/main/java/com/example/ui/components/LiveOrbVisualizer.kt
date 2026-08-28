package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AssistantState
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun LiveOrbVisualizer(
    state: AssistantState,
    inputAmplitude: Float,
    outputAmplitude: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_transition")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val energyPulse by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "energy"
    )

    val activeAmp = when (state) {
        AssistantState.LISTENING -> inputAmplitude.coerceIn(0.1f, 1.0f)
        AssistantState.SPEAKING -> outputAmplitude.coerceIn(0.15f, 1.0f)
        AssistantState.EXECUTING_ACTION -> 0.8f
        AssistantState.THINKING -> 0.5f * energyPulse
        AssistantState.IDLE -> 0.05f
    }

    val primaryOrbColor = when (state) {
        AssistantState.LISTENING -> Color(0xFF00E5FF) // Electric Cyan
        AssistantState.SPEAKING -> Color(0xFF38BDF8) // Bright Sky Blue
        AssistantState.THINKING -> Color(0xFFA855F7) // Neon Purple
        AssistantState.EXECUTING_ACTION -> Color(0xFF10B981) // Emerald Green
        AssistantState.IDLE -> Color(0xFF0284C7)
    }

    val secondaryOrbColor = when (state) {
        AssistantState.LISTENING -> Color(0xFF06B6D4)
        AssistantState.SPEAKING -> Color(0xFF2563EB)
        AssistantState.THINKING -> Color(0xFFEC4899)
        AssistantState.EXECUTING_ACTION -> Color(0xFF059669)
        AssistantState.IDLE -> Color(0xFF1E3A8A)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(170.dp)
                .testTag("live_orb_visualizer"),
            contentAlignment = Alignment.Center
        ) {
            // Background glow canvas
            Canvas(modifier = Modifier.size(170.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseRadius = (size.width / 2.6f) * pulseScale * (1f + activeAmp * 0.25f)

                // Outer ambient glow ring
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryOrbColor.copy(alpha = 0.45f * energyPulse),
                            secondaryOrbColor.copy(alpha = 0.2f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = baseRadius * 1.5f
                    ),
                    radius = baseRadius * 1.4f,
                    center = center
                )

                // Multi-orbital wave rings
                val rings = 3
                for (i in 1..rings) {
                    val ringRadius = baseRadius * (0.6f + i * 0.18f + activeAmp * 0.15f)
                    val strokeW = (2f + (i * 1.2f) + activeAmp * 4f)
                    drawCircle(
                        color = if (i % 2 == 0) primaryOrbColor.copy(alpha = 0.6f) else secondaryOrbColor.copy(alpha = 0.5f),
                        radius = ringRadius,
                        center = center,
                        style = Stroke(width = strokeW)
                    )
                }

                // Rotating quantum nodes
                val numNodes = 6
                val nodeOrbitRadius = baseRadius * 0.88f
                for (n in 0 until numNodes) {
                    val angleDeg = (rotation + (n * (360f / numNodes))) * (Math.PI / 180f)
                    val nodeX = center.x + (nodeOrbitRadius * cos(angleDeg)).toFloat()
                    val nodeY = center.y + (nodeOrbitRadius * sin(angleDeg)).toFloat()
                    drawCircle(
                        color = primaryOrbColor,
                        radius = (3.5f + activeAmp * 3.5f),
                        center = Offset(nodeX, nodeY)
                    )
                }

                // Core inner energy sphere
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White,
                            primaryOrbColor,
                            secondaryOrbColor.copy(alpha = 0.8f)
                        ),
                        center = center,
                        radius = baseRadius * 0.55f
                    ),
                    radius = baseRadius * 0.55f,
                    center = center
                )
            }

            // Center state icon
            Icon(
                imageVector = when (state) {
                    AssistantState.LISTENING -> Icons.Default.Mic
                    AssistantState.SPEAKING -> Icons.Default.GraphicEq
                    AssistantState.THINKING -> Icons.Default.SmartToy
                    AssistantState.EXECUTING_ACTION -> Icons.Default.TouchApp
                    AssistantState.IDLE -> Icons.Default.GraphicEq
                },
                contentDescription = "Assistant State Icon",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // State indicator pill
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = primaryOrbColor.copy(alpha = 0.15f),
            border = androidx.compose.foundation.BorderStroke(1.dp, primaryOrbColor.copy(alpha = 0.5f)),
            modifier = Modifier.testTag("status_indicator_pill")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(primaryOrbColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (state) {
                        AssistantState.LISTENING -> "Listening to your voice..."
                        AssistantState.THINKING -> "AR PRIME AI is thinking..."
                        AssistantState.SPEAKING -> "Speaking (Gemini Live Male Voice)"
                        AssistantState.EXECUTING_ACTION -> "Executing Device Action..."
                        AssistantState.IDLE -> "AR PRIME AI • Ready to assist"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp,
                        color = Color.White
                    )
                )
            }
        }
    }
}
