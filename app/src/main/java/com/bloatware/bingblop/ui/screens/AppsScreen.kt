package com.bloatware.bingblop.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.window.Dialog
import com.bloatware.bingblop.data.model.DebloatCategory
import com.bloatware.bingblop.data.model.DebloatProfile
import com.bloatware.bingblop.data.model.DebloatSafetyTier
import com.bloatware.bingblop.data.model.PackageItem
import com.bloatware.bingblop.data.repository.AppRepository
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
fun AppsScreen(appRepository: AppRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var packages by remember { mutableStateOf<List<PackageItem>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemOnly by remember { mutableStateOf(false) }
    var showBloatOnly by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(DebloatCategory.ALL) }
    var selectedSafetyTier by remember { mutableStateOf<DebloatSafetyTier?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Multi-selection set
    val selectedPackages = remember { mutableStateListOf<String>() }

    // Dialog states
    var inspectionPackage by remember { mutableStateOf<PackageItem?>(null) }
    var showBatchConfirmDialog by remember { mutableStateOf<String?>(null) } // "FREEZE", "UNFREEZE", "UNINSTALL"
    var showProfileDialog by remember { mutableStateOf(false) }
    var isExecutingBatch by remember { mutableStateOf(false) }
    var batchProgressText by remember { mutableStateOf("") }

    fun refreshList() {
        scope.launch {
            isLoading = true
            packages = appRepository.loadPackages(
                filter = searchQuery,
                showSystemOnly = showSystemOnly,
                categoryFilter = selectedCategory,
                safetyFilter = selectedSafetyTier,
                bloatOnly = showBloatOnly
            )
            isLoading = false
        }
    }

    LaunchedEffect(searchQuery, showSystemOnly, showBloatOnly, selectedCategory, selectedSafetyTier) {
        refreshList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(16.dp)
    ) {
        // Search & Refresh Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search package or app name...", color = TextDim, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = AccentCyan) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted)
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("apps_search_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentCyan,
                    unfocusedBorderColor = BorderGlass,
                    focusedTextColor = TextMain,
                    unfocusedTextColor = TextMain,
                    focusedContainerColor = BgSurface,
                    unfocusedContainerColor = BgSurface
                ),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = { showProfileDialog = true },
                modifier = Modifier
                    .background(BgSurface, RoundedCornerShape(10.dp))
                    .border(1.dp, BorderGlass, RoundedCornerShape(10.dp))
                    .size(52.dp)
                    .testTag("apps_profile_btn")
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = "Debloat Profiles", tint = SecondaryPurple)
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = { refreshList() },
                modifier = Modifier
                    .background(BgSurface, RoundedCornerShape(10.dp))
                    .border(1.dp, BorderGlass, RoundedCornerShape(10.dp))
                    .size(52.dp)
                    .testTag("apps_refresh_btn")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentCyan)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // OEM Preset Categories Horizontal Scroll
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DebloatCategory.values().forEach { cat ->
                val isSelected = selectedCategory == cat
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        selectedCategory = cat
                        if (cat != DebloatCategory.ALL) showBloatOnly = true
                    },
                    label = { Text(cat.displayName, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentCyan,
                        selectedLabelColor = Color.Black,
                        containerColor = BgSurface,
                        labelColor = TextMuted
                    ),
                    shape = RoundedCornerShape(8.dp),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = BorderGlass,
                        selectedBorderColor = AccentCyan
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Safety Tiers & Quick Filter Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Bloat Only Toggle
            FilterChip(
                selected = showBloatOnly,
                onClick = { showBloatOnly = !showBloatOnly },
                label = { Text("Bloatware Only", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = WarningAmber.copy(alpha = 0.2f),
                    selectedLabelColor = WarningAmber,
                    containerColor = BgSurface,
                    labelColor = TextDim
                ),
                shape = RoundedCornerShape(8.dp)
            )

            // System Only Toggle
            FilterChip(
                selected = showSystemOnly,
                onClick = { showSystemOnly = !showSystemOnly },
                label = { Text("System Apps", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = SecondaryPurple.copy(alpha = 0.2f),
                    selectedLabelColor = SecondaryPurple,
                    containerColor = BgSurface,
                    labelColor = TextDim
                ),
                shape = RoundedCornerShape(8.dp)
            )

            // Safety Tier Filters
            DebloatSafetyTier.values().forEach { tier ->
                val isSelected = selectedSafetyTier == tier
                val tierColor = when (tier) {
                    DebloatSafetyTier.SAFE, DebloatSafetyTier.RECOMMENDED -> CleanGreen
                    DebloatSafetyTier.ADVANCED -> WarningAmber
                    DebloatSafetyTier.EXPERT -> Color(0xFFF97316)
                    DebloatSafetyTier.UNSAFE -> DangerRed
                }
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        selectedSafetyTier = if (isSelected) null else tier
                    },
                    label = { Text(tier.displayName, fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tierColor.copy(alpha = 0.25f),
                        selectedLabelColor = tierColor,
                        containerColor = BgSurface,
                        labelColor = TextDim
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Multi-Select Quick Selection Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${packages.size} apps listed • ${selectedPackages.size} selected",
                color = TextDim,
                fontSize = 11.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = {
                        // Select all safe bloatware currently visible
                        val safePackages = packages.filter {
                            it.debloatInfo?.safetyTier == DebloatSafetyTier.SAFE ||
                            it.debloatInfo?.safetyTier == DebloatSafetyTier.RECOMMENDED
                        }.map { it.packageName }
                        selectedPackages.clear()
                        selectedPackages.addAll(safePackages)
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("Select Safe", fontSize = 11.sp, color = CleanGreen)
                }

                TextButton(
                    onClick = {
                        if (selectedPackages.size == packages.size) {
                            selectedPackages.clear()
                        } else {
                            selectedPackages.clear()
                            selectedPackages.addAll(packages.map { it.packageName })
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(if (selectedPackages.size == packages.size) "Clear" else "Select All", fontSize = 11.sp, color = AccentCyan)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Main Package List
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(packages, key = { it.packageName }) { app ->
                    val isChecked = selectedPackages.contains(app.packageName)

                    ModernCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { inspectionPackage = app }
                            .testTag("app_item_${app.packageName.replace('.', '_')}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Checkbox for batch staging
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    if (checked) selectedPackages.add(app.packageName)
                                    else selectedPackages.remove(app.packageName)
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = AccentCyan,
                                    uncheckedColor = TextDim,
                                    checkmarkColor = Color.Black
                                ),
                                modifier = Modifier.size(36.dp)
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            // Details
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = app.appName,
                                        color = TextMain,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )

                                    // Enabled / Frozen Badge
                                    if (!app.isEnabled) {
                                        Text(
                                            text = "FROZEN",
                                            color = AccentCyan,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .background(AccentCyan.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }

                                    // Safety Tier Badge
                                    if (app.debloatInfo != null) {
                                        val tier = app.debloatInfo.safetyTier
                                        val tierColor = when (tier) {
                                            DebloatSafetyTier.SAFE, DebloatSafetyTier.RECOMMENDED -> CleanGreen
                                            DebloatSafetyTier.ADVANCED -> WarningAmber
                                            DebloatSafetyTier.EXPERT -> Color(0xFFF97316)
                                            DebloatSafetyTier.UNSAFE -> DangerRed
                                        }
                                        Text(
                                            text = tier.displayName.uppercase(),
                                            color = tierColor,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Black,
                                            modifier = Modifier
                                                .background(tierColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = app.packageName,
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )

                                if (app.debloatInfo != null && app.debloatInfo.summary.isNotEmpty()) {
                                    Text(
                                        text = app.debloatInfo.summary,
                                        color = TextDim,
                                        fontSize = 10.sp,
                                        maxLines = 1
                                    )
                                }

                                Text(
                                    text = "v${app.versionName} • ${(app.sizeBytes / (1024 * 1024))} MB • ${if (app.isSystemApp) "System" else "User"}",
                                    color = TextDim.copy(alpha = 0.8f),
                                    fontSize = 10.sp
                                )
                            }

                            // Single Quick Actions
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            if (app.isEnabled) {
                                                appRepository.freezePackage(app.packageName)
                                                Toast.makeText(context, "Frozen: ${app.appName}", Toast.LENGTH_SHORT).show()
                                            } else {
                                                appRepository.unfreezePackage(app.packageName)
                                                Toast.makeText(context, "Unfrozen: ${app.appName}", Toast.LENGTH_SHORT).show()
                                            }
                                            refreshList()
                                        }
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = if (app.isEnabled) Icons.Default.AcUnit else Icons.Default.WbSunny,
                                        contentDescription = if (app.isEnabled) "Freeze" else "Unfreeze",
                                        tint = if (app.isEnabled) AccentCyan else CleanGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            appRepository.uninstallPackage(app.packageName, keepData = false)
                                            Toast.makeText(context, "Uninstalled: ${app.appName}", Toast.LENGTH_SHORT).show()
                                            refreshList()
                                        }
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Uninstall",
                                        tint = DangerRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // BATCH OPERATIONS & SAFE STAGING BOTTOM BAR
        // ==========================================
        if (selectedPackages.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            ModernCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("apps_batch_action_bar")
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val selectedObjs = packages.filter { selectedPackages.contains(it.packageName) }
                    val safeCount = selectedObjs.count {
                        it.debloatInfo?.safetyTier == DebloatSafetyTier.SAFE ||
                        it.debloatInfo?.safetyTier == DebloatSafetyTier.RECOMMENDED
                    }
                    val unsafeCount = selectedObjs.count { it.debloatInfo?.safetyTier == DebloatSafetyTier.UNSAFE }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Batch Safe Staging (${selectedPackages.size})",
                                color = TextMain,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "$safeCount Safe • ${selectedPackages.size - safeCount - unsafeCount} Advanced" +
                                        if (unsafeCount > 0) " • $unsafeCount UNSAFE!" else "",
                                color = if (unsafeCount > 0) DangerRed else TextMuted,
                                fontSize = 11.sp
                            )
                        }

                        IconButton(onClick = { selectedPackages.clear() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextDim)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Batch Freeze (Safe)
                        Button(
                            onClick = { showBatchConfirmDialog = "FREEZE" },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.AcUnit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Freeze (${selectedPackages.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        // Batch Restore
                        Button(
                            onClick = { showBatchConfirmDialog = "UNFREEZE" },
                            colors = ButtonDefaults.buttonColors(containerColor = CleanGreen, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.WbSunny, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Restore", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        // Batch Uninstall
                        Button(
                            onClick = { showBatchConfirmDialog = "UNINSTALL" },
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Uninstall", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // DETAILED PACKAGE INSPECTION DIALOG
    // ==========================================
    inspectionPackage?.let { app ->
        Dialog(onDismissRequest = { inspectionPackage = null }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BgSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderGlass),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = app.appName, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(text = app.packageName, color = TextMuted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { inspectionPackage = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Debloat Info Card
                    if (app.debloatInfo != null) {
                        val info = app.debloatInfo
                        val tier = info.safetyTier
                        val tierColor = when (tier) {
                            DebloatSafetyTier.SAFE, DebloatSafetyTier.RECOMMENDED -> CleanGreen
                            DebloatSafetyTier.ADVANCED -> WarningAmber
                            DebloatSafetyTier.EXPERT -> Color(0xFFF97316)
                            DebloatSafetyTier.UNSAFE -> DangerRed
                        }

                        ModernCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = info.category.displayName,
                                        color = AccentCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = tier.displayName,
                                        color = tierColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        modifier = Modifier
                                            .background(tierColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                if (info.summary.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(text = info.summary, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }

                                if (info.description.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(text = info.description, color = TextDim, fontSize = 11.sp, lineHeight = 16.sp)
                                }

                                if (info.dependencies.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Dependencies: ${info.dependencies.joinToString(", ")}",
                                        color = WarningAmber,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Metadata attributes
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgCard, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = "Status: ${if (app.isEnabled) "Active / Enabled" else "Frozen / Disabled"}", color = TextMain, fontSize = 11.sp)
                        Text(text = "Version: ${app.versionName} (Build ${app.versionCode})", color = TextDim, fontSize = 11.sp)
                        Text(text = "Size: ${(app.sizeBytes / (1024 * 1024))} MB", color = TextDim, fontSize = 11.sp)
                        Text(text = "Source: ${app.apkPath}", color = TextDim, fontSize = 10.sp, maxLines = 1)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    if (app.isEnabled) appRepository.freezePackage(app.packageName)
                                    else appRepository.unfreezePackage(app.packageName)
                                    Toast.makeText(context, "${if (app.isEnabled) "Frozen" else "Unfrozen"}: ${app.appName}", Toast.LENGTH_SHORT).show()
                                    inspectionPackage = null
                                    refreshList()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (app.isEnabled) AccentCyan else CleanGreen,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (app.isEnabled) "Freeze App" else "Restore App", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    appRepository.uninstallPackage(app.packageName, keepData = false)
                                    Toast.makeText(context, "Uninstalled: ${app.appName}", Toast.LENGTH_SHORT).show()
                                    inspectionPackage = null
                                    refreshList()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Uninstall", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                                if (launchIntent != null) {
                                    context.startActivity(launchIntent)
                                } else {
                                    Toast.makeText(context, "No launchable activity for ${app.appName}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp), tint = AccentCyan)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Launch", fontSize = 11.sp, color = TextMain)
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    appRepository.reinstallPackage(app.packageName)
                                    Toast.makeText(context, "Reinstalled: ${app.appName}", Toast.LENGTH_SHORT).show()
                                    inspectionPackage = null
                                    refreshList()
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.SettingsBackupRestore, contentDescription = null, modifier = Modifier.size(14.dp), tint = CleanGreen)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reinstall Existing", fontSize = 11.sp, color = TextMain)
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // BATCH CONFIRMATION & STAGING DIALOG
    // ==========================================
    showBatchConfirmDialog?.let { action ->
        val selectedObjs = packages.filter { selectedPackages.contains(it.packageName) }
        val hasUnsafe = selectedObjs.any { it.debloatInfo?.safetyTier == DebloatSafetyTier.UNSAFE }

        AlertDialog(
            onDismissRequest = { showBatchConfirmDialog = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (hasUnsafe) Icons.Default.Warning else Icons.Default.Security,
                        contentDescription = null,
                        tint = if (hasUnsafe) DangerRed else AccentCyan
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (action) {
                            "FREEZE" -> "Confirm Batch Freeze (${selectedPackages.size})"
                            "UNFREEZE" -> "Confirm Batch Restore (${selectedPackages.size})"
                            else -> "Confirm Batch Uninstall (${selectedPackages.size})"
                        },
                        fontSize = 16.sp
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = when (action) {
                            "FREEZE" -> "Freeze (disable) will safely suspend the selected apps without deleting data. This is 100% reversible via Restore."
                            "UNFREEZE" -> "Restore (enable) will reactivate all selected packages immediately."
                            else -> "Uninstall for User 0 removes the apps for this user profile. Can be restored using 'pm install-existing'."
                        },
                        color = TextDim,
                        fontSize = 12.sp
                    )

                    if (hasUnsafe) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "WARNING: Selection includes packages marked as UNSAFE / Bootloop risk! Disabling them may cause system instability.",
                            color = DangerRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Packages: ${selectedPackages.take(5).joinToString(", ")}${if (selectedPackages.size > 5) "..." else ""}",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targets = selectedPackages.toList()
                        showBatchConfirmDialog = null
                        isExecutingBatch = true
                        batchProgressText = "Executing $action for ${targets.size} packages..."

                        scope.launch {
                            when (action) {
                                "FREEZE" -> appRepository.batchFreeze(targets)
                                "UNFREEZE" -> appRepository.batchUnfreeze(targets)
                                "UNINSTALL" -> appRepository.batchUninstall(targets, keepData = false)
                            }
                            isExecutingBatch = false
                            selectedPackages.clear()
                            Toast.makeText(context, "Completed $action for ${targets.size} apps", Toast.LENGTH_SHORT).show()
                            refreshList()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (action == "UNINSTALL" || hasUnsafe) DangerRed else AccentCyan,
                        contentColor = if (action == "UNINSTALL" || hasUnsafe) Color.White else Color.Black
                    )
                ) {
                    Text("Apply Now", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchConfirmDialog = null }) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = BgSurface
        )
    }

    // ==========================================
    // BATCH PROGRESS MODAL
    // ==========================================
    if (isExecutingBatch) {
        Dialog(onDismissRequest = {}) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = BgSurface,
                modifier = Modifier.padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = AccentCyan)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(batchProgressText, color = TextMain, fontSize = 13.sp)
                }
            }
        }
    }

    // ==========================================
    // DEBLOAT PROFILE EXPORT & IMPORT DIALOG
    // ==========================================
    if (showProfileDialog) {
        DebloatProfileDialog(
            appRepository = appRepository,
            currentPackages = packages,
            selectedPackages = selectedPackages,
            onDismiss = { showProfileDialog = false },
            onProfileApplied = {
                showProfileDialog = false
                refreshList()
            }
        )
    }
}

@Composable
private fun DebloatProfileDialog(
    appRepository: AppRepository,
    currentPackages: List<PackageItem>,
    selectedPackages: List<String>,
    onDismiss: () -> Unit,
    onProfileApplied: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) } // 0 = Export, 1 = Import

    // Export state
    var profileName by remember { mutableStateOf("My Device Debloat Profile") }
    var generatedJson by remember { mutableStateOf("") }

    // Import state
    var importJsonText by remember { mutableStateOf("") }
    var previewProfile by remember { mutableStateOf<DebloatProfile?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedPackages) {
        val targets = if (selectedPackages.isNotEmpty()) {
            currentPackages.filter { selectedPackages.contains(it.packageName) }
        } else {
            currentPackages.filter { !it.isEnabled || it.isVendorBloatware }
        }
        generatedJson = appRepository.exportDebloatProfileJson(profileName, targets, "DISABLE")
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = BgSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderGlass),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Debloat Profiles (JSON)", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Tab Switcher
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { selectedTab = 0 },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedTab == 0) AccentCyan else BgCard,
                            contentColor = if (selectedTab == 0) Color.Black else TextMuted
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export Config", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { selectedTab = 1 },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedTab == 1) AccentCyan else BgCard,
                            contentColor = if (selectedTab == 1) Color.Black else TextMuted
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Import Config", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (selectedTab == 0) {
                    // EXPORT TAB
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = {
                            profileName = it
                            val targets = if (selectedPackages.isNotEmpty()) {
                                currentPackages.filter { p -> selectedPackages.contains(p.packageName) }
                            } else {
                                currentPackages.filter { p -> !p.isEnabled || p.isVendorBloatware }
                            }
                            generatedJson = appRepository.exportDebloatProfileJson(it, targets, "DISABLE")
                        },
                        label = { Text("Profile Name", color = TextDim, fontSize = 11.sp) },
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

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = generatedJson,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BorderGlass,
                            unfocusedBorderColor = BorderGlass,
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain,
                            focusedContainerColor = BgCard,
                            unfocusedContainerColor = BgCard
                        ),
                        shape = RoundedCornerShape(8.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Debloat Profile", generatedJson))
                                Toast.makeText(context, "Copied profile JSON to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy JSON", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    // IMPORT TAB
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Paste Profile JSON:", color = TextDim, fontSize = 11.sp)
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                if (!clip.isNullOrEmpty()) {
                                    importJsonText = clip
                                    try {
                                        previewProfile = appRepository.importDebloatProfileJson(clip)
                                        parseError = null
                                    } catch (e: Exception) {
                                        parseError = "Invalid JSON format: ${e.message}"
                                    }
                                } else {
                                    Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(12.dp), tint = AccentCyan)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Paste from Clipboard", fontSize = 11.sp, color = AccentCyan)
                        }
                    }

                    OutlinedTextField(
                        value = importJsonText,
                        onValueChange = {
                            importJsonText = it
                            if (it.isNotEmpty()) {
                                try {
                                    previewProfile = appRepository.importDebloatProfileJson(it)
                                    parseError = null
                                } catch (e: Exception) {
                                    parseError = "JSON Error: ${e.message}"
                                    previewProfile = null
                                }
                            } else {
                                previewProfile = null
                                parseError = null
                            }
                        },
                        placeholder = { Text("Paste JSON config here...", color = TextDim, fontSize = 11.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = BorderGlass,
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain,
                            focusedContainerColor = BgCard,
                            unfocusedContainerColor = BgCard
                        ),
                        shape = RoundedCornerShape(8.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    )

                    parseError?.let { err ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = err, color = DangerRed, fontSize = 10.sp)
                    }

                    previewProfile?.let { profile ->
                        Spacer(modifier = Modifier.height(8.dp))
                        val matchedCount = currentPackages.count { cp -> profile.packages.any { pp -> pp.packageName == cp.packageName } }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BgCard, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Text(text = "Profile: ${profile.name}", color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(text = "Matches $matchedCount of ${profile.packages.size} packages on this device", color = CleanGreen, fontSize = 11.sp)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                val targets = currentPackages.filter { cp ->
                                    profile.packages.any { pp -> pp.packageName == cp.packageName }
                                }.map { it.packageName }

                                scope.launch {
                                    appRepository.batchFreeze(targets)
                                    Toast.makeText(context, "Applied profile to ${targets.size} packages!", Toast.LENGTH_SHORT).show()
                                    onProfileApplied()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CleanGreen, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Apply Profile (Freeze $matchedCount Apps)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
