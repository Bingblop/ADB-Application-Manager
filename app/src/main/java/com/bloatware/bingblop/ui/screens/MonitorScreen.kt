package com.bloatware.bingblop.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bloatware.bingblop.data.model.MonitorTelemetry
import com.bloatware.bingblop.data.repository.AppRepository
import com.bloatware.bingblop.data.repository.MonitorRepository
import com.bloatware.bingblop.data.repository.PowerAction
import com.bloatware.bingblop.data.repository.ShellRepository
import com.bloatware.bingblop.ui.components.ModernCard
import com.bloatware.bingblop.ui.theme.AccentCyan
import com.bloatware.bingblop.ui.theme.BgBase
import com.bloatware.bingblop.ui.theme.CleanGreen
import com.bloatware.bingblop.ui.theme.DangerRed
import com.bloatware.bingblop.ui.theme.TextDim
import com.bloatware.bingblop.ui.theme.TextMain
import com.bloatware.bingblop.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun MonitorScreen(
    monitorRepository: MonitorRepository,
    shellRepository: ShellRepository,
    appRepository: AppRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var telemetry by remember {
        mutableStateOf(
            MonitorTelemetry(
                cpuUsagePercent = 12.0f,
                cpuFrequencyMhz = 2400,
                totalRamBytes = 8589934592L,
                usedRamBytes = 4294967296L,
                batteryLevelPercent = 85,
                batteryTempCelsius = 31.5f,
                batteryCharging = true,
                uptimeSeconds = 3600L
            )
        )
    }

    LaunchedEffect(Unit) {
        telemetry = monitorRepository.getTelemetry()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Device Telemetry & Specs", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Hardware utilization and privileged power state management", color = TextDim, fontSize = 11.sp)
        }

        item {
            ModernCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Memory (RAM) Allocation", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    val usedMb = telemetry.usedRamBytes / (1024 * 1024)
                    val totalMb = telemetry.totalRamBytes / (1024 * 1024)
                    val ramPct = if (totalMb > 0) (usedMb * 100) / totalMb else 50
                    Text("$usedMb MB / $totalMb MB ($ramPct% utilized)", color = AccentCyan, fontSize = 18.sp, fontWeight = FontWeight.Black)

                    Spacer(modifier = Modifier.height(10.dp))

                    Text("Battery Telemetry", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${telemetry.batteryLevelPercent}% • ${telemetry.batteryTempCelsius}°C • ${if (telemetry.batteryCharging) "Charging" else "Discharging"}",
                        color = CleanGreen,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        item {
            Text("Power & Framework Controls", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        items(PowerAction.values().size) { idx ->
            val action = PowerAction.values()[idx]
            ModernCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(action.title, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(action.description, color = TextMuted, fontSize = 11.sp)
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                val res = shellRepository.executePowerAction(action)
                                Toast.makeText(context, "${action.title}: ${res.output.ifEmpty { "Dispatched" }}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (action.isDangerous) DangerRed else AccentCyan,
                            contentColor = BgBase
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Exec", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
