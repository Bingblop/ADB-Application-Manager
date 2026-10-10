package com.bloatware.bingblop.ui.screens

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bloatware.bingblop.data.model.AppItem
import com.bloatware.bingblop.data.model.ComponentItem
import com.bloatware.bingblop.data.model.ComponentType
import com.bloatware.bingblop.data.model.DebloatLevel
import com.bloatware.bingblop.data.model.DebloatPackage
import com.bloatware.bingblop.data.model.DexOptMode
import com.bloatware.bingblop.data.model.DexOptResult
import com.bloatware.bingblop.data.model.BatchDexOptSummary
import com.bloatware.bingblop.data.repository.AppRepository
import com.bloatware.bingblop.ui.components.CyberCard
import com.bloatware.bingblop.ui.components.InteractiveStatBadge
import com.bloatware.bingblop.ui.components.StatusPill
import com.bloatware.bingblop.ui.theme.AccentCyan
import com.bloatware.bingblop.ui.theme.BgBase
import com.bloatware.bingblop.ui.theme.BgCard
import com.bloatware.bingblop.ui.theme.BgSurface
import com.bloatware.bingblop.ui.theme.BorderGlass
import com.bloatware.bingblop.ui.theme.CleanGreen
import com.bloatware.bingblop.ui.theme.SecondaryPurple
import com.bloatware.bingblop.ui.theme.StatusBloat
import com.bloatware.bingblop.ui.theme.StatusFrozen
import com.bloatware.bingblop.ui.theme.StatusRunning
import com.bloatware.bingblop.ui.theme.StatusSystem
import com.bloatware.bingblop.ui.theme.StatusUser
import com.bloatware.bingblop.ui.theme.TextDim
import com.bloatware.bingblop.ui.theme.TextMain
import com.bloatware.bingblop.ui.theme.TextMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class AppFilter { ALL, USER, SYSTEM, FROZEN, BLOAT, TRACKERS }
enum class AppSort { NAME, SIZE, TARGET_SDK }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    appRepository: AppRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var debloatList by remember { mutableStateOf<List<DebloatPackage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var currentFilter by remember { mutableStateOf(AppFilter.ALL) }
    var currentSort by remember { mutableStateOf(AppSort.NAME) }
    var isDebloatMode by remember { mutableStateOf(false) }

    var selectedApp by remember { mutableStateOf<AppItem?>(null) }
    var detailedPermissions by remember { mutableStateOf<List<String>>(emptyList()) }
    var detailedActivities by remember { mutableStateOf<List<String>>(emptyList()) }
    var detailedComponents by remember { mutableStateOf<List<ComponentItem>>(emptyList()) }
    var isLoadingComponents by remember { mutableStateOf(false) }
    var selectedSheetTab by remember { mutableIntStateOf(0) } // 0: Overview & Actions, 1: Component Disabler
    var componentFilterType by remember { mutableStateOf<ComponentType?>(null) }
    var componentSearchQuery by remember { mutableStateOf("") }
    var sheetActionMessage by remember { mutableStateOf<String?>(null) }

    // Active single operation feedback state
    var activeOperationType by remember { mutableStateOf<String?>(null) } // e.g. "freezing", "uninstalling", "clearing"
    var pendingPkgAction by remember { mutableStateOf<String?>(null) }

    // Batch operation feedback state
    var isBatchRunning by remember { mutableStateOf(false) }
    var batchProgressCurrent by remember { mutableStateOf(0) }
    var batchProgressTotal by remember { mutableStateOf(0) }
    var batchCurrentPkg by remember { mutableStateOf("") }
    var batchCurrentAction by remember { mutableStateOf("") }
    var isBatchCancelRequested by remember { mutableStateOf(false) }

