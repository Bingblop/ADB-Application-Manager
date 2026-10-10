package com.bloatware.bingblop.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bloatware.bingblop.data.model.PowerUserApp
import com.bloatware.bingblop.data.model.SettingNamespace
import com.bloatware.bingblop.data.model.SystemSettingItem
import com.bloatware.bingblop.data.model.PrivateDnsPreset
import com.bloatware.bingblop.data.model.DnsBenchmarkResult
import com.bloatware.bingblop.data.model.DnsPingStatus
import com.bloatware.bingblop.data.repository.AppRepository
import com.bloatware.bingblop.data.repository.SettingsRepository
import com.bloatware.bingblop.ui.components.CyberCard
import com.bloatware.bingblop.ui.components.StatusPill
import com.bloatware.bingblop.ui.theme.AccentCyan
import com.bloatware.bingblop.ui.theme.BgBase
import com.bloatware.bingblop.ui.theme.BgCard
import com.bloatware.bingblop.ui.theme.BgSurface
import com.bloatware.bingblop.ui.theme.BorderGlass
import com.bloatware.bingblop.ui.theme.CleanGreen
import com.bloatware.bingblop.ui.theme.SecondaryPurple
import com.bloatware.bingblop.ui.theme.TextDim
import com.bloatware.bingblop.ui.theme.TextMain
import com.bloatware.bingblop.ui.theme.TextMuted
import com.bloatware.bingblop.ui.theme.WarningOrange
import com.bloatware.bingblop.ui.theme.DangerRed
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    appRepository: AppRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    var currentNamespace by remember { mutableStateOf(SettingNamespace.GLOBAL) }
    var settingsList by remember { mutableStateOf<List<SystemSettingItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }

    var editingItem by remember { mutableStateOf<SystemSettingItem?>(null) }
    var editValueText by remember { mutableStateOf("") }

    // Display & App Grants state
    var blacklistedIcons by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedRefreshRate by remember { mutableStateOf("Default") }
    var customWidth by remember { mutableStateOf("1080") }
    var customHeight by remember { mutableStateOf("2400") }
    var customDpi by remember { mutableStateOf("420") }
    val powerApps = remember { appRepository.getKnownPowerUserApps() }
    var grantingAppPkg by remember { mutableStateOf<String?>(null) }

    // Private DNS & Speed Scanner state
    var privateDnsMode by remember { mutableStateOf("off") }
    var privateDnsSpec by remember { mutableStateOf("") }
    var customDnsInput by remember { mutableStateOf("") }
    val dnsPresets = remember { appRepository.getPrivateDnsPresets() }
    var isScanningDns by remember { mutableStateOf(false) }
    var dnsScanResults by remember { mutableStateOf<Map<String, DnsBenchmarkResult>>(emptyMap()) }
    var fastestDnsResult by remember { mutableStateOf<DnsBenchmarkResult?>(null) }
    var dnsCategoryFilter by remember { mutableStateOf("All") }
    var testingCustomDns by remember { mutableStateOf(false) }
    var customDnsLatency by remember { mutableStateOf<Long?>(null) }
    var singlePingingId by remember { mutableStateOf<String?>(null) }

    fun refreshSettings() {
        isLoading = true
        scope.launch {
            settingsList = settingsRepository.getSettings(currentNamespace)
            isLoading = false
        }
    }

    LaunchedEffect(currentNamespace) {
        refreshSettings()
    }

    val categories = remember(settingsList) {
        listOf("All") + settingsList.map { it.category }.distinct()
    }

    val filteredList = remember(settingsList, searchQuery, selectedCategory) {
        settingsList.filter {
            val matchesSearch = searchQuery.isEmpty() ||
                    it.title.contains(searchQuery, ignoreCase = true) ||
                    it.key.contains(searchQuery, ignoreCase = true)
            val matchesCategory = selectedCategory == "All" || it.category == selectedCategory
            matchesSearch && matchesCategory
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(horizontal = 14.dp)
    ) {
        // Quick 1-Tap Tweak Bar
        CyberCard(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                        Text("1-Tap Quick Tweaks", fontSize = 14.sp, fontWeight = FontWeight.Black, color = TextMain)
                    }
                    if (settingsRepository.canUndo()) {
                        StatusPill("UNDO AVAILABLE", SecondaryPurple)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val s1 = SystemSettingItem(SettingNamespace.GLOBAL, "animator_duration_scale", "", "", "Scale", "", "")
                                val s2 = SystemSettingItem(SettingNamespace.GLOBAL, "window_animation_scale", "", "", "Scale", "", "")
                                val s3 = SystemSettingItem(SettingNamespace.GLOBAL, "transition_animation_scale", "", "", "Scale", "", "")
                                settingsRepository.writeSetting(s1, "0.5")
                                settingsRepository.writeSetting(s2, "0.5")
                                settingsRepository.writeSetting(s3, "0.5")
                                Toast.makeText(context, "⚡ Snappy UI applied (0.5x speeds)", Toast.LENGTH_SHORT).show()
                                refreshSettings()
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("⚡ 0.5x Snappy", fontSize = 11.sp, color = AccentCyan, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val s1 = SystemSettingItem(SettingNamespace.GLOBAL, "animator_duration_scale", "", "", "Scale", "", "")
                                val s2 = SystemSettingItem(SettingNamespace.GLOBAL, "window_animation_scale", "", "", "Scale", "", "")
                                val s3 = SystemSettingItem(SettingNamespace.GLOBAL, "transition_animation_scale", "", "", "Scale", "", "")
                                settingsRepository.writeSetting(s1, "0.0")
                                settingsRepository.writeSetting(s2, "0.0")
                                settingsRepository.writeSetting(s3, "0.0")
                                Toast.makeText(context, "🚀 Instant UI applied (No animations)", Toast.LENGTH_SHORT).show()
                                refreshSettings()
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🚀 0.0x Instant", fontSize = 11.sp, color = CleanGreen, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val s1 = SystemSettingItem(SettingNamespace.GLOBAL, "animator_duration_scale", "", "", "Scale", "", "")
                                val s2 = SystemSettingItem(SettingNamespace.GLOBAL, "window_animation_scale", "", "", "Scale", "", "")
                                val s3 = SystemSettingItem(SettingNamespace.GLOBAL, "transition_animation_scale", "", "", "Scale", "", "")
                                settingsRepository.writeSetting(s1, "1.0")
                                settingsRepository.writeSetting(s2, "1.0")
                                settingsRepository.writeSetting(s3, "1.0")
                                Toast.makeText(context, "🔄 Default 1.0x restored", Toast.LENGTH_SHORT).show()
                                refreshSettings()
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🔄 1.0x Stock", fontSize = 11.sp, color = TextMuted)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Namespace Switcher Tabs
        val tabs = listOf("Global", "Secure", "System", "Display", "DNS Switcher", "Grants")
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = BgSurface,
            contentColor = AccentCyan,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = AccentCyan
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = {
                        selectedTab = index
                        selectedCategory = "All"
                        when (index) {
                            0 -> currentNamespace = SettingNamespace.GLOBAL
                            1 -> currentNamespace = SettingNamespace.SECURE
                            2 -> currentNamespace = SettingNamespace.SYSTEM
                            3 -> scope.launch { blacklistedIcons = appRepository.getBlacklistedIcons() }
                            4 -> scope.launch {
                                val (m, s) = appRepository.getPrivateDnsConfig()
                                privateDnsMode = m
                                privateDnsSpec = s
                            }
                        }
                    },
                    text = {
                        Text(
                            text = title,
                            fontSize = 10.sp,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (selectedTab < 3) {
            // Search Bar with Undo Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter setting keys...", color = TextDim, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AccentCyan) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted)
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("settings_search_field"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = BorderGlass,
                        focusedContainerColor = BgSurface,
                        unfocusedContainerColor = BgSurface,
                        focusedTextColor = TextMain,
                        unfocusedTextColor = TextMain
                    )
                )

                Button(
                    onClick = {
                        scope.launch {
                            val res = settingsRepository.undoLastChange()
                            Toast.makeText(context, res.fold(onSuccess = { it }, onFailure = { it.message ?: "Failed" }), Toast.LENGTH_SHORT).show()
                            refreshSettings()
                        }
                    },
                    enabled = settingsRepository.canUndo(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SecondaryPurple,
                        disabledContainerColor = BgCard
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("undo_settings_btn")
                ) {
                    Icon(Icons.Default.Undo, contentDescription = "Undo", tint = TextMain, modifier = Modifier.size(18.dp))
                }
            }

            // Category Filter Chips
            if (categories.size > 2) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(categories) { cat ->
                        val isSelected = selectedCategory == cat
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedCategory = cat },
                            label = {
                                Text(
                                    text = cat,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentCyan.copy(alpha = 0.2f),
                                selectedLabelColor = AccentCyan,
                                containerColor = BgCard,
                                labelColor = TextMuted
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = if (isSelected) AccentCyan else BorderGlass,
                                enabled = true,
                                selected = isSelected
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentCyan)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredList, key = { it.key }) { setting ->
                        CyberCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editingItem = setting
                                    editValueText = setting.currentValue
                                }
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = setting.title,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextMain
                                    )
                                    StatusPill(
                                        text = setting.currentValue.ifEmpty { "0" },
                                        color = AccentCyan
                                    )
                                }

                                Text(
                                    text = "${setting.namespace.name.lowercase()}:${setting.key}",
                                    fontSize = 11.sp,
                                    color = AccentCyan.copy(alpha = 0.8f),
                                    fontFamily = FontFamily.Monospace
                                )

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = setting.description,
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )

                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = setting.category,
                                        fontSize = 10.sp,
                                        color = TextDim
                                    )
                                    Text(
                                        text = "Tap to edit ✎",
                                        fontSize = 10.sp,
                                        color = AccentCyan
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(100.dp))
                    }
                }
            }
        } else if (selectedTab == 3) {
            // Display & Window Manager Customizer
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Screen Resolution (wm size)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                            Text("Overrides the virtual display framebuffer resolution via WindowManager.", fontSize = 11.sp, color = TextMuted)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = customWidth,
                                    onValueChange = { customWidth = it },
                                    label = { Text("Width (px)", fontSize = 10.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = customHeight,
                                    onValueChange = { customHeight = it },
                                    label = { Text("Height (px)", fontSize = 10.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        val w = customWidth.toIntOrNull() ?: 1080
                                        val h = customHeight.toIntOrNull() ?: 2400
                                        scope.launch {
                                            val res = appRepository.setScreenResolution(w, h)
                                            Toast.makeText(context, res.getOrDefault("Resolution applied"), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgBase),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Apply ${customWidth}x${customHeight}", fontSize = 11.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val res = appRepository.resetScreenResolution()
                                            Toast.makeText(context, res.getOrDefault("Resolution reset"), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Reset Default", fontSize = 11.sp, color = TextMuted)
                                }
                            }
                        }
                    }
                }

                item {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Display Density (wm density)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                            Text("Scales UI element sizing across the entire Android OS.", fontSize = 11.sp, color = TextMuted)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("360", "400", "440", "480", "560").forEach { dpiVal ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (customDpi == dpiVal) AccentCyan.copy(alpha = 0.2f) else BgSurface)
                                            .border(1.dp, if (customDpi == dpiVal) AccentCyan else BorderGlass, RoundedCornerShape(6.dp))
                                            .clickable { customDpi = dpiVal }
                                            .padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(dpiVal, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (customDpi == dpiVal) AccentCyan else TextMain)
                                    }
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        val dpi = customDpi.toIntOrNull() ?: 420
                                        scope.launch {
                                            val res = appRepository.setScreenDensity(dpi)
                                            Toast.makeText(context, res.getOrDefault("DPI applied"), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CleanGreen, contentColor = BgBase),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Apply $customDpi DPI", fontSize = 11.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val res = appRepository.resetScreenDensity()
                                            Toast.makeText(context, res.getOrDefault("DPI reset"), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Reset Default", fontSize = 11.sp, color = TextMuted)
                                }
                            }
                        }
                    }
                }

                item {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Force Peak & Min Refresh Rate", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                            Text("Enforces 120Hz/90Hz/60Hz high refresh rate locks.", fontSize = 11.sp, color = TextMuted)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("60Hz" to 60f, "90Hz" to 90f, "120Hz" to 120f).forEach { (label, fps) ->
                                    Button(
                                        onClick = {
                                            selectedRefreshRate = label
                                            scope.launch {
                                                val res = appRepository.setRefreshRate(fps)
                                                Toast.makeText(context, res.getOrDefault("Refresh rate set"), Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (selectedRefreshRate == label) AccentCyan else BgSurface,
                                            contentColor = if (selectedRefreshRate == label) BgBase else TextMain
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Status Bar Icon Blacklist (SystemUI Tuner)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                            Text("Hide clutter icons from the status bar (e.g. alarm, volume, bluetooth, hotspot).", fontSize = 11.sp, color = TextMuted)
                            val iconKeys = listOf(
                                "volume" to "Volume / Silent Icon",
                                "bluetooth" to "Bluetooth Icon",
                                "alarm_clock" to "Alarm Clock Icon",
                                "hotspot" to "Mobile Hotspot Icon",
                                "nfc" to "NFC Icon",
                                "location" to "Location Pin"
                            )
                            iconKeys.forEach { (key, label) ->
                                val isHidden = blacklistedIcons.contains(key)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(label, fontSize = 12.sp, color = TextMain)
                                    Switch(
                                        checked = isHidden,
                                        onCheckedChange = { hide ->
                                            scope.launch {
                                                appRepository.toggleStatusIconBlacklist(key, hide)
                                                blacklistedIcons = appRepository.getBlacklistedIcons()
                                            }
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = BgBase,
                                            checkedTrackColor = SecondaryPurple
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        } else if (selectedTab == 4) {
            // Private DNS & Latency Speed Scanner Manager
            val sortedPresets = remember(dnsPresets, dnsScanResults, dnsCategoryFilter) {
                val filtered = if (dnsCategoryFilter == "All") {
                    dnsPresets
                } else {
                    dnsPresets.filter { it.category.equals(dnsCategoryFilter, ignoreCase = true) }
                }
                if (dnsScanResults.isEmpty()) {
                    filtered
                } else {
                    filtered.sortedWith { a, b ->
                        val resA = dnsScanResults[a.id]?.latencyMs
                        val resB = dnsScanResults[b.id]?.latencyMs
                        when {
                            resA == null && resB == null -> 0
                            resA == null -> 1
                            resB == null -> -1
                            else -> resA.compareTo(resB)
                        }
                    }
                }
            }

            // Determine top 3 lowest latencies across all scanned
            val topRanks = remember(dnsScanResults) {
                dnsScanResults.values
                    .filter { it.latencyMs != null && it.latencyMs > 0 }
                    .sortedBy { it.latencyMs }
                    .take(3)
                    .mapIndexed { index, item -> item.presetId to (index + 1) }
                    .toMap()
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Active Private DNS Status Banner
                item {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Dns, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                                    Text("ACTIVE ENCRYPTED DNS (DoT)", fontSize = 11.sp, fontWeight = FontWeight.Black, color = TextMuted, letterSpacing = 1.sp)
                                }
                                StatusPill(
                                    when (privateDnsMode) {
                                        "hostname" -> "SECURED (DoT)"
                                        "opportunistic" -> "AUTO (DoT)"
                                        else -> "UNENCRYPTED"
                                    },
                                    when (privateDnsMode) {
                                        "hostname" -> CleanGreen
                                        "opportunistic" -> SecondaryPurple
                                        else -> WarningOrange
                                    }
                                )
                            }
                            if (privateDnsMode == "hostname") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = privateDnsSpec.ifEmpty { "Host not set" },
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentCyan,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    val activePing = dnsScanResults.values.firstOrNull { it.hostname == privateDnsSpec }?.latencyMs
                                    if (activePing != null) {
                                        StatusPill("⚡ $activePing ms", CleanGreen)
                                    }
                                }
                            } else if (privateDnsMode == "opportunistic") {
                                Text(
                                    text = "Opportunistic mode — uses upstream TLS resolver if supported",
                                    fontSize = 12.sp,
                                    color = TextMuted
                                )
                            } else {
                                Text(
                                    text = "Disabled — system is using default unencrypted ISP DNS",
                                    fontSize = 12.sp,
                                    color = DangerRed
                                )
                            }
                            Text(
                                text = "Android Private DNS encrypts lookups system-wide using TLS on port 853 with zero battery overhead or VPN latency.",
                                fontSize = 10.5.sp,
                                color = TextDim
                            )
                        }
                    }
                }

                // DNS Speed Scanner & Latency Benchmark Card
                item {
                    CyberCard(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = if (fastestDnsResult != null) CleanGreen else AccentCyan
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Speed, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                                    Text("DNS SPEED SCANNER & BENCHMARK", fontSize = 11.sp, fontWeight = FontWeight.Black, color = TextMuted, letterSpacing = 1.sp)
                                }
                                if (dnsScanResults.isNotEmpty()) {
                                    StatusPill("${dnsScanResults.size} SCANNED", CleanGreen)
                                }
                            }

                            Text(
                                text = "Benchmark real-time round-trip latency to Anycast DNS nodes to find the lowest-latency encrypted resolver for your Wi-Fi/carrier connection.",
                                fontSize = 11.sp,
                                color = TextMuted
                            )

                            // Action buttons: Scan & Apply Fastest
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (isScanningDns) return@Button
                                        isScanningDns = true
                                        scope.launch {
                                            val results = appRepository.benchmarkAllDnsPresets(dnsPresets)
                                            val map = results.associateBy { it.presetId }
                                            dnsScanResults = map
                                            fastestDnsResult = results.filter { it.latencyMs != null && it.latencyMs > 0 }.minByOrNull { it.latencyMs ?: Long.MAX_VALUE }
                                            isScanningDns = false
                                            if (fastestDnsResult != null) {
                                                Toast.makeText(context, "Fastest detected: ${fastestDnsResult?.title} (${fastestDnsResult?.latencyMs} ms)", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Benchmark finished", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    enabled = !isScanningDns,
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgBase),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (isScanningDns) {
                                        CircularProgressIndicator(color = BgBase, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Pinging Nodes...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    } else {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (dnsScanResults.isEmpty()) "Scan Fastest DNS" else "Re-scan Latencies", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (fastestDnsResult != null) {
                                    Button(
                                        onClick = {
                                            fastestDnsResult?.let { best ->
                                                scope.launch {
                                                    val res = appRepository.setPrivateDns("hostname", best.hostname)
                                                    Toast.makeText(context, "✓ Applied fastest: ${best.title}", Toast.LENGTH_SHORT).show()
                                                    val (m, s) = appRepository.getPrivateDnsConfig()
                                                    privateDnsMode = m
                                                    privateDnsSpec = s
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CleanGreen, contentColor = BgBase),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Apply Fastest (${fastestDnsResult?.latencyMs}ms)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            // Highlight banner if fastest detected
                            if (fastestDnsResult != null) {
                                val best = fastestDnsResult!!
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(CleanGreen.copy(alpha = 0.12f))
                                        .border(1.dp, CleanGreen.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text("🏆 FASTEST RESOLVER DETECTED", fontSize = 10.sp, fontWeight = FontWeight.Black, color = CleanGreen)
                                            }
                                            Text(best.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextMain)
                                            Text(best.hostname, fontSize = 10.sp, color = AccentCyan, fontFamily = FontFamily.Monospace)
                                        }
                                        StatusPill("⚡ ${best.latencyMs} ms", CleanGreen)
                                    }
                                }
                            }
                        }
                    }
                }

                // Category Filter Chips
                item {
                    val filterCategories = listOf("All", "Ultra Fast Anycast", "AdBlock & Privacy", "Threat Protection", "Stock Android")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(filterCategories) { cat ->
                            val isSelected = dnsCategoryFilter.equals(cat, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) AccentCyan.copy(alpha = 0.2f) else BgSurface)
                                    .border(1.dp, if (isSelected) AccentCyan else BorderGlass, RoundedCornerShape(8.dp))
                                    .clickable { dnsCategoryFilter = cat }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = cat,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) AccentCyan else TextDim
                                )
                            }
                        }
                    }
                }

                // Custom DoT Hostname Card with Pre-test Ping
                item {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Custom DoT Hostname", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextMain)
                                if (customDnsLatency != null) {
                                    StatusPill("⚡ $customDnsLatency ms", CleanGreen)
                                }
                            }
                            OutlinedTextField(
                                value = customDnsInput,
                                onValueChange = {
                                    customDnsInput = it
                                    customDnsLatency = null
                                },
                                placeholder = { Text("e.g. your-id.dns.nextdns.io or dns.quad9.net", fontSize = 11.sp, color = TextDim) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (customDnsInput.isBlank() || testingCustomDns) return@OutlinedButton
                                        testingCustomDns = true
                                        customDnsLatency = null
                                        scope.launch {
                                            val latency = appRepository.pingDnsHostOrIp(customDnsInput.trim())
                                            customDnsLatency = latency
                                            testingCustomDns = false
                                            if (latency != null) {
                                                Toast.makeText(context, "Ping: $latency ms", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Host unreachable / timed out", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    enabled = customDnsInput.isNotBlank() && !testingCustomDns,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (testingCustomDns) {
                                        CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = AccentCyan)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Pinging...", fontSize = 11.sp)
                                    } else {
                                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(14.dp), tint = AccentCyan)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Ping Host", fontSize = 11.sp, color = AccentCyan)
                                    }
                                }

                                Button(
                                    onClick = {
                                        if (customDnsInput.isBlank()) return@Button
                                        scope.launch {
                                            val res = appRepository.setPrivateDns("hostname", customDnsInput.trim())
                                            Toast.makeText(context, res.getOrDefault("DNS applied"), Toast.LENGTH_SHORT).show()
                                            val (m, s) = appRepository.getPrivateDnsConfig()
                                            privateDnsMode = m
                                            privateDnsSpec = s
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgBase),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Apply Hostname", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Section Header: Curated DNS Providers
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "ENCRYPTED RESOLVERS (${sortedPresets.size})",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = TextMuted,
                            letterSpacing = 1.sp
                        )
                        if (dnsScanResults.isNotEmpty()) {
                            Text("SORTED BY LATENCY", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                        }
                    }
                }

                // Preset Cards
                items(sortedPresets) { preset ->
                    val isCurrent = (preset.mode == "hostname" && privateDnsMode == "hostname" && privateDnsSpec == preset.hostname) ||
                            (preset.mode != "hostname" && privateDnsMode == preset.mode)
                    val benchmark = dnsScanResults[preset.id]
                    val rank = topRanks[preset.id]
                    val isPingingThis = singlePingingId == preset.id

                    CyberCard(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = when {
                            isCurrent -> CleanGreen
                            rank == 1 -> CleanGreen.copy(alpha = 0.6f)
                            else -> BorderGlass
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = preset.title,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCurrent) CleanGreen else TextMain
                                    )
                                    if (rank != null) {
                                        val rankText = when (rank) {
                                            1 -> "🥇 1st"
                                            2 -> "🥈 2nd"
                                            3 -> "🥉 3rd"
                                            else -> "#$rank"
                                        }
                                        StatusPill(rankText, CleanGreen)
                                    }
                                    if (isCurrent) {
                                        StatusPill("ACTIVE", CleanGreen)
                                    }
                                }

                                if (preset.hostname.isNotEmpty()) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(preset.hostname, fontSize = 10.sp, color = AccentCyan, fontFamily = FontFamily.Monospace)
                                        if (preset.primaryIp.isNotEmpty()) {
                                            Text("(${preset.primaryIp})", fontSize = 9.5.sp, color = TextDim, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                                Text(preset.description, fontSize = 10.sp, color = TextMuted)

                                // Latency status indicator
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (isPingingThis) {
                                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = AccentCyan)
                                        Text("Pinging...", fontSize = 9.5.sp, color = TextDim)
                                    } else if (benchmark != null) {
                                        when {
                                            benchmark.latencyMs == null -> {
                                                StatusPill("TIMEOUT", DangerRed)
                                            }
                                            benchmark.latencyMs < 40 -> {
                                                StatusPill("⚡ ${benchmark.latencyMs} ms", CleanGreen)
                                            }
                                            benchmark.latencyMs < 90 -> {
                                                StatusPill("${benchmark.latencyMs} ms", AccentCyan)
                                            }
                                            else -> {
                                                StatusPill("${benchmark.latencyMs} ms", WarningOrange)
                                            }
                                        }
                                    } else if (preset.primaryIp.isNotEmpty() || preset.hostname.isNotEmpty()) {
                                        // Small on-demand ping button
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(BgSurface)
                                                .border(1.dp, BorderGlass, RoundedCornerShape(6.dp))
                                                .clickable {
                                                    singlePingingId = preset.id
                                                    scope.launch {
                                                        val res = appRepository.benchmarkDnsPreset(preset)
                                                        dnsScanResults = dnsScanResults + (preset.id to res)
                                                        singlePingingId = null
                                                    }
                                                }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Icon(Icons.Default.Speed, contentDescription = null, tint = TextDim, modifier = Modifier.size(10.dp))
                                                Text("Ping", fontSize = 9.sp, color = TextMuted)
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = {
                                    scope.launch {
                                        val res = appRepository.setPrivateDns(preset.mode, preset.hostname)
                                        Toast.makeText(context, res.getOrDefault("DNS updated"), Toast.LENGTH_SHORT).show()
                                        val (m, s) = appRepository.getPrivateDnsConfig()
                                        privateDnsMode = m
                                        privateDnsSpec = s
                                    }
                                },
                                enabled = !isCurrent,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (preset.isEncrypted) AccentCyan else SecondaryPurple,
                                    contentColor = BgBase
                                )
                            ) {
                                Text(if (isCurrent) "Active" else "Set", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        } else {
            // Privileged App Grants (1-Tap Permissions)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Power-User ADB Permission Dispatcher", fontSize = 13.sp, fontWeight = FontWeight.Black, color = AccentCyan)
                            Text(
                                "Grant privileged WRITE_SECURE_SETTINGS, DUMP, and PACKAGE_USAGE_STATS permissions to system tools with a single click.",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                    }
                }

                items(powerApps) { app ->
                    val isGranting = grantingAppPkg == app.packageName
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(app.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain)
                                    Text(app.packageName, fontSize = 10.sp, color = AccentCyan, fontFamily = FontFamily.Monospace)
                                }
                                StatusPill("TOOL", SecondaryPurple)
                            }
                            Text(app.description, fontSize = 11.sp, color = TextMuted)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                app.permissions.forEach { perm ->
                                    Text("• ${perm.substringAfterLast('.')}", fontSize = 10.sp, color = TextDim, fontFamily = FontFamily.Monospace)
                                }
                            }
                            Button(
                                onClick = {
                                    if (isGranting) return@Button
                                    grantingAppPkg = app.packageName
                                    scope.launch {
                                        app.permissions.forEach { perm ->
                                            appRepository.grantPermission(app.packageName, perm)
                                        }
                                        grantingAppPkg = null
                                        Toast.makeText(context, "✓ Permissions granted to ${app.name}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = !isGranting,
                                colors = ButtonDefaults.buttonColors(containerColor = CleanGreen, contentColor = BgBase),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isGranting) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = BgBase, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Granting ADB Permissions...", fontSize = 11.sp)
                                } else {
                                    Text("Grant All Privileged Permissions", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        }
    }

    // Setting Edit BottomSheet
    editingItem?.let { item ->
        ModalBottomSheet(
            onDismissRequest = { editingItem = null },
            containerColor = BgSurface,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Edit: ${item.title}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    color = TextMain
                )
                Text(
                    text = "settings put ${item.namespace.name.lowercase()} ${item.key}",
                    fontSize = 12.sp,
                    color = AccentCyan,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(item.description, fontSize = 12.sp, color = TextMuted)

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = editValueText,
                    onValueChange = { editValueText = it },
                    label = { Text("Setting Value") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("edit_setting_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = BorderGlass,
                        focusedTextColor = TextMain,
                        unfocusedTextColor = TextMain
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { editingItem = null },
                        colors = ButtonDefaults.buttonColors(containerColor = BgCard, contentColor = TextMuted),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                val res = settingsRepository.writeSetting(item, editValueText)
                                Toast.makeText(context, res.fold(onSuccess = { it }, onFailure = { it.message ?: "Failed" }), Toast.LENGTH_SHORT).show()
                                editingItem = null
                                refreshSettings()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgBase),
                        modifier = Modifier.weight(1f).testTag("save_setting_btn")
                    ) {
                        Text("Apply Value", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
