package com.bloatware.bingblop.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.bloatware.bingblop.data.model.PowerUserApp
import com.bloatware.bingblop.data.model.SystemSettingItem
import com.bloatware.bingblop.data.model.WirelessAdbInfo
import com.bloatware.bingblop.data.model.WirelessAdbState
import com.bloatware.bingblop.data.repository.AppRepository
import com.bloatware.bingblop.data.repository.SettingsRepository
import com.bloatware.bingblop.ui.components.ModernCard
import com.bloatware.bingblop.ui.theme.AccentCyan
import com.bloatware.bingblop.ui.theme.BgBase
import com.bloatware.bingblop.ui.theme.BgCard
import com.bloatware.bingblop.ui.theme.BgSurface
import com.bloatware.bingblop.ui.theme.BorderGlass
import com.bloatware.bingblop.ui.theme.CleanGreen
import com.bloatware.bingblop.ui.theme.DangerRed
import com.bloatware.bingblop.ui.theme.SecondaryPurple
import com.bloatware.bingblop.ui.theme.TextDim
import com.bloatware.bingblop.ui.theme.TextMain
import com.bloatware.bingblop.ui.theme.TextMuted
import com.bloatware.bingblop.ui.theme.WarningAmber
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    appRepository: AppRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Wireless ADB", "Display & WM", "Hidden Tweaks", "Permission Hub")

    var settingsList by remember { mutableStateOf<List<SystemSettingItem>>(emptyList()) }
    var currentDisplaySize by remember { mutableStateOf("") }
    var currentDensity by remember { mutableStateOf("") }
    var powerUserApps by remember { mutableStateOf<List<PowerUserApp>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Wireless ADB state
    var wirelessAdbInfo by remember {
        mutableStateOf(
            WirelessAdbInfo(
                state = WirelessAdbState.DISCONNECTED,
                ipAddress = settingsRepository.getLocalIpAddress()
            )
        )
    }
    var pairingPortInput by remember { mutableStateOf("") }
    var pairingCodeInput by remember { mutableStateOf("") }
    var connectPortInput by remember { mutableStateOf("") }
    var adbConsoleLog by remember { mutableStateOf("") }
    var isAdbProcessing by remember { mutableStateOf(false) }

    fun refreshAll() {
        scope.launch {
            isLoading = true
            settingsList = settingsRepository.loadSettings()
            currentDisplaySize = settingsRepository.getDisplaySize()
            currentDensity = settingsRepository.getDisplayDensity()
            powerUserApps = settingsRepository.getPowerUserApps()
            wirelessAdbInfo = wirelessAdbInfo.copy(ipAddress = settingsRepository.getLocalIpAddress())
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshAll()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "System Tweaks & ADB Hub",
                    color = TextMain,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Elevated ADB controls, display scaling, and hidden Android toggles",
                    color = TextDim,
                    fontSize = 11.sp
                )
            }

            IconButton(
                onClick = { refreshAll() },
                modifier = Modifier
                    .background(BgSurface, RoundedCornerShape(10.dp))
                    .border(1.dp, BorderGlass, RoundedCornerShape(10.dp))
                    .size(42.dp)
                    .testTag("settings_refresh_btn")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentCyan, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Tabs
        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = BgSurface,
            contentColor = AccentCyan,
            edgePadding = 0.dp,
            indicator = { tabPositions ->
                TabRowDefaults.Indicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                    color = AccentCyan,
                    height = 2.dp
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderGlass, RoundedCornerShape(8.dp))
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = {
                        Text(
                            text = title,
                            fontSize = 11.sp,
                            fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTabIndex == index) AccentCyan else TextMuted
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AccentCyan)
            }
        } else {
            when (selectedTabIndex) {
                0 -> WirelessAdbSection(
                    info = wirelessAdbInfo,
                    pairingPort = pairingPortInput,
                    onPairingPortChange = { pairingPortInput = it },
                    pairingCode = pairingCodeInput,
                    onPairingCodeChange = { pairingCodeInput = it },
                    connectPort = connectPortInput,
                    onConnectPortChange = { connectPortInput = it },
                    consoleLog = adbConsoleLog,
                    isProcessing = isAdbProcessing,
                    onOpenDeveloperSettings = {
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "Could not launch Developer Options directly", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onPair = {
                        scope.launch {
                            isAdbProcessing = true
                            adbConsoleLog = "Executing: adb pair 127.0.0.1:$pairingPortInput $pairingCodeInput\n"
                            val res = settingsRepository.pairWirelessAdb(pairingPortInput, pairingCodeInput)
                            adbConsoleLog += res.output + "\n"
                            if (res.exitCode == 0 || res.output.contains("Successfully paired", ignoreCase = true)) {
                                wirelessAdbInfo = wirelessAdbInfo.copy(state = WirelessAdbState.PAIRING)
                                Toast.makeText(context, "Successfully paired with Wireless ADB!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Pairing finished with code ${res.exitCode}", Toast.LENGTH_SHORT).show()
                            }
                            isAdbProcessing = false
                        }
                    },
                    onConnect = {
                        scope.launch {
                            isAdbProcessing = true
                            adbConsoleLog = "Executing: adb connect 127.0.0.1:$connectPortInput\n"
                            val res = settingsRepository.connectWirelessAdb(connectPortInput)
                            adbConsoleLog += res.output + "\n"
                            val devicesOut = settingsRepository.checkAdbDevices()
                            adbConsoleLog += "\nadb devices:\n" + devicesOut

                            if (res.output.contains("connected", ignoreCase = true)) {
                                wirelessAdbInfo = wirelessAdbInfo.copy(state = WirelessAdbState.CONNECTED)
                                Toast.makeText(context, "Wireless ADB Connected!", Toast.LENGTH_SHORT).show()
                            }
                            isAdbProcessing = false
                        }
                    },
                    onDisconnect = {
                        scope.launch {
                            isAdbProcessing = true
                            val res = settingsRepository.disconnectWirelessAdb()
                            adbConsoleLog = "Disconnected: ${res.output}"
                            wirelessAdbInfo = wirelessAdbInfo.copy(state = WirelessAdbState.DISCONNECTED)
                            isAdbProcessing = false
                        }
                    }
                )

                1 -> DisplaySection(
                    displaySizeStr = currentDisplaySize,
                    densityStr = currentDensity,
                    onApplySize = { w, h ->
                        scope.launch {
                            val res = settingsRepository.setDisplaySize(w, h)
                            Toast.makeText(context, "Applied resolution ${w}x${h}", Toast.LENGTH_SHORT).show()
                            currentDisplaySize = settingsRepository.getDisplaySize()
                        }
                    },
                    onResetSize = {
                        scope.launch {
                            settingsRepository.resetDisplaySize()
                            Toast.makeText(context, "Reset resolution to physical default", Toast.LENGTH_SHORT).show()
                            currentDisplaySize = settingsRepository.getDisplaySize()
                        }
                    },
                    onApplyDensity = { dpi ->
                        scope.launch {
                            settingsRepository.setDisplayDensity(dpi)
                            Toast.makeText(context, "Applied density ${dpi} DPI", Toast.LENGTH_SHORT).show()
                            currentDensity = settingsRepository.getDisplayDensity()
                        }
                    },
                    onResetDensity = {
                        scope.launch {
                            settingsRepository.resetDisplayDensity()
                            Toast.makeText(context, "Reset density to physical default", Toast.LENGTH_SHORT).show()
                            currentDensity = settingsRepository.getDisplayDensity()
                        }
                    },
                    onSetRefreshRate = { rate ->
                        scope.launch {
                            if (rate == null) {
                                settingsRepository.resetSetting("peak_refresh_rate")
                                settingsRepository.resetSetting("min_refresh_rate")
                                Toast.makeText(context, "Reset refresh rate to adaptive default", Toast.LENGTH_SHORT).show()
                            } else {
                                settingsRepository.updateSetting("peak_refresh_rate", rate)
                                settingsRepository.updateSetting("min_refresh_rate", rate)
                                Toast.makeText(context, "Forced refresh rate to $rate Hz", Toast.LENGTH_SHORT).show()
                            }
                            refreshAll()
                        }
                    }
                )

                2 -> HiddenTweaksSection(
                    settings = settingsList,
                    onToggleSetting = { item, newValue ->
                        scope.launch {
                            settingsRepository.updateSetting(item.key, newValue)
                            refreshAll()
                        }
                    },
                    onSetSpeed = { scale ->
                        scope.launch {
                            settingsRepository.setAllAnimationScales(scale)
                            Toast.makeText(context, "Animation scales set to ${scale}x", Toast.LENGTH_SHORT).show()
                            refreshAll()
                        }
                    },
                    onSetPrivateDns = { mode, host ->
                        scope.launch {
                            settingsRepository.setPrivateDns(mode, host)
                            Toast.makeText(context, "Private DNS resolver updated", Toast.LENGTH_SHORT).show()
                            refreshAll()
                        }
                    }
                )

                3 -> PermissionHubSection(
                    apps = powerUserApps,
                    onGrant = { app ->
                        scope.launch {
                            val res = settingsRepository.grantPowerUserPermissions(app.packageName)
                            Toast.makeText(context, "Granted 4 privileged permissions to ${app.appName}", Toast.LENGTH_SHORT).show()
                            refreshAll()
                        }
                    }
                )
            }
        }
    }
}

