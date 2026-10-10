package com.bloatware.bingblop.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bloatware.bingblop.data.model.DeviceInfo
import com.bloatware.bingblop.data.repository.MonitorRepository
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var deviceInfo by remember { mutableStateOf<DeviceInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }

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
            IconButton(onClick = { refreshMetrics() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentCyan)
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
                                    Text("RAM Utilization", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain)
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
                                Text("Used: ${Formatter.formatFileSize(context, ramUsed)}", fontSize = 11.sp, color = TextMuted)
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
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = CleanGreen, modifier = Modifier.size(18.dp))
                                    Text("Battery Health & Status", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain)
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
                                Text("Temp: ${info.batteryTemp}°C", fontSize = 12.sp, color = TextMain)
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
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
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
