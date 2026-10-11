package com.bloatware.bingblop.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.bloatware.bingblop.data.model.MorpheJobState
import com.bloatware.bingblop.data.model.MorphePatchItem
import com.bloatware.bingblop.data.model.PatchBundleInfo
import com.bloatware.bingblop.data.model.PatchJobStatus
import com.bloatware.bingblop.data.model.SigningKeystoreInfo
import com.bloatware.bingblop.data.repository.AppRepository
import com.bloatware.bingblop.data.repository.PatcherRepository
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
fun PatcherScreen(
    patcherRepository: PatcherRepository,
    appRepository: AppRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Patch Workflow", "Keystore & Signer")

    val bundles = remember { patcherRepository.getBundles() }
    var selectedBundle by remember { mutableStateOf(bundles.first()) }

    // Target applications available for patching
    val targetApps = listOf(
        "com.google.android.youtube" to "YouTube",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.twitter.android" to "Twitter / X",
        "com.reddit.frontpage" to "Reddit",
        "com.spotify.music" to "Spotify"
    )
    var selectedTargetApp by remember { mutableStateOf(targetApps.first()) }

    var availablePatches by remember { mutableStateOf<List<MorphePatchItem>>(emptyList()) }
    val enabledPatchIds = remember { mutableStateListOf<String>() }

    var patchSearchQuery by remember { mutableStateOf("") }
    var selectedCategoryFilter by remember { mutableStateOf("All") }

    var activeSigningKey by remember { mutableStateOf(patcherRepository.getActiveSigningKey()) }

    // Live Patching Job State
    var isJobRunning by remember { mutableStateOf(false) }
    var jobState by remember { mutableStateOf(MorpheJobState()) }
    var showJobModal by remember { mutableStateOf(false) }

    fun refreshPatches() {
        val patches = patcherRepository.getPatchesForPackage(selectedTargetApp.first)
        availablePatches = patches
        enabledPatchIds.clear()
        enabledPatchIds.addAll(patches.filter { it.isEnabled }.map { it.id })
    }

    LaunchedEffect(selectedTargetApp) {
        refreshPatches()
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
                    text = "Morphe Bytecode Patcher",
                    color = TextMain,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "On-device smali modifications, MicroG hooks, adblock & APK signing",
                    color = TextDim,
                    fontSize = 11.sp
                )
            }

            Icon(
                Icons.Default.AutoFixHigh,
                contentDescription = null,
                tint = SecondaryPurple,
                modifier = Modifier.size(24.dp)
            )
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

        if (selectedTabIndex == 0) {
            // ==========================================
            // TAB 0: NATIVE PATCH WORKFLOW
            // ==========================================
            Column(modifier = Modifier.fillMaxSize()) {
                // Target App & Bundle Selector Card
                ModernCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("1. Target Application", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))

                        // Target app selector chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            targetApps.forEach { pair ->
                                val isSelected = selectedTargetApp.first == pair.first
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedTargetApp = pair },
                                    label = { Text(pair.second, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentCyan,
                                        selectedLabelColor = Color.Black,
                                        containerColor = BgCard,
                                        labelColor = TextMuted
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text("2. Patch Bundle Source", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            bundles.forEach { b ->
                                val isSelected = selectedBundle.id == b.id
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedBundle = b },
                                    label = { Text("${b.name} (${b.version})", fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SecondaryPurple,
                                        selectedLabelColor = Color.White,
                                        containerColor = BgCard,
                                        labelColor = TextMuted
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Search & Category Filters
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = patchSearchQuery,
                        onValueChange = { patchSearchQuery = it },
                        placeholder = { Text("Filter patches...", color = TextDim, fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = BorderGlass,
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain,
                            focusedContainerColor = BgSurface,
                            unfocusedContainerColor = BgSurface
                        ),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    TextButton(
                        onClick = {
                            val recommended = availablePatches.filter { it.isRecommended }.map { it.id }
                            enabledPatchIds.clear()
                            enabledPatchIds.addAll(recommended)
                        }
                    ) {
                        Text("Recommended", fontSize = 11.sp, color = CleanGreen)
                    }

                    TextButton(
                        onClick = {
                            if (enabledPatchIds.size == availablePatches.size) enabledPatchIds.clear()
                            else {
                                enabledPatchIds.clear()
                                enabledPatchIds.addAll(availablePatches.map { it.id })
                            }
                        }
                    ) {
                        Text(if (enabledPatchIds.size == availablePatches.size) "Clear" else "All", fontSize = 11.sp, color = AccentCyan)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Patches Checklist
                val filteredPatches = availablePatches.filter { patch ->
                    val matchesQuery = patch.name.contains(patchSearchQuery, ignoreCase = true) ||
                            patch.summary.contains(patchSearchQuery, ignoreCase = true)
                    val matchesCat = selectedCategoryFilter == "All" || patch.category == selectedCategoryFilter
                    matchesQuery && matchesCat
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredPatches, key = { it.id }) { patch ->
                        val isChecked = enabledPatchIds.contains(patch.id)

                        ModernCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isChecked) enabledPatchIds.remove(patch.id)
                                    else enabledPatchIds.add(patch.id)
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        if (checked) enabledPatchIds.add(patch.id)
                                        else enabledPatchIds.remove(patch.id)
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = AccentCyan,
                                        uncheckedColor = TextDim,
                                        checkmarkColor = Color.Black
                                    ),
                                    modifier = Modifier.size(32.dp)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = patch.name, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.width(6.dp))

                                        if (patch.isRecommended) {
                                            Text(
                                                text = "RECOMMENDED",
                                                color = CleanGreen,
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Black,
                                                modifier = Modifier
                                                    .background(CleanGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(4.dp))

                                        Text(
                                            text = patch.category,
                                            color = TextDim,
                                            fontSize = 9.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(text = patch.summary, color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Action Bar to Run Patching
                ModernCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${enabledPatchIds.size} Patches Selected",
                                color = TextMain,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Signer: ${activeSigningKey.alias}",
                                color = AccentCyan,
                                fontSize = 10.sp
                            )
                        }

                        Button(
                            onClick = {
                                val selectedObjects = availablePatches.filter { enabledPatchIds.contains(it.id) }
                                if (selectedObjects.isEmpty()) {
                                    Toast.makeText(context, "Select at least 1 patch to proceed", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                showJobModal = true
                                isJobRunning = true
                                scope.launch {
                                    patcherRepository.runPatchingPipeline(
                                        targetAppLabel = selectedTargetApp.second,
                                        targetPackage = selectedTargetApp.first,
                                        bundle = selectedBundle,
                                        selectedPatches = selectedObjects,
                                        signingKey = activeSigningKey,
                                        onUpdate = { jobState = it }
                                    )
                                    isJobRunning = false
                                }
                            },
                            enabled = enabledPatchIds.isNotEmpty() && !isJobRunning,
                            colors = ButtonDefaults.buttonColors(containerColor = SecondaryPurple, contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Start Bytecode Patch", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            // ==========================================
            // TAB 1: KEYSTORE & SIGNER MANAGEMENT
            // ==========================================
            KeystoreSignerSection(
                keyInfo = activeSigningKey,
                onGenerateKey = { alias, cn, org, years ->
                    val newKey = patcherRepository.generateCustomKeystore(alias, cn, org, years)
                    activeSigningKey = newKey
                    Toast.makeText(context, "Generated and activated new signing key!", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    // ==========================================
    // REAL-TIME PATCHING JOB MODAL DIALOG
    // ==========================================
    if (showJobModal) {
        Dialog(onDismissRequest = { if (!isJobRunning) showJobModal = false }) {
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
                        Text(
                            text = if (isJobRunning) "Patching in Progress..." else "Patching Completed!",
                            color = if (jobState.status == PatchJobStatus.COMPLETED) CleanGreen else TextMain,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (!isJobRunning) {
                            IconButton(onClick = { showJobModal = false }) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(text = "Target: ${jobState.targetAppLabel} (${jobState.targetPackage})", color = TextDim, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = jobState.currentStep, color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { jobState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = if (jobState.status == PatchJobStatus.COMPLETED) CleanGreen else AccentCyan,
                        trackColor = BgCard
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Live Terminal Logs Window
                    Text("Execution Logcat:", color = TextDim, fontSize = 10.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(BgCard, RoundedCornerShape(8.dp))
                            .border(1.dp, BorderGlass, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(jobState.logs) { line ->
                                Text(
                                    text = line,
                                    color = if (line.contains("Success", ignoreCase = true)) CleanGreen else TextMuted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }

                    if (jobState.status == PatchJobStatus.COMPLETED) {
                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val res = patcherRepository.installPatchedApk(jobState.outputApkPath)
                                        Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CleanGreen, contentColor = Color.Black),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Install APK", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Patched APK Path", jobState.outputApkPath))
                                    Toast.makeText(context, "Copied output path: ${jobState.outputApkPath}", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp), tint = AccentCyan)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy Path", fontSize = 11.sp, color = TextMain)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// KEYSTORE & SIGNER MANAGEMENT SECTION
// ==========================================
@Composable
private fun KeystoreSignerSection(
    keyInfo: SigningKeystoreInfo,
    onGenerateKey: (String, String, String, Int) -> Unit
) {
    val context = LocalContext.current
    var customAlias by remember { mutableStateOf("custom_apk_signer") }
    var commonName by remember { mutableStateOf("Morphe Developer") }
    var organization by remember { mutableStateOf("On-Device Signing Authority") }
    var validityYears by remember { mutableStateOf("25") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Active Signing Key Card
        item {
            ModernCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Key, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Active Signing Keystore", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        Text(
                            text = if (keyInfo.isHardwareBacked) "HARDWARE BACKED (TEE)" else "SOFTWARE STORE",
                            color = CleanGreen,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier
                                .background(CleanGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text("Alias: ${keyInfo.alias}", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Subject: ${keyInfo.subject}", color = TextMuted, fontSize = 11.sp)
                    Text("Algorithm: ${keyInfo.algorithm}", color = TextDim, fontSize = 10.sp)

                    Spacer(modifier = Modifier.height(8.dp))

                    Text("SHA-256 Certificate Fingerprint:", color = TextDim, fontSize = 10.sp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgCard, RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = keyInfo.sha256Fingerprint,
                            color = AccentCyan,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Signing Fingerprint", keyInfo.sha256Fingerprint))
                            Toast.makeText(context, "Copied SHA-256 fingerprint to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Certificate SHA-256", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Custom Key Generator Card
        item {
            ModernCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Generate Custom Signing Key", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Create a unique RSA-2048 signing certificate to sign modded APKs", color = TextDim, fontSize = 10.sp)

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = customAlias,
                        onValueChange = { customAlias = it },
                        label = { Text("Key Alias", color = TextDim, fontSize = 10.sp) },
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = commonName,
                            onValueChange = { commonName = it },
                            label = { Text("Common Name (CN)", color = TextDim, fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = organization,
                            onValueChange = { organization = it },
                            label = { Text("Organization (O)", color = TextDim, fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val years = validityYears.toIntOrNull() ?: 25
                            onGenerateKey(customAlias, commonName, organization, years)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SecondaryPurple, contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Generate & Activate Key", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
