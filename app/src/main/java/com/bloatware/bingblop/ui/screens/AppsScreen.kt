package com.bloatware.bingblop.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bloatware.bingblop.data.model.AppItem
import com.bloatware.bingblop.data.model.DebloatPackage
import com.bloatware.bingblop.data.repository.AppRepository
import com.bloatware.bingblop.ui.components.CyberCard
import com.bloatware.bingblop.ui.components.StatMetricBadge
import com.bloatware.bingblop.ui.components.StatusPill
import com.bloatware.bingblop.ui.theme.AccentCyan
import com.bloatware.bingblop.ui.theme.BgBase
import com.bloatware.bingblop.ui.theme.BgCard
import com.bloatware.bingblop.ui.theme.BgSurface
import com.bloatware.bingblop.ui.theme.BorderGlass
import com.bloatware.bingblop.ui.theme.StatusBloat
import com.bloatware.bingblop.ui.theme.StatusFrozen
import com.bloatware.bingblop.ui.theme.StatusRunning
import com.bloatware.bingblop.ui.theme.StatusSystem
import com.bloatware.bingblop.ui.theme.StatusUser
import com.bloatware.bingblop.ui.theme.TextDim
import com.bloatware.bingblop.ui.theme.TextMain
import com.bloatware.bingblop.ui.theme.TextMuted
import kotlinx.coroutines.launch