    // Dex-opt state
    var appDexStatus by remember { mutableStateOf<String?>(null) }
    var selectedDexMode by remember { mutableStateOf(DexOptMode.SPEED_PROFILE) }
    var forceDexOpt by remember { mutableStateOf(false) }
    var secondaryDexOpt by remember { mutableStateOf(true) }
    var isDexOptBusy by remember { mutableStateOf(false) }
    var dexOptResultText by remember { mutableStateOf<String?>(null) }
    var showBatchDexOptDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = isDebloatMode) {
        isDebloatMode = false
    }

    fun refreshData() {
        isLoading = true
        scope.launch {
            val loadedApps = appRepository.getInstalledApps()
            apps = loadedApps
            debloatList = appRepository.getDebloatList(loadedApps)
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshData()
    }

    val filteredApps = remember(apps, searchQuery, currentFilter, currentSort) {
        val filtered = apps.filter { app ->
            val matchesSearch = searchQuery.isEmpty() ||
                    app.appName.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)

            val matchesFilter = when (currentFilter) {
                AppFilter.ALL -> true
                AppFilter.USER -> !app.isSystemApp
                AppFilter.SYSTEM -> app.isSystemApp
                AppFilter.FROZEN -> !app.isEnabled
                AppFilter.BLOAT -> app.isBloatware
                AppFilter.TRACKERS -> app.trackers.isNotEmpty()
            }
            matchesSearch && matchesFilter
        }

        when (currentSort) {
            AppSort.NAME -> filtered.sortedBy { it.appName.lowercase() }
            AppSort.SIZE -> filtered.sortedByDescending { it.appSize }
            AppSort.TARGET_SDK -> filtered.sortedByDescending { it.targetSdk }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(horizontal = 14.dp)
    ) {
        // Global Operation Progress Banner (when executing ADB commands)
        AnimatedVisibility(
            visible = pendingPkgAction != null && activeOperationType != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            CyberCard(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                borderColor = AccentCyan
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = AccentCyan,
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Executing ADB: $activeOperationType",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMain
                            )
                        }
                        Text(
                            text = pendingPkgAction ?: "",
                            fontSize = 11.sp,
                            color = AccentCyan,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = AccentCyan,
                        trackColor = BgSurface
                    )
                }
            }
        }

        // Interactive Quick Stat Badges: 1-Tap Filter Cards
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InteractiveStatBadge(
                title = "Total",
                value = apps.size.toString(),
                isSelected = currentFilter == AppFilter.ALL && !isDebloatMode,
                modifier = Modifier.weight(1f)
            ) {
                isDebloatMode = false
                currentFilter = AppFilter.ALL
            }
            InteractiveStatBadge(
                title = "Active",
                value = apps.count { it.isEnabled }.toString(),
                isSelected = currentFilter == AppFilter.USER && !isDebloatMode,
                color = StatusRunning,
                modifier = Modifier.weight(1f)
            ) {
                isDebloatMode = false
                currentFilter = AppFilter.USER
            }
            InteractiveStatBadge(
                title = "Frozen",
                value = apps.count { !it.isEnabled }.toString(),
                isSelected = currentFilter == AppFilter.FROZEN && !isDebloatMode,
                color = StatusFrozen,
                modifier = Modifier.weight(1f)
            ) {
                isDebloatMode = false
                currentFilter = AppFilter.FROZEN
            }
            InteractiveStatBadge(
                title = "Bloatware",
                value = apps.count { it.isBloatware }.toString(),
                isSelected = isDebloatMode,
                color = StatusBloat,
                modifier = Modifier.weight(1f)
            ) {
                isDebloatMode = true
            }
        }

        // View Mode Switcher
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(BgSurface)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = { isDebloatMode = false },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!isDebloatMode) AccentCyan else Color.Transparent,
                    contentColor = if (!isDebloatMode) BgBase else TextMuted
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f).testTag("tab_all_apps")
            ) {
                Text("All Packages (${apps.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            Button(
                onClick = { isDebloatMode = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDebloatMode) StatusBloat else Color.Transparent,
                    contentColor = if (isDebloatMode) TextMain else TextMuted
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f).testTag("tab_debloater")
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("UAD Debloater (${debloatList.count { it.isInstalled }})", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (isDebloatMode) {
            // Enhanced Debloater View with Live Batch Progress Indicators
            DebloaterView(
                debloatList = debloatList,
                isBatchRunning = isBatchRunning,
                batchProgressCurrent = batchProgressCurrent,
                batchProgressTotal = batchProgressTotal,
                batchCurrentPkg = batchCurrentPkg,
                batchCurrentAction = batchCurrentAction,
                onToggleSelect = { pkg ->
                    debloatList = debloatList.map {
                        if (it.packageName == pkg) it.copy(isSelected = !it.isSelected) else it
                    }
                },
                onSelectRecommendedOnly = {
                    debloatList = debloatList.map {
                        it.copy(isSelected = it.isInstalled && it.level == DebloatLevel.RECOMMENDED && !it.isFrozen)
                    }
                },
                onSelectAll = {
                    debloatList = debloatList.map { it.copy(isSelected = it.isInstalled && !it.isFrozen) }
                },
                onDeselectAll = {
                    debloatList = debloatList.map { it.copy(isSelected = false) }
                },
                onBatchFreeze = {
                    val targets = debloatList.filter { it.isSelected }.map { it.packageName }
                    if (targets.isEmpty()) return@DebloaterView

                    isBatchRunning = true
                    batchProgressTotal = targets.size
                    batchProgressCurrent = 0
                    batchCurrentAction = "Freezing (pm disable-user)"

                    scope.launch {
                        targets.forEachIndexed { index, pkg ->
                            batchProgressCurrent = index + 1
                            batchCurrentPkg = pkg
                            appRepository.freezeApp(pkg)
                            delay(120) // Smooth progress cadence
                        }
                        isBatchRunning = false
                        Toast.makeText(context, "Successfully froze ${targets.size} bloatware packages", Toast.LENGTH_SHORT).show()
                        refreshData()
                    }
                },
                onBatchUninstall = {
                    val targets = debloatList.filter { it.isSelected }.map { it.packageName }
                    if (targets.isEmpty()) return@DebloaterView

                    isBatchRunning = true
                    batchProgressTotal = targets.size
                    batchProgressCurrent = 0
                    batchCurrentAction = "Uninstalling (pm uninstall --user 0)"

                    scope.launch {
                        targets.forEachIndexed { index, pkg ->
                            batchProgressCurrent = index + 1
                            batchCurrentPkg = pkg
                            appRepository.uninstallApp(pkg)
                            delay(120)
                        }
                        isBatchRunning = false
                        Toast.makeText(context, "Successfully uninstalled ${targets.size} packages", Toast.LENGTH_SHORT).show()
                        refreshData()
                    }
                },
                onBatchDexOpt = {
                    showBatchDexOptDialog = true
                },
                onCancelBatch = {
                    isBatchCancelRequested = true
                }
            )
        } else {
            // Live Batch Progress Indicator Banner on Main Screen
            AnimatedVisibility(visible = isBatchRunning) {
                CyberCard(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), borderColor = AccentCyan) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$batchCurrentAction ($batchProgressCurrent of $batchProgressTotal)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentCyan
                            )
                            IconButton(
                                onClick = { isBatchCancelRequested = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Cancel batch", tint = StatusBloat)
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = { if (batchProgressTotal > 0) batchProgressCurrent.toFloat() / batchProgressTotal.toFloat() else 0f },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = CleanGreen,
                            trackColor = BgSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = batchCurrentPkg,
                            fontSize = 9.sp,
                            color = TextDim,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Convenient Search Bar & Sort Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search name or package...", color = TextDim, fontSize = 13.sp) },
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
                        .testTag("app_search_field"),
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

                // Quick Sort Button
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BgSurface)
                        .border(1.dp, BorderGlass, RoundedCornerShape(12.dp))
                        .clickable {
                            currentSort = when (currentSort) {
                                AppSort.NAME -> AppSort.SIZE
                                AppSort.SIZE -> AppSort.TARGET_SDK
                                AppSort.TARGET_SDK -> AppSort.NAME
                            }
                            val sortName = when (currentSort) {
                                AppSort.NAME -> "Name"
                                AppSort.SIZE -> "Size"
                                AppSort.TARGET_SDK -> "Target SDK"
                            }
                            Toast.makeText(context, "Sorted by $sortName", Toast.LENGTH_SHORT).show()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Sort, contentDescription = "Sort", tint = AccentCyan, modifier = Modifier.size(18.dp))
                        Text(
                            text = when (currentSort) {
                                AppSort.NAME -> "A-Z"
                                AppSort.SIZE -> "Size"
                                AppSort.TARGET_SDK -> "SDK"
                            },
                            fontSize = 9.sp,
                            color = TextMuted,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Batch Dex-Opt Button (Fast Action for all active user apps or filtered list)
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BgSurface)
                        .border(1.dp, BorderGlass, RoundedCornerShape(12.dp))
                        .clickable {
                            val targets = filteredApps.filter { it.isEnabled && !it.isSystemApp }.map { it.packageName }
                                .ifEmpty { filteredApps.take(15).map { it.packageName } }
                            if (targets.isNotEmpty()) {
                                debloatList = debloatList.map { it.copy(isSelected = it.packageName in targets) }
                                showBatchDexOptDialog = true
                            } else {
                                Toast.makeText(context, "No apps available to optimize", Toast.LENGTH_SHORT).show()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Bolt, contentDescription = "Batch Dex-Opt", tint = CleanGreen, modifier = Modifier.size(18.dp))
                        Text(
                            text = "DexOpt",
                            fontSize = 9.sp,
                            color = CleanGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Quick Filter Chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(AppFilter.values()) { filter ->
                    val isSelected = currentFilter == filter
                    FilterChip(
                        selected = isSelected,
                        onClick = { currentFilter = filter },
                        label = {
                            Text(
                                text = when (filter) {
                                    AppFilter.ALL -> "All (${apps.size})"
                                    AppFilter.USER -> "User (${apps.count { !it.isSystemApp }})"
                                    AppFilter.SYSTEM -> "System (${apps.count { it.isSystemApp }})"
                                    AppFilter.FROZEN -> "Frozen (${apps.count { !it.isEnabled }})"
                                    AppFilter.BLOAT -> "Bloat (${apps.count { it.isBloatware }})"
                                    AppFilter.TRACKERS -> "Trackers (${apps.count { it.trackers.isNotEmpty() }})"
                                },
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

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentCyan)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val isRowBusy = pendingPkgAction == app.packageName

                        AppListItemCard(
                            app = app,
                            isBusy = isRowBusy,
                            onClick = {
                                selectedApp = app
                                detailedPermissions = emptyList()
                                detailedActivities = emptyList()
                                detailedComponents = emptyList()
                                isLoadingComponents = true
                                selectedSheetTab = 0
                                componentSearchQuery = ""
                                componentFilterType = null
                                appDexStatus = "Checking ART status..."
                                dexOptResultText = null
                                scope.launch {
                                    val (perms, acts) = appRepository.getAppDetails(app.packageName)
                                    detailedPermissions = perms
                                    detailedActivities = acts
                                    val comps = appRepository.getAppComponents(app.packageName)
                                    detailedComponents = comps
                                    isLoadingComponents = false
                                    appDexStatus = appRepository.getDexOptStatus(app.packageName)
                                }
                            },
                            onQuickFreezeToggle = {
                                if (pendingPkgAction != null) return@AppListItemCard
                                pendingPkgAction = app.packageName
                                activeOperationType = if (app.isEnabled) "Freezing package" else "Unfreezing package"

                                scope.launch {
                                    val res = if (app.isEnabled) {
                                        appRepository.freezeApp(app.packageName)
                                    } else {
                                        appRepository.unfreezeApp(app.packageName)
                                    }
                                    pendingPkgAction = null
                                    activeOperationType = null
                                    Toast.makeText(context, res.fold(onSuccess = { it }, onFailure = { it.message ?: "Failed" }), Toast.LENGTH_SHORT).show()
                                    refreshData()
                                }
                            },
                            onQuickLaunch = {
                                val launched = appRepository.launchApp(app.packageName)
                                if (!launched) {
                                    Toast.makeText(context, "No launcher activity found for ${app.appName}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(100.dp))
                    }
                }
            }
        }
    }

    // Batch DEX-Opt Confirmation and Profile Selection Dialog
    if (showBatchDexOptDialog) {
        val targets = debloatList.filter { it.isSelected }.map { it.packageName }.ifEmpty {
            filteredApps.filter { it.isEnabled && !it.isSystemApp }.map { it.packageName }.take(20)
        }
        var dialogMode by remember { mutableStateOf(DexOptMode.SPEED_PROFILE) }
        var dialogForce by remember { mutableStateOf(false) }
        var dialogSecondary by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showBatchDexOptDialog = false },
            containerColor = BgSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = AccentCyan)
                    Text("Batch DEX Optimization", color = TextMain, fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Recompile ${targets.size} applications using ART Ahead-Of-Time compilation.",
                        fontSize = 12.sp,
                        color = TextMuted
                    )

                    Text("SELECT COMPILATION PROFILE", fontSize = 10.sp, fontWeight = FontWeight.Black, color = TextMuted, letterSpacing = 1.sp)

                    listOf(
                        DexOptMode.SPEED_PROFILE,
                        DexOptMode.SPEED,
                        DexOptMode.SPACE_PROFILE,
                        DexOptMode.QUICKEN,
                        DexOptMode.RESET
                    ).forEach { mode ->
                        val isSel = dialogMode == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) AccentCyan.copy(alpha = 0.12f) else BgCard)
                                .border(width = 1.dp, color = if (isSel) AccentCyan else BorderGlass, shape = RoundedCornerShape(8.dp))
                                .clickable { dialogMode = mode }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            RadioButton(
                                selected = isSel,
                                onClick = { dialogMode = mode },
                                colors = RadioButtonDefaults.colors(selectedColor = AccentCyan, unselectedColor = TextMuted)
                            )
                            Column {
                                Text(mode.title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isSel) AccentCyan else TextMain)
                                Text(mode.description, fontSize = 9.sp, color = TextDim, maxLines = 1)
                            }
                        }
                    }

                    if (!dialogMode.isReset) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Force recompile (-f)", fontSize = 11.sp, color = TextMain)
                            Switch(
                                checked = dialogForce,
                                onCheckedChange = { dialogForce = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = BgBase, checkedTrackColor = AccentCyan)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Secondary DEX (--secondary-dex)", fontSize = 11.sp, color = TextMain)
                            Switch(
                                checked = dialogSecondary,
                                onCheckedChange = { dialogSecondary = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = BgBase, checkedTrackColor = AccentCyan)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBatchDexOptDialog = false
                        if (targets.isEmpty()) return@Button

                        isBatchRunning = true
                        isBatchCancelRequested = false
                        batchProgressTotal = targets.size
                        batchProgressCurrent = 0
                        batchCurrentAction = "DEX-Opt (${dialogMode.arg})"

                        scope.launch {
                            val summary = appRepository.optimizeAppBatch(
                                packages = targets,
                                mode = dialogMode,
                                force = dialogForce,
                                compileSecondaryDex = dialogSecondary,
                                onProgress = { cur, tot, pkg ->
                                    batchProgressCurrent = cur
                                    batchProgressTotal = tot
                                    batchCurrentPkg = pkg
                                },
                                isCancelled = { isBatchCancelRequested }
                            )
                            isBatchRunning = false
                            Toast.makeText(
                                context,
                                if (summary.cancelled) {
                                    "Batch DEX-Opt cancelled (${summary.succeeded} of ${summary.total} compiled)"
                                } else {
                                    "✓ Batch DEX-Opt complete: ${summary.succeeded} succeeded, ${summary.failed} failed"
                                },
                                Toast.LENGTH_LONG
                            ).show()
                            refreshData()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CleanGreen, contentColor = BgBase)
                ) {
                    Text("Start Optimization (${targets.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showBatchDexOptDialog = false }) {
                    Text("Cancel", color = TextMuted, fontSize = 12.sp)
                }
            }
        )
    }

    // App Inspector BottomSheet with Live Action Feedback
    selectedApp?.let { app ->
        val isSheetBusy = pendingPkgAction == app.packageName

        ModalBottomSheet(
            onDismissRequest = {
                if (!isSheetBusy) {
                    selectedApp = null
                    sheetActionMessage = null
                    detailedPermissions = emptyList()
                    detailedActivities = emptyList()
                }
            },
            containerColor = BgSurface,
            scrimColor = Color.Black.copy(alpha = 0.7f),
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.appName,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = TextMain
                        )
                        Text(
                            text = app.packageName,
                            fontSize = 12.sp,
                            color = AccentCyan,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    StatusPill(
                        text = if (app.isEnabled) "ACTIVE" else "FROZEN",
                        color = if (app.isEnabled) StatusRunning else StatusFrozen
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Segmented Tab Switcher: Overview vs DEX-Opt vs Component Disabler
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(BgCard)
                        .border(width = 1.dp, color = BorderGlass, shape = RoundedCornerShape(10.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("Overview", "⚡ DEX-Opt", "Components (${detailedComponents.size})").forEachIndexed { index, title ->
                        val isSelected = selectedSheetTab == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) AccentCyan else Color.Transparent)
                                .clickable { selectedSheetTab = index }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium,
                                color = if (isSelected) BgBase else TextMuted
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (selectedSheetTab == 0) {
                    // Metadata Details Card
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("Version / Code:", fontSize = 11.sp, color = TextMuted)
                                Text("${app.versionName} (${app.versionCode})", fontSize = 11.sp, color = TextMain)
                            }
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("Target SDK / Min SDK:", fontSize = 11.sp, color = TextMuted)
                                Text("API ${app.targetSdk} / API ${app.minSdk}", fontSize = 11.sp, color = TextMain)
                            }
                            if (app.appSize > 0L) {
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("APK File Size:", fontSize = 11.sp, color = TextMuted)
                                    Text(Formatter.formatFileSize(context, app.appSize), fontSize = 11.sp, color = AccentCyan)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("UID / Package Type:", fontSize = 11.sp, color = TextMuted)
                                Text("${app.uid} • ${if (app.isSystemApp) "System" else "User"}", fontSize = 11.sp, color = TextMain)
                            }
                            if (app.trackers.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("Exodus Privacy Trackers:", fontSize = 11.sp, color = StatusBloat)
                                    Text(app.trackers.joinToString(", "), fontSize = 11.sp, color = StatusBloat, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (app.isBloatware) {
                                Text("UAD: ${app.bloatDescription ?: "Bloatware component"}", fontSize = 11.sp, color = StatusBloat, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Active Command Execution Feedback Card inside Sheet
                    AnimatedVisibility(visible = isSheetBusy) {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            CyberCard(modifier = Modifier.fillMaxWidth(), borderColor = AccentCyan) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentCyan, strokeWidth = 2.dp)
                                    Column {
                                        Text(
                                            text = "Executing ADB: $activeOperationType...",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextMain
                                        )
                                        Text(
                                            text = "Please wait while command completes",
                                            fontSize = 10.sp,
                                            color = TextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (detailedPermissions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "DECLARED PERMISSIONS (${detailedPermissions.size})",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted
                        )
                        detailedPermissions.take(4).forEach { p ->
                            Text("• ${p.substringAfterLast('.')}", fontSize = 10.sp, color = TextDim, fontFamily = FontFamily.Monospace)
                        }
                        if (detailedPermissions.size > 4) {
                            Text("...and ${detailedPermissions.size - 4} more", fontSize = 10.sp, color = AccentCyan)
                        }
                    }

                    sheetActionMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = msg,
                            color = AccentCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text("PRIVILEGED ACTIONS", fontSize = 11.sp, fontWeight = FontWeight.Black, color = TextMuted, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    // Action Buttons Grid
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (isSheetBusy) return@Button
                                pendingPkgAction = app.packageName
                                activeOperationType = if (app.isEnabled) "pm disable-user" else "pm enable"

                                scope.launch {
                                    val res = if (app.isEnabled) {
                                        appRepository.freezeApp(app.packageName)
                                    } else {
                                        appRepository.unfreezeApp(app.packageName)
                                    }
                                    pendingPkgAction = null
                                    activeOperationType = null
                                    sheetActionMessage = res.fold(onSuccess = { it }, onFailure = { it.message ?: "Failed" })
                                    refreshData()
                                }
                            },
                            enabled = !isSheetBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (app.isEnabled) StatusFrozen else StatusRunning,
                                contentColor = BgBase
                            ),
                            modifier = Modifier.weight(1f).testTag("action_freeze_toggle")
                        ) {
                            if (isSheetBusy && activeOperationType?.contains("disable") == true) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgBase, strokeWidth = 2.dp)
                            } else {
                                Text(if (app.isEnabled) "Freeze (Disable)" else "Unfreeze (Enable)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = {
                                if (isSheetBusy) return@Button
                                pendingPkgAction = app.packageName
                                activeOperationType = "pm uninstall --user 0"

                                scope.launch {
                                    val res = appRepository.uninstallApp(app.packageName)
                                    pendingPkgAction = null
                                    activeOperationType = null
                                    sheetActionMessage = res.fold(onSuccess = { it }, onFailure = { it.message ?: "Failed" })
                                    refreshData()
                                }
                            },
                            enabled = !isSheetBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = StatusBloat, contentColor = TextMain),
                            modifier = Modifier.weight(1f).testTag("action_uninstall")
                        ) {
                            if (isSheetBusy && activeOperationType?.contains("uninstall") == true) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextMain, strokeWidth = 2.dp)
                            } else {
                                Text("Uninstall (--user 0)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (isSheetBusy) return@OutlinedButton
                                pendingPkgAction = app.packageName
                                activeOperationType = "pm clear"

                                scope.launch {
                                    val res = appRepository.clearAppData(app.packageName)
                                    pendingPkgAction = null
                                    activeOperationType = null
                                    sheetActionMessage = res.fold(onSuccess = { it }, onFailure = { it.message ?: "Failed" })
                                }
                            },
                            enabled = !isSheetBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isSheetBusy && activeOperationType?.contains("clear") == true) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = AccentCyan, strokeWidth = 2.dp)
                            } else {
                                Text("Clear Data", fontSize = 11.sp, color = TextMain)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                if (isSheetBusy) return@OutlinedButton
                                pendingPkgAction = app.packageName
                                activeOperationType = "cmd package install-existing"

                                scope.launch {
                                    val res = appRepository.reinstallApp(app.packageName)
                                    pendingPkgAction = null
                                    activeOperationType = null
                                    sheetActionMessage = res.fold(onSuccess = { it }, onFailure = { it.message ?: "Failed" })
                                    refreshData()
                                }
                            },
                            enabled = !isSheetBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isSheetBusy && activeOperationType?.contains("install-existing") == true) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = AccentCyan, strokeWidth = 2.dp)
                            } else {
                                Text("Reinstall OEM", fontSize = 11.sp, color = TextMain)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                appRepository.launchApp(app.packageName)
                            },
                            enabled = !isSheetBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Launch", fontSize = 11.sp, color = AccentCyan)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { selectedSheetTab = 1 },
                        enabled = !isSheetBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Configure ART DEX-Opt (${appDexStatus ?: "Check"})", fontSize = 11.sp, color = AccentCyan)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { appRepository.openAppDetails(app.packageName) },
                        enabled = !isSheetBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open System App Settings", fontSize = 11.sp, color = TextMuted)
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                } else if (selectedSheetTab == 1) {
                    // DEX Optimization Tab (ART Compiler)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CyberCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Current ART Compilation Status", fontSize = 11.sp, color = TextMuted)
                                        Text(
                                            text = appDexStatus ?: "Checking...",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Black,
                                            color = when {
                                                appDexStatus?.contains("speed-profile", ignoreCase = true) == true -> CleanGreen
                                                appDexStatus?.contains("speed", ignoreCase = true) == true -> AccentCyan
                                                appDexStatus?.contains("verify", ignoreCase = true) == true -> StatusFrozen
                                                else -> TextMain
                                            },
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                appDexStatus = "Checking..."
                                                appDexStatus = appRepository.getDexOptStatus(app.packageName)
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentCyan, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Text(
                                    text = "ART Ahead-Of-Time compilation translates DEX bytecode into machine code. Baseline profiles ensure hot startup paths run at native speed.",
                                    fontSize = 10.sp,
                                    color = TextDim
                                )
                            }
                        }

                        Text("COMPILATION PROFILE", fontSize = 10.sp, fontWeight = FontWeight.Black, color = TextMuted, letterSpacing = 1.sp)

                        DexOptMode.values().forEach { mode ->
                            val isSelected = selectedDexMode == mode
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) AccentCyan.copy(alpha = 0.12f) else BgCard)
                                    .border(width = 1.dp, color = if (isSelected) AccentCyan else BorderGlass, shape = RoundedCornerShape(8.dp))
                                    .clickable { selectedDexMode = mode }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedDexMode = mode },
                                    colors = RadioButtonDefaults.colors(selectedColor = AccentCyan, unselectedColor = TextMuted)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = mode.title,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) AccentCyan else TextMain
                                    )
                                    Text(
                                        text = mode.description,
                                        fontSize = 9.sp,
                                        color = TextDim
                                    )
                                }
                            }
                        }

                        if (!selectedDexMode.isReset) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Force Recompile (-f)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMain)
                                    Text("Recompiles even if already compiled", fontSize = 10.sp, color = TextMuted)
                                }
                                Switch(
                                    checked = forceDexOpt,
                                    onCheckedChange = { forceDexOpt = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = BgBase, checkedTrackColor = AccentCyan)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Compile Secondary DEX", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMain)
                                    Text("Optimize dynamic split modules (--secondary-dex)", fontSize = 10.sp, color = TextMuted)
                                }
                                Switch(
                                    checked = secondaryDexOpt,
                                    onCheckedChange = { secondaryDexOpt = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = BgBase, checkedTrackColor = AccentCyan)
                                )
                            }
                        }

                        Button(
                            onClick = {
                                if (isDexOptBusy) return@Button
                                isDexOptBusy = true
                                dexOptResultText = null
                                scope.launch {
                                    val res = appRepository.optimizeApp(
                                        packageName = app.packageName,
                                        mode = selectedDexMode,
                                        force = forceDexOpt,
                                        compileSecondaryDex = secondaryDexOpt
                                    )
                                    isDexOptBusy = false
                                    dexOptResultText = if (res.success) {
                                        "✓ Compiled (${res.durationMs}ms): ${res.output.lines().firstOrNull() ?: "Success"}"
                                    } else {
                                        "✕ ${res.output}"
                                    }
                                    appDexStatus = appRepository.getDexOptStatus(app.packageName)
                                }
                            },
                            enabled = !isDexOptBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedDexMode.isReset) StatusBloat else CleanGreen,
                                contentColor = if (selectedDexMode.isReset) TextMain else BgBase
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("run_dexopt_btn")
                        ) {
                            if (isDexOptBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgBase, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Compiling with ART...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (selectedDexMode.isReset) "Reset App Compilation" else "Run DEX Optimization",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        dexOptResultText?.let { out ->
                            CyberCard(
                                modifier = Modifier.fillMaxWidth(),
                                borderColor = if (out.startsWith("✓")) CleanGreen else StatusBloat
                            ) {
                                Text(
                                    text = out,
                                    fontSize = 11.sp,
                                    color = if (out.startsWith("✓")) CleanGreen else StatusBloat,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                    }
                } else {
                    // Component Disabler Tab
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Selective Component Disabler (pm disable <component>)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentCyan
                        )
                        Text(
                            text = "Disable specific background services or broadcast receivers without disabling the entire app.",
                            fontSize = 11.sp,
                            color = TextDim
                        )

                        // Component filter chips
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            item {
                                FilterChip(
                                    selected = componentFilterType == null,
                                    onClick = { componentFilterType = null },
                                    label = { Text("All (${detailedComponents.size})", fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentCyan,
                                        selectedLabelColor = BgBase
                                    )
                                )
                            }
                            item {
                                val sCount = detailedComponents.count { it.type == ComponentType.SERVICE }
                                FilterChip(
                                    selected = componentFilterType == ComponentType.SERVICE,
                                    onClick = { componentFilterType = ComponentType.SERVICE },
                                    label = { Text("Services ($sCount)", fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentCyan,
                                        selectedLabelColor = BgBase
                                    )
                                )
                            }
                            item {
                                val rCount = detailedComponents.count { it.type == ComponentType.RECEIVER }
                                FilterChip(
                                    selected = componentFilterType == ComponentType.RECEIVER,
                                    onClick = { componentFilterType = ComponentType.RECEIVER },
                                    label = { Text("Receivers ($rCount)", fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SecondaryPurple,
                                        selectedLabelColor = TextMain
                                    )
                                )
                            }
                            item {
                                val aCount = detailedComponents.count { it.type == ComponentType.ACTIVITY }
                                FilterChip(
                                    selected = componentFilterType == ComponentType.ACTIVITY,
                                    onClick = { componentFilterType = ComponentType.ACTIVITY },
                                    label = { Text("Activities ($aCount)", fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CleanGreen,
                                        selectedLabelColor = BgBase
                                    )
                                )
                            }
                        }

                        // Search component
                        OutlinedTextField(
                            value = componentSearchQuery,
                            onValueChange = { componentSearchQuery = it },
                            placeholder = { Text("Search component name...", fontSize = 11.sp, color = TextDim) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = BorderGlass,
                                focusedContainerColor = BgCard,
                                unfocusedContainerColor = BgCard
                            )
                        )

                        if (isLoadingComponents) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = AccentCyan, modifier = Modifier.size(24.dp))
                            }
                        } else {
                            val filteredComponents = detailedComponents.filter { cmp ->
                                val typeMatches = componentFilterType == null || cmp.type == componentFilterType
                                val queryMatches = componentSearchQuery.isBlank() || cmp.name.contains(componentSearchQuery, ignoreCase = true)
                                typeMatches && queryMatches
                            }

                            if (filteredComponents.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No components match filter criteria", fontSize = 12.sp, color = TextMuted)
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(280.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        items(filteredComponents, key = { it.name }) { comp ->
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(BgCard)
                                                    .border(width = 1.dp, color = BorderGlass, shape = RoundedCornerShape(8.dp))
                                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                        ) {
                                                            val badgeColor = when (comp.type) {
                                                                ComponentType.SERVICE -> AccentCyan
                                                                ComponentType.RECEIVER -> SecondaryPurple
                                                                ComponentType.ACTIVITY -> CleanGreen
                                                            }
                                                            Box(
                                                                modifier = Modifier
                                                                    .clip(RoundedCornerShape(4.dp))
                                                                    .background(badgeColor.copy(alpha = 0.2f))
                                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                                            ) {
                                                                Text(comp.type.label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = badgeColor)
                                                            }
                                                            Text(
                                                                text = comp.simpleName,
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = TextMain
                                                            )
                                                        }
                                                        Text(
                                                            text = comp.name,
                                                            fontSize = 9.5.sp,
                                                            color = TextDim,
                                                            fontFamily = FontFamily.Monospace,
                                                            maxLines = 1
                                                        )
                                                    }

                                                    Switch(
                                                        checked = comp.isEnabled,
                                                        onCheckedChange = { targetState ->
                                                            detailedComponents = detailedComponents.map {
                                                                if (it.name == comp.name) it.copy(isEnabled = targetState) else it
                                                            }
                                                            scope.launch {
                                                                val res = appRepository.toggleComponent(app.packageName, comp.name, targetState)
                                                                Toast.makeText(context, res.fold(onSuccess = { it }, onFailure = { it.message ?: "Failed" }), Toast.LENGTH_SHORT).show()
                                                            }
                                                        },
                                                        colors = SwitchDefaults.colors(
                                                            checkedThumbColor = BgBase,
                                                            checkedTrackColor = AccentCyan,
                                                            uncheckedThumbColor = TextMuted,
                                                            uncheckedTrackColor = BgSurface
                                                        ),
                                                        modifier = Modifier.size(40.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun AppListItemCard(
    app: AppItem,
    isBusy: Boolean = false,
    onClick: () -> Unit,
    onQuickFreezeToggle: () -> Unit,
    onQuickLaunch: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BgCard)
            .border(width = 1.dp, color = BorderGlass, shape = RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App Avatar / Initial block
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (app.isBloatware) StatusBloat.copy(alpha = 0.2f) else AccentCyan.copy(alpha = 0.15f))
                .border(
                    width = 1.dp,
                    color = if (app.isBloatware) StatusBloat.copy(alpha = 0.5f) else AccentCyan.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentCyan, strokeWidth = 2.dp)
            } else {
                Text(
                    text = app.appName.firstOrNull()?.uppercase() ?: "A",
                    color = if (app.isBloatware) StatusBloat else AccentCyan,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = app.appName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain,
                    maxLines = 1
                )
                if (app.isBloatware) {
                    StatusPill("BLOAT", StatusBloat)
                }
                if (app.trackers.isNotEmpty()) {
                    StatusPill("TRACKER", StatusBloat)
                }
            }

            Text(
                text = app.packageName,
                fontSize = 11.sp,
                color = TextMuted,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )

            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("v${app.versionName}", fontSize = 10.sp, color = TextDim)
                Text("•", fontSize = 10.sp, color = TextDim)
                Text(if (app.isSystemApp) "System" else "User", fontSize = 10.sp, color = if (app.isSystemApp) StatusSystem else StatusUser)
                if (app.targetSdk > 0) {
                    Text("• API ${app.targetSdk}", fontSize = 10.sp, color = TextDim)
                }
            }
        }

        // Direct 1-Tap Convenience Quick Action Icons with Loading indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Quick Freeze / Unfreeze
            IconButton(
                onClick = onQuickFreezeToggle,
                enabled = !isBusy,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (app.isEnabled) StatusFrozen.copy(alpha = 0.15f) else StatusRunning.copy(alpha = 0.15f))
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AccentCyan, strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = if (app.isEnabled) Icons.Default.AcUnit else Icons.Default.Check,
                        contentDescription = if (app.isEnabled) "Freeze" else "Enable",
                        tint = if (app.isEnabled) StatusFrozen else StatusRunning,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Quick Launch
            IconButton(
                onClick = onQuickLaunch,
                enabled = !isBusy,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(AccentCyan.copy(alpha = 0.15f))
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Launch",
                    tint = AccentCyan,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun DebloaterView(
    debloatList: List<DebloatPackage>,
    isBatchRunning: Boolean,
    batchProgressCurrent: Int,
    batchProgressTotal: Int,
    batchCurrentPkg: String,
    batchCurrentAction: String,
    onToggleSelect: (String) -> Unit,
    onSelectRecommendedOnly: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onBatchFreeze: () -> Unit,
    onBatchUninstall: () -> Unit,
    onBatchDexOpt: () -> Unit,
    onCancelBatch: () -> Unit = {}
) {
    val selectedCount = debloatList.count { it.isSelected }
    val progressRatio = if (batchProgressTotal > 0) batchProgressCurrent.toFloat() / batchProgressTotal.toFloat() else 0f

    Column(modifier = Modifier.fillMaxSize()) {
        CyberCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Universal Android Debloater (UAD)", fontWeight = FontWeight.Black, fontSize = 15.sp, color = TextMain)
                Text(
                    "Curated list of OEM preloaded telemetry and bloatware packages with verified safety recommendations.",
                    fontSize = 11.sp,
                    color = TextMuted
                )

                // Live Batch Progress Indicator Banner
                AnimatedVisibility(visible = isBatchRunning) {
                    Column(modifier = Modifier.padding(top = 10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$batchCurrentAction ($batchProgressCurrent of $batchProgressTotal)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentCyan
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "${(progressRatio * 100).toInt()}%",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black,
                                    color = TextMain
                                )
                                IconButton(
                                    onClick = onCancelBatch,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = "Cancel batch", tint = StatusBloat)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { progressRatio },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = when {
                                batchCurrentAction.contains("Uninstall") -> StatusBloat
                                batchCurrentAction.contains("DEX-Opt") -> CleanGreen
                                else -> StatusFrozen
                            },
                            trackColor = BgSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = batchCurrentPkg,
                            fontSize = 10.sp,
                            color = TextDim,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Convenient 1-Tap Selection Shortcuts
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = onSelectRecommendedOnly,
                        enabled = !isBatchRunning,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Recommended Only", fontSize = 10.sp, color = CleanGreen, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onSelectAll,
                        enabled = !isBatchRunning,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(0.7f)
                    ) {
                        Text("Select All", fontSize = 10.sp, color = AccentCyan)
                    }
                    OutlinedButton(
                        onClick = onDeselectAll,
                        enabled = !isBatchRunning,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(0.7f)
                    ) {
                        Text("Clear", fontSize = 10.sp, color = TextDim)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onBatchFreeze,
                        enabled = selectedCount > 0 && !isBatchRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = StatusFrozen, contentColor = BgBase),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).testTag("batch_freeze_btn")
                    ) {
                        if (isBatchRunning && batchCurrentAction.contains("disable")) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgBase, strokeWidth = 2.dp)
                        } else {
                            Text("Freeze ($selectedCount)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = onBatchUninstall,
                        enabled = selectedCount > 0 && !isBatchRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = StatusBloat, contentColor = TextMain),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).testTag("batch_uninstall_btn")
                    ) {
                        if (isBatchRunning && batchCurrentAction.contains("uninstall")) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextMain, strokeWidth = 2.dp)
                        } else {
                            Text("Uninstall ($selectedCount)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Button(
                    onClick = onBatchDexOpt,
                    enabled = selectedCount > 0 && !isBatchRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = CleanGreen, contentColor = BgBase),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("batch_dexopt_btn")
                ) {
                    if (isBatchRunning && batchCurrentAction.contains("DEX-Opt")) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgBase, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Compiling with ART...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("⚡ Batch DEX-Opt ($selectedCount selected)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(debloatList, key = { it.packageName }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BgCard)
                        .border(width = 1.dp, color = BorderGlass, shape = RoundedCornerShape(12.dp))
                        .clickable(enabled = !isBatchRunning) { onToggleSelect(item.packageName) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = item.isSelected,
                        onCheckedChange = { onToggleSelect(item.packageName) },
                        enabled = !isBatchRunning,
                        colors = CheckboxDefaults.colors(
                            checkedColor = StatusBloat,
                            uncheckedColor = TextDim
                        )
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextMain)
                            StatusPill(item.level.title, Color(item.level.colorHex))
                        }
                        Text(item.packageName, fontSize = 11.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                        Text(item.description, fontSize = 10.sp, color = TextDim)
                    }

                    if (item.isFrozen) {
                        StatusPill("FROZEN", StatusFrozen)
                    }
                }
            }
            item {
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }
}
