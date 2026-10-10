package com.bloatware.bingblop.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bloatware.bingblop.data.model.DeviceInfo
import com.bloatware.bingblop.data.repository.MonitorRepository
import com.bloatware.bingblop.data.repository.PowerAction
import com.bloatware.bingblop.data.repository.ShellRepository
import com.bloatware.bingblop.ui.components.CyberCard
import com.bloatware.bingblop.ui.components.StatusPill
import com.bloatware.bingblop.ui.theme.AccentCyan
import com.bloatware.bingblop.ui.theme.BgBase
import com.bloatware.bingblop.ui.theme.BgCard
import com.bloatware.bingblop.ui.theme.BgSurface
import com.bloatware.bingblop.ui.theme.BorderGlass
import com.bloatware.bingblop.ui.theme.CleanGreen
import com.bloatware.bingblop.ui.theme.SecondaryPurple
import com.bloatware.bingblop.ui.theme.StatusBloat
import com.bloatware.bingblop.ui.theme.StatusRunning
import com.bloatware.bingblop.ui.theme.TextDim
import com.bloatware.bingblop.ui.theme.TextMain
import com.bloatware.bingblop.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun MonitorScreen(
    monitorRepository: MonitorRepository,
    shellRepository: ShellRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var deviceInfo by remember { mutableStateOf<DeviceInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var pendingPowerAction by remember { mutableStateOf<PowerAction?>(null) }
    var isExecutingPowerAction by remember { mutableStateOf(false) }

    fun refreshMetrics() {
        isLoading = true
        scope.launch {
            deviceInfo = monitorRepository.getDeviceInfo()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshMetrics()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(horizontal = 14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Device Telemetry & Specs", fontSize = 16.sp, fontWeight = FontWeight.Black, color = TextMain)
                Text("Real-time RAM, storage, battery & kernel monitors", fontSize = 11.sp, color = TextMuted)
            }
            Row {
                deviceInfo?.let { info ->
                    IconButton(onClick = {
                        val specText = buildString {
                            appendLine("Device: ${info.manufacturer} ${info.model} (${info.brand})")
                            appendLine("SoC: ${info.soc} | ABI: ${info.abi}")
                            appendLine("Android OS: ${info.androidVersion} (API ${info.sdkInt})")
                            appendLine("Security Patch: ${info.securityPatch}")
                            appendLine("Kernel: ${info.kernelVersion}")
                            appendLine("RAM Total: ${Formatter.formatFileSize(context, info.ramTotalBytes)}")
                            appendLine("Storage Total: ${Formatter.formatFileSize(context, info.storageTotalBytes)}")
                            appendLine("Battery: ${info.batteryPct}% (${info.batteryStatus}, ${info.batteryTemp}°C)")
                        }
                        val clip = ClipData.newPlainText("Device Specs", specText)
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(clip)
                        Toast.makeText(context, "Hardware specifications copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Specs", tint = AccentCyan)
                    }
                }
                IconButton(onClick = { refreshMetrics() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentCyan)
                }
            }
        }

        if (isLoading || deviceInfo == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentCyan)
            }
        } else {
            val info = deviceInfo!!
            val ramUsed = info.ramTotalBytes - info.ramAvailBytes
            val ramRatio = (ramUsed.toFloat() / info.ramTotalBytes.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)

            val storageUsed = info.storageTotalBytes - info.storageAvailBytes
            val storageRatio = (storageUsed.toFloat() / info.storageTotalBytes.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)

            val uptimeHours = (info.uptimeMillis / (1000 * 60 * 60))
            val uptimeDays = uptimeHours / 24

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // RAM Gauge Card
                item {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Memory, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                                    Text("RAM Memory Utilization", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain)
                                }
                                Text("${(ramRatio * 100).toInt()}%", fontSize = 14.sp, fontWeight = FontWeight.Black, color = AccentCyan)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            LinearProgressIndicator(
                                progress = { ramRatio },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = AccentCyan,
                                trackColor = BgSurface
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("In Use: ${Formatter.formatFileSize(context, ramUsed)}", fontSize = 11.sp, color = TextMuted)
                                Text("Total: ${Formatter.formatFileSize(context, info.ramTotalBytes)}", fontSize = 11.sp, color = TextMain)
                            }
                        }
                    }
                }

                // Storage Gauge Card
                item {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Storage, contentDescription = null, tint = SecondaryPurple, modifier = Modifier.size(18.dp))
                                    Text("Internal Storage (/data)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain)
                                }
                                Text("${(storageRatio * 100).toInt()}%", fontSize = 14.sp, fontWeight = FontWeight.Black, color = SecondaryPurple)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            LinearProgressIndicator(
                                progress = { storageRatio },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = SecondaryPurple,
                                trackColor = BgSurface
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Free: ${Formatter.formatFileSize(context, info.storageAvailBytes)}", fontSize = 11.sp, color = TextMuted)
                                Text("Total: ${Formatter.formatFileSize(context, info.storageTotalBytes)}", fontSize = 11.sp, color = TextMain)
                            }
                        }
                    }
                }

                // Battery Telemetry Card
                item {
                    val tempColor = if (info.batteryTemp > 40f) StatusBloat else if (info.batteryTemp > 35f) AccentCyan else CleanGreen
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = CleanGreen, modifier = Modifier.size(18.dp))
                                    Text("Battery Health & Thermals", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain)
                                }
                                StatusPill("${info.batteryPct}%", CleanGreen)
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Status: ${info.batteryStatus}", fontSize = 12.sp, color = TextMain)
                                Text("Health: ${info.batteryHealth}", fontSize = 12.sp, color = CleanGreen)
                                Text("Temp: ${info.batteryTemp}°C", fontSize = 12.sp, color = tempColor, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Power & Reboot Controls Card
                item {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = StatusBloat, modifier = Modifier.size(18.dp))
                                    Text("POWER & REBOOT CONTROLS", fontSize = 11.sp, fontWeight = FontWeight.Black, color = TextMuted, letterSpacing = 1.sp)
                                }
                                StatusPill("ROOT / ADB", AccentCyan)
                            }

                            Text(
                                "Hardware & userspace power signals via privileged shell commands.",
                                fontSize = 11.sp,
                                color = TextDim
                            )

                            // 2x2 Grid of Primary Controls
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    PowerActionButton(
                                        title = "Reboot",
                                        subtitle = "System",
                                        icon = Icons.Default.RestartAlt,
                                        color = AccentCyan,
                                        modifier = Modifier.weight(1f),
                                        onClick = { pendingPowerAction = PowerAction.REBOOT }
                                    )
                                    PowerActionButton(
                                        title = "Soft Reboot",
                                        subtitle = "Zygote",
                                        icon = Icons.Default.Bolt,
                                        color = CleanGreen,
                                        modifier = Modifier.weight(1f),
                                        onClick = { pendingPowerAction = PowerAction.SOFT_REBOOT }
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    PowerActionButton(
                                        title = "Recovery",
                                        subtitle = "Maintenance",
                                        icon = Icons.Default.Build,
                                        color = SecondaryPurple,
                                        modifier = Modifier.weight(1f),
                                        onClick = { pendingPowerAction = PowerAction.REBOOT_RECOVERY }
                                    )
                                    PowerActionButton(
                                        title = "Power Off",
                                        subtitle = "Shutdown",
                                        icon = Icons.Default.PowerSettingsNew,
                                        color = StatusBloat,
                                        modifier = Modifier.weight(1f),
                                        onClick = { pendingPowerAction = PowerAction.POWER_OFF }
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    PowerActionButton(
                                        title = "Bootloader",
                                        subtitle = "Fastboot",
                                        icon = Icons.Default.DeveloperBoard,
                                        color = Color(0xFFFF9100),
                                        modifier = Modifier.weight(1f),
                                        onClick = { pendingPowerAction = PowerAction.REBOOT_BOOTLOADER }
                                    )
                                    PowerActionButton(
                                        title = "SystemUI",
                                        subtitle = "Restart UI",
                                        icon = Icons.Default.Refresh,
                                        color = TextMuted,
                                        modifier = Modifier.weight(1f),
                                        onClick = { pendingPowerAction = PowerAction.RESTART_SYSTEM_UI }
                                    )
                                }
                            }
                        }
                    }
                }

                // Device Specifications
                item {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.DeveloperBoard, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                                Text("HARDWARE SPECIFICATIONS", fontSize = 11.sp, fontWeight = FontWeight.Black, color = TextMuted, letterSpacing = 1.sp)
                            }

                            SpecRow("Device Model", info.model)
                            SpecRow("Manufacturer / Brand", "${info.manufacturer} (${info.brand})")
                            SpecRow("SoC Processor", info.soc)
                            SpecRow("CPU Architecture", info.abi)
                            SpecRow("Android OS Version", "Android ${info.androidVersion} (API ${info.sdkInt})")
                            SpecRow("Security Patch", info.securityPatch)
                            SpecRow("Linux Kernel", info.kernelVersion)
                            SpecRow("System Uptime", if (uptimeDays > 0) "${uptimeDays}d ${uptimeHours % 24}h" else "${uptimeHours}h")
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        }

        // Confirmation Dialog for Power & Reboot Controls
        pendingPowerAction?.let { action ->
            AlertDialog(
                onDismissRequest = { if (!isExecutingPowerAction) pendingPowerAction = null },
                containerColor = BgCard,
                icon = {
                    Icon(
                        if (action.isDangerous) Icons.Default.Warning else Icons.Default.RestartAlt,
                        contentDescription = null,
                        tint = if (action.isDangerous) StatusBloat else AccentCyan,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text(
                        text = "Confirm ${action.title}?",
                        color = TextMain,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = action.description,
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(BgSurface)
                                .border(width = 1.dp, color = BorderGlass, shape = RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "$ ${action.command}",
                                color = AccentCyan,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (action.isDangerous) {
                            Text(
                                text = "Warning: Make sure all background apps and unsaved work are preserved before executing.",
                                color = StatusBloat,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isExecutingPowerAction = true
                            scope.launch {
                                val res = shellRepository.executePowerAction(action)
                                isExecutingPowerAction = false
                                pendingPowerAction = null
                                val statusMsg = if (res.exitCode == 0) "${action.title} executed" else "Dispatched: ${res.output.take(80)}"
                                Toast.makeText(context, statusMsg, Toast.LENGTH_LONG).show()
                            }
                        },
                        enabled = !isExecutingPowerAction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (action.isDangerous) StatusBloat else AccentCyan,
                            contentColor = BgBase
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isExecutingPowerAction) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgBase, strokeWidth = 2.dp)
                        } else {
                            Text("Confirm & Execute")
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { pendingPowerAction = null },
                        enabled = !isExecutingPowerAction
                    ) {
                        Text("Cancel", color = TextDim)
                    }
                }
            )
        }
    }
}

@Composable
fun PowerActionButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BgSurface)
            .border(width = 1.dp, color = BorderGlass, shape = RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            }
            Column {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextMain)
                Text(subtitle, fontSize = 10.sp, color = TextDim)
            }
        }
    }
}

@Composable
fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = TextMuted)
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TextMain,
            fontFamily = FontFamily.Monospace
        )
    }
}
