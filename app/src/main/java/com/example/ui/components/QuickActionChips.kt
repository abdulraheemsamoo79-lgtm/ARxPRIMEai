package com.example.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class QuickPrompt(
    val title: String,
    val command: String,
    val icon: ImageVector
)

@Composable
fun QuickActionChips(
    onChipClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val prompts = listOf(
        QuickPrompt("WhatsApp kholo", "WhatsApp kholo", Icons.Default.Launch),
        QuickPrompt("Open WhatsApp", "Open WhatsApp", Icons.Default.Launch),
        QuickPrompt("Call Mummy", "Mummy ko call karo", Icons.Default.Phone),
        QuickPrompt("Call Rahul", "Rahul ko call karo", Icons.Default.Call),
        QuickPrompt("Call 9876543210", "Call 9876543210", Icons.Default.Phone),
        QuickPrompt("Open YouTube", "Open YouTube", Icons.Default.Launch),
        QuickPrompt("Open Settings", "Open device settings", Icons.Default.Settings),
        QuickPrompt("Hindi mein baat karo", "Hindi mein baat karo", Icons.Default.Language),
        QuickPrompt("Hinglish mein bolo", "Hinglish mein baat karo", Icons.Default.Language),
        QuickPrompt("Talk in English", "Talk to me in English", Icons.Default.Language)
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("quick_action_chips_row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        prompts.forEachIndexed { index, item ->
            AssistChip(
                onClick = { onChipClick(item.command) },
                label = {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 12.sp,
                            color = Color(0xFFE2E8F0)
                        )
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.title,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(16.dp)
                    )
                },
                shape = RoundedCornerShape(20.dp),
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = Color(0xFF1E293B).copy(alpha = 0.9f)
                ),
                border = AssistChipDefaults.assistChipBorder(
                    enabled = true,
                    borderColor = Color(0xFF334155)
                ),
                modifier = Modifier.testTag("quick_chip_$index")
            )
        }
    }
}