// ==========================================
// 1. WIRELESS ADB SECTION
// ==========================================
@Composable
private fun WirelessAdbSection(
    info: WirelessAdbInfo,
    pairingPort: String,
    onPairingPortChange: (String) -> Unit,
    pairingCode: String,
    onPairingCodeChange: (String) -> Unit,
    connectPort: String,
    onConnectPortChange: (String) -> Unit,
    consoleLog: String,
    isProcessing: Boolean,
    onOpenDeveloperSettings: () -> Unit,
    onPair: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status & Connection Card
        item {
            ModernCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Wifi, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Wireless Debugging (Android 11+)", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        val (statusText, statusColor) = when (info.state) {
                            WirelessAdbState.CONNECTED -> "CONNECTED" to CleanGreen
                            WirelessAdbState.PAIRING -> "PAIRED" to AccentCyan
                            WirelessAdbState.CONNECTING -> "CONNECTING" to WarningAmber
                            WirelessAdbState.FAILED -> "FAILED" to DangerRed
                            WirelessAdbState.DISCONNECTED -> "DISCONNECTED" to TextDim
                        }

                        Text(
                            text = statusText,
                            color = statusColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier
                                .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Device Local IP: ${info.ipAddress} • Loopback Target: 127.0.0.1",
                        color = TextMuted,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = onOpenDeveloperSettings,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderGlass),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Developer Options", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Pairing Form Card
        item {
            ModernCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Step 1: Wi-Fi Pairing Code", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Developer Options -> Wireless Debugging -> 'Pair device with pairing code'", color = TextDim, fontSize = 10.sp)

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = pairingPort,
                            onValueChange = onPairingPortChange,
                            placeholder = { Text("Port (e.g. 37123)", color = TextDim, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = BorderGlass,
                                focusedTextColor = TextMain,
                                unfocusedTextColor = TextMain,
                                focusedContainerColor = BgCard,
                                unfocusedContainerColor = BgCard
                            ),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = pairingCode,
                            onValueChange = onPairingCodeChange,
                            placeholder = { Text("6-Digit Code", color = TextDim, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = BorderGlass,
                                focusedTextColor = TextMain,
                                unfocusedTextColor = TextMain,
                                focusedContainerColor = BgCard,
                                unfocusedContainerColor = BgCard
                            ),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = onPair,
                        enabled = pairingPort.isNotBlank() && pairingCode.isNotBlank() && !isProcessing,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pair with Local ADB (TLS)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Connection Form Card
        item {
            ModernCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Step 2: Connect Daemon", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Use the primary IP address and port shown under 'Wireless Debugging'", color = TextDim, fontSize = 10.sp)

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = connectPort,
                        onValueChange = onConnectPortChange,
                        placeholder = { Text("Connection Port (e.g. 42159)", color = TextDim, fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = BorderGlass,
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain,
                            focusedContainerColor = BgCard,
                            unfocusedContainerColor = BgCard
                        ),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onConnect,
                            enabled = connectPort.isNotBlank() && !isProcessing,
                            colors = ButtonDefaults.buttonColors(containerColor = CleanGreen, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Connect (adb connect)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = onDisconnect,
                            enabled = !isProcessing,
                            colors = ButtonDefaults.buttonColors(containerColor = BgCard, contentColor = DangerRed),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Disconnect", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Console Output Log
        if (consoleLog.isNotEmpty()) {
            item {
                ModernCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("ADB Daemon Response", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = consoleLog,
                            color = TextMain,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// 2. DISPLAY & WM SECTION
// ==========================================
@Composable
private fun DisplaySection(
    displaySizeStr: String,
    densityStr: String,
    onApplySize: (Int, Int) -> Unit,
    onResetSize: () -> Unit,
    onApplyDensity: (Int) -> Unit,
    onResetDensity: () -> Unit,
    onSetRefreshRate: (String?) -> Unit
) {
    var customWidth by remember { mutableStateOf("") }
    var customHeight by remember { mutableStateOf("") }
    var customDpi by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Display Info Readout
        item {
            ModernCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Active Window Manager Metrics", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Icon(Icons.Default.AspectRatio, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Resolution: $displaySizeStr", color = CleanGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(text = "Density: $densityStr", color = AccentCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Resolution Presets (wm size)
        item {
            ModernCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Display Resolution Presets (wm size)", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Render at lower resolutions to boost gaming framerates or save battery", color = TextDim, fontSize = 10.sp)

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onApplySize(720, 1600) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("720p HD+", fontSize = 10.sp, color = TextMain)
                        }

                        OutlinedButton(
                            onClick = { onApplySize(1080, 2400) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("1080p FHD+", fontSize = 10.sp, color = TextMain)
                        }

                        OutlinedButton(
                            onClick = { onApplySize(1440, 3200) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("1440p QHD+", fontSize = 10.sp, color = TextMain)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customWidth,
                            onValueChange = { customWidth = it },
                            placeholder = { Text("Width", color = TextDim, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = customHeight,
                            onValueChange = { customHeight = it },
                            placeholder = { Text("Height", color = TextDim, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                val w = customWidth.toIntOrNull()
                                val h = customHeight.toIntOrNull()
                                if (w != null && h != null) onApplySize(w, h)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Apply", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    TextButton(onClick = onResetSize) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(14.dp), tint = WarningAmber)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset to Native Resolution", color = WarningAmber, fontSize = 11.sp)
                    }
                }
            }
        }

        // Density / DPI Presets (wm density)
        item {
            ModernCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Screen Density & Scale (wm density)", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Adjust UI element scaling and information density", color = TextDim, fontSize = 10.sp)

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(onClick = { onApplyDensity(360) }, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)) {
                            Text("360 Compact", fontSize = 9.sp, color = TextMain)
                        }
                        OutlinedButton(onClick = { onApplyDensity(400) }, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)) {
                            Text("400 Default", fontSize = 9.sp, color = TextMain)
                        }
                        OutlinedButton(onClick = { onApplyDensity(440) }, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)) {
                            Text("440 Large", fontSize = 9.sp, color = TextMain)
                        }
                        OutlinedButton(onClick = { onApplyDensity(480) }, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)) {
                            Text("480 Huge", fontSize = 9.sp, color = TextMain)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    TextButton(onClick = onResetDensity) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(14.dp), tint = WarningAmber)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset to Native Density", color = WarningAmber, fontSize = 11.sp)
                    }
                }
            }
        }

        // Refresh Rate Forcing
        item {
            ModernCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Display Refresh Rate Overrides", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Force peak and minimum refresh rate without LTPO throttling", color = TextDim, fontSize = 10.sp)

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { onSetRefreshRate("60.0") },
                            colors = ButtonDefaults.buttonColors(containerColor = BgCard, contentColor = TextMain),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("60 Hz", fontSize = 11.sp)
                        }

                        Button(
                            onClick = { onSetRefreshRate("90.0") },
                            colors = ButtonDefaults.buttonColors(containerColor = BgCard, contentColor = TextMain),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("90 Hz", fontSize = 11.sp)
                        }

                        Button(
                            onClick = { onSetRefreshRate("120.0") },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("120 Hz Peak", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { onSetRefreshRate(null) },
                            colors = ButtonDefaults.buttonColors(containerColor = BgCard, contentColor = TextDim),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Adaptive", fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 3. HIDDEN TWEAKS SECTION
// ==========================================
@Composable
private fun HiddenTweaksSection(
    settings: List<SystemSettingItem>,
    onToggleSetting: (SystemSettingItem, String) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetPrivateDns: (String, String?) -> Unit
) {
    var customDnsHost by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Animation Speed Presets Card
        item {
            ModernCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("UI Animation Speedup", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Icon(Icons.Default.Speed, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                    }
                    Text("Sets Window, Transition, and Animator scales in 1 tap", color = TextDim, fontSize = 10.sp)

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onSetSpeed(0.0f) },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("0.0x (Instant)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { onSetSpeed(0.5f) },
                            colors = ButtonDefaults.buttonColors(containerColor = CleanGreen, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("0.5x (Snappy)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { onSetSpeed(1.0f) },
                            colors = ButtonDefaults.buttonColors(containerColor = BgCard, contentColor = TextMain),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("1.0x (Default)", fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        // Private DNS Switcher Card
        item {
            ModernCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Private DNS Ad-Blocking Resolver", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Icon(Icons.Default.Security, contentDescription = null, tint = CleanGreen, modifier = Modifier.size(18.dp))
                    }
                    Text("Encrypted system-wide DNS blocking ads and trackers without a VPN", color = TextDim, fontSize = 10.sp)

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onSetPrivateDns("hostname", "dns.adguard.com") },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("AdGuard DNS", fontSize = 9.sp, color = CleanGreen, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { onSetPrivateDns("hostname", "one.one.one.one") },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cloudflare 1.1", fontSize = 9.sp, color = TextMain)
                        }

                        OutlinedButton(
                            onClick = { onSetPrivateDns("hostname", "dns.quad9.net") },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Quad9 Safe", fontSize = 9.sp, color = TextMain)
                        }

                        OutlinedButton(
                            onClick = { onSetPrivateDns("off", null) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Disable", fontSize = 9.sp, color = DangerRed)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = customDnsHost,
                            onValueChange = { customDnsHost = it },
                            placeholder = { Text("Custom Hostname (e.g. dns.nextdns.io)", color = TextDim, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )

                        Button(
                            onClick = {
                                if (customDnsHost.isNotBlank()) onSetPrivateDns("hostname", customDnsHost)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Set", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // System Knobs List
        items(settings.filter { it.category != "Animations" }, key = { it.key }) { item ->
            ModernCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = item.title, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(text = item.description, color = TextDim, fontSize = 10.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Key: ${item.key} • Value: ${item.currentValue}",
                            color = AccentCyan,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    if (item.type == "boolean") {
                        val isChecked = item.currentValue == "1"
                        Switch(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                onToggleSetting(item, if (checked) "1" else "0")
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = AccentCyan,
                                uncheckedThumbColor = TextDim,
                                uncheckedTrackColor = BgCard
                            )
                        )
                    } else {
                        Button(
                            onClick = {
                                val nextVal = if (item.currentValue == item.defaultValue) "3" else item.defaultValue
                                onToggleSetting(item, nextVal)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BgCard, contentColor = TextMain),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Toggle", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 4. PERMISSION HUB SECTION
// ==========================================
@Composable
private fun PermissionHubSection(
    apps: List<PowerUserApp>,
    onGrant: (PowerUserApp) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            ModernCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Privileged Power-User Dispatcher", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "One-tap grant for WRITE_SECURE_SETTINGS, DUMP, PACKAGE_USAGE_STATS & SYSTEM_ALERT_WINDOW",
                        color = TextDim,
                        fontSize = 11.sp
                    )
                }
            }
        }

        items(apps, key = { it.packageName }) { app ->
            ModernCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = app.appName, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(6.dp))
                            val (badgeText, badgeColor) = if (app.isInstalled) {
                                "INSTALLED" to CleanGreen
                            } else {
                                "NOT INSTALLED" to TextDim
                            }
                            Text(
                                text = badgeText,
                                color = badgeColor,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier
                                    .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                        Text(text = app.packageName, color = TextMuted, fontSize = 10.sp)
                        Text(text = app.description, color = TextDim, fontSize = 10.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = { onGrant(app) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (app.isInstalled) AccentCyan else BgCard,
                            contentColor = if (app.isInstalled) Color.Black else TextDim
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Grant All", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