enum class AppFilter { ALL, USER, SYSTEM, FROZEN, BLOAT }

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
    var isDebloatMode by remember { mutableStateOf(false) }

    var selectedApp by remember { mutableStateOf<AppItem?>(null) }
    var detailedPermissions by remember { mutableStateOf<List<String>>(emptyList()) }
    var detailedActivities by remember { mutableStateOf<List<String>>(emptyList()) }
    var sheetActionMessage by remember { mutableStateOf<String?>(null) }

    // Intercept back button when in Debloat mode
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

    val filteredApps = remember(apps, searchQuery, currentFilter) {
        apps.filter { app ->
            val matchesSearch = searchQuery.isEmpty() ||
                    app.appName.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)

            val matchesFilter = when (currentFilter) {
                AppFilter.ALL -> true
                AppFilter.USER -> !app.isSystemApp
                AppFilter.SYSTEM -> app.isSystemApp
                AppFilter.FROZEN -> !app.isEnabled
                AppFilter.BLOAT -> app.isBloatware
            }
            matchesSearch && matchesFilter
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(horizontal = 14.dp)
    ) {
        // Top stats row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatMetricBadge(
                title = "Total",
                value = apps.size.toString(),
                modifier = Modifier.weight(1f)
            )
            StatMetricBadge(
                title = "Enabled",
                value = apps.count { it.isEnabled }.toString(),
                color = StatusRunning,
                modifier = Modifier.weight(1f)
            )
            StatMetricBadge(
                title = "Frozen",
                value = apps.count { !it.isEnabled }.toString(),
                color = StatusFrozen,
                modifier = Modifier.weight(1f)
            )
            StatMetricBadge(
                title = "Bloatware",
                value = apps.count { it.isBloatware }.toString(),
                color = StatusBloat,
                modifier = Modifier.weight(1f)
            )
        }

        // Mode Switcher: Standard Apps List vs UAD Debloater
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(BgSurface)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                Text("All Packages", fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                    Text("UAD Debloater", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (isDebloatMode) {
            // Debloater View
            DebloaterView(
                debloatList = debloatList,
                onToggleSelect = { pkg ->
                    debloatList = debloatList.map {
                        if (it.packageName == pkg) it.copy(isSelected = !it.isSelected) else it
                    }
                },
                onBatchFreeze = {
                    val targets = debloatList.filter { it.isSelected }.map { it.packageName }
                    scope.launch {
                        targets.forEach { appRepository.freezeApp(it) }
                        Toast.makeText(context, "Batch processed ${targets.size} packages", Toast.LENGTH_SHORT).show()
                        refreshData()
                    }
                },
                onBatchUninstall = {
                    val targets = debloatList.filter { it.isSelected }.map { it.packageName }
                    scope.launch {
                        targets.forEach { appRepository.uninstallApp(it) }
                        Toast.makeText(context, "Batch uninstalled ${targets.size} packages", Toast.LENGTH_SHORT).show()
                        refreshData()
                    }
                }
            )
        } else {
            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search by name or package...", color = TextDim, fontSize = 13.sp) },
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
                    .fillMaxWidth()
                    .testTag("app_search_field"),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentCyan,
                    unfocusedBorderColor = BorderGlass,
                    focusedContainerColor = BgSurface,
                    unfocusedContainerColor = BgSurface,
                    focusedTextColor = TextMain,
                    unfocusedTextColor = TextMain
                )
            )

            // Filter Chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
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
                        AppListItemCard(
                            app = app,
                            onClick = {
                                selectedApp = app
                                detailedPermissions = emptyList()
                                detailedActivities = emptyList()
                                scope.launch {
                                    val (perms, acts) = appRepository.getAppDetails(app.packageName)
                                    detailedPermissions = perms
                                    detailedActivities = acts
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

    // App Inspector BottomSheet
    selectedApp?.let { app ->
        ModalBottomSheet(
            onDismissRequest = {
                selectedApp = null
                sheetActionMessage = null
                detailedPermissions = emptyList()
                detailedActivities = emptyList()
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
                        text = if (app.isEnabled) "ENABLED" else "FROZEN",
                        color = if (app.isEnabled) StatusRunning else StatusFrozen
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Metadata Details
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Version:", fontSize = 11.sp, color = TextMuted)
                            Text("${app.versionName} (${app.versionCode})", fontSize = 11.sp, color = TextMain)
                        }
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Target SDK / Min SDK:", fontSize = 11.sp, color = TextMuted)
                            Text("API ${app.targetSdk} / API ${app.minSdk}", fontSize = 11.sp, color = TextMain)
                        }
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("UID / Type:", fontSize = 11.sp, color = TextMuted)
                            Text("${app.uid} • ${if (app.isSystemApp) "System" else "User"}", fontSize = 11.sp, color = TextMain)
                        }
                        if (app.trackers.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("Exodus Trackers:", fontSize = 11.sp, color = StatusBloat)
                                Text(app.trackers.joinToString(", "), fontSize = 11.sp, color = StatusBloat, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (app.isBloatware) {
                            Text("UAD: ${app.bloatDescription ?: "Bloatware component"}", fontSize = 11.sp, color = StatusBloat, fontWeight = FontWeight.Bold)
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
                            scope.launch {
                                val res = if (app.isEnabled) {
                                    appRepository.freezeApp(app.packageName)
                                } else {
                                    appRepository.unfreezeApp(app.packageName)
                                }
                                sheetActionMessage = res.fold(onSuccess = { it }, onFailure = { it.message ?: "Failed" })
                                refreshData()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (app.isEnabled) StatusFrozen else StatusRunning,
                            contentColor = BgBase
                        ),
                        modifier = Modifier.weight(1f).testTag("action_freeze_toggle")
                    ) {
                        Text(if (app.isEnabled) "Freeze (Disable)" else "Unfreeze (Enable)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                val res = appRepository.uninstallApp(app.packageName)
                                sheetActionMessage = res.fold(onSuccess = { it }, onFailure = { it.message ?: "Failed" })
                                refreshData()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusBloat, contentColor = TextMain),
                        modifier = Modifier.weight(1f).testTag("action_uninstall")
                    ) {
                        Text("Uninstall (--user 0)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val res = appRepository.clearAppData(app.packageName)
                                sheetActionMessage = res.fold(onSuccess = { it }, onFailure = { it.message ?: "Failed" })
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear Data", fontSize = 11.sp, color = TextMain)
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val res = appRepository.reinstallApp(app.packageName)
                                sheetActionMessage = res.fold(onSuccess = { it }, onFailure = { it.message ?: "Failed" })
                                refreshData()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reinstall OEM", fontSize = 11.sp, color = TextMain)
                    }

                    OutlinedButton(
                        onClick = {
                            appRepository.launchApp(app.packageName)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Launch", fontSize = 11.sp, color = AccentCyan)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { appRepository.openAppDetails(app.packageName) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open System App Settings", fontSize = 11.sp, color = TextMuted)
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
fun AppListItemCard(
    app: AppItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BgCard)
            .border(width = 1.dp, color = BorderGlass, shape = RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App Avatar / Icon block
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
            Text(
                text = app.appName.firstOrNull()?.uppercase() ?: "A",
                color = if (app.isBloatware) StatusBloat else AccentCyan,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

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
            }
        }

        StatusPill(
            text = if (app.isEnabled) "ACTIVE" else "FROZEN",
            color = if (app.isEnabled) StatusRunning else StatusFrozen
        )
    }
}

@Composable
fun DebloaterView(
    debloatList: List<DebloatPackage>,
    onToggleSelect: (String) -> Unit,
    onBatchFreeze: () -> Unit,
    onBatchUninstall: () -> Unit
) {
    val selectedCount = debloatList.count { it.isSelected }

    Column(modifier = Modifier.fillMaxSize()) {
        CyberCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Universal Android Debloater (UAD)", fontWeight = FontWeight.Black, fontSize = 14.sp, color = TextMain)
                Text(
                    "Curated list of OEM preloaded telemetry, telemetry loggers, and bloatware packages with verified safety recommendations.",
                    fontSize = 11.sp,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onBatchFreeze,
                        enabled = selectedCount > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = StatusFrozen, contentColor = BgBase),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).testTag("batch_freeze_btn")
                    ) {
                        Text("Freeze Selected ($selectedCount)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = onBatchUninstall,
                        enabled = selectedCount > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = StatusBloat, contentColor = TextMain),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).testTag("batch_uninstall_btn")
                    ) {
                        Text("Uninstall ($selectedCount)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                        .clickable { onToggleSelect(item.packageName) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = item.isSelected,
                        onCheckedChange = { onToggleSelect(item.packageName) },
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
