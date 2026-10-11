package com.bloatware.bingblop.ui.screens.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bloatware.bingblop.data.model.DebloatLevel
import com.bloatware.bingblop.ui.theme.AccentCyan
import com.bloatware.bingblop.ui.theme.BgCard
import com.bloatware.bingblop.ui.theme.BgSurface
import com.bloatware.bingblop.ui.theme.BorderGlass
import com.bloatware.bingblop.ui.theme.CleanGreen
import com.bloatware.bingblop.ui.theme.SecondaryPurple
import com.bloatware.bingblop.ui.theme.StatusBloat
import com.bloatware.bingblop.ui.theme.StatusSystem
import com.bloatware.bingblop.ui.theme.TextDim
import com.bloatware.bingblop.ui.theme.TextMain
import com.bloatware.bingblop.ui.theme.TextMuted

@Composable
fun DebloatPresetDialog(
    currentLevel: DebloatLevel,
    detectedVendor: String = "",
    onSelectLevel: (DebloatLevel) -> Unit,
    onSelectVendorPreset: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Debloat Safety Presets",
                color = TextMain,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Select a Universal Android Debloater (UAD) preset level to filter candidate apps.",
                    color = TextMuted,
                    fontSize = 13.sp
                )

                if (detectedVendor.isNotBlank() && onSelectVendorPreset != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AccentCyan.copy(alpha = 0.12f))
                            .border(1.dp, AccentCyan, RoundedCornerShape(10.dp))
                            .clickable { onSelectVendorPreset(detectedVendor) }
                            .padding(12.dp)
                            .testTag("preset_option_vendor"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(AccentCyan)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Device OEM: $detectedVendor",
                                color = AccentCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Target preinstalled apps specific to $detectedVendor devices.",
                                color = TextDim,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }

                PresetLevelOption(
                    level = DebloatLevel.RECOMMENDED,
                    title = "Recommended (Safe)",
                    description = "Telemetry, carrier bloat, analytics daemons, and preinstalled demos. Safe for all devices.",
                    color = CleanGreen,
                    isSelected = currentLevel == DebloatLevel.RECOMMENDED,
                    onClick = { onSelectLevel(DebloatLevel.RECOMMENDED) }
                )

                PresetLevelOption(
                    level = DebloatLevel.ADVANCED,
                    title = "Advanced",
                    description = "Preloaded OEM cloud sync, duplicate browsers, voice assistants. May disable minor features.",
                    color = StatusSystem,
                    isSelected = currentLevel == DebloatLevel.ADVANCED,
                    onClick = { onSelectLevel(DebloatLevel.ADVANCED) }
                )

                PresetLevelOption(
                    level = DebloatLevel.EXPERT,
                    title = "Expert (Use Caution)",
                    description = "Core vendor enhancements, keyboards, system WebViews. Could affect dependent third-party apps.",
                    color = SecondaryPurple,
                    isSelected = currentLevel == DebloatLevel.EXPERT,
                    onClick = { onSelectLevel(DebloatLevel.EXPERT) }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                modifier = Modifier.testTag("dismiss_preset_dialog")
            ) {
                Text("Done", color = BgSurface, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = BgSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.testTag("debloat_preset_dialog")
    )
}

@Composable
private fun PresetLevelOption(
    level: DebloatLevel,
    title: String,
    description: String,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) color.copy(alpha = 0.15f) else BgCard)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) color else BorderGlass,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp)
            .testTag("preset_option_${level.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (isSelected) color else TextMain,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = TextDim,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}
