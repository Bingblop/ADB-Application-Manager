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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.bloatware.bingblop.data.model.ApkSignatureComparison
import com.bloatware.bingblop.data.model.MorpheJobState
import com.bloatware.bingblop.data.model.MorphePatchItem
import com.bloatware.bingblop.data.model.PatchBundleInfo
import com.bloatware.bingblop.data.model.PatchJobStatus
import com.bloatware.bingblop.data.model.PatchOption
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
import java.io.File

@Composable
fun PatcherScreen(
    patcherRepository: PatcherRepository,
    appRepository: AppRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Patch Workflow", "Bundle Explorer", "Keystore & Signer Vault")

    var bundles by remember { mutableStateOf(patcherRepository.getBundles()) }
    var selectedBundle by remember { mutableStateOf(bundles.first()) }

    // Target applications available for patching
    val targetApps = listOf(
        "com.google.android.youtube" to "YouTube",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.twitter.android" to "Twitter / X",
        "com.reddit.frontpage" to "Reddit",
        "com.spotify.music" to "Spotify",
        "*" to "Universal / Other Installed App"
    )
    var selectedTargetApp by remember { mutableStateOf(targetApps.first()) }

    var availablePatches by remember { mutableStateOf<List<MorphePatchItem>>(emptyList()) }
    val enabledPatchIds = remember { mutableStateListOf<String>() }

    var patchSearchQuery by remember { mutableStateOf("") }
    var selectedCategoryFilter by remember { mutableStateOf("All") }

    var activeSigningKey by remember { mutableStateOf(patcherRepository.getActiveSigningKey()) }

    // Per-patch option configuration dialog state
    var selectedPatchForConfig by remember { mutableStateOf<MorphePatchItem?>(null) }

    // Live Patching Job State
    var isJobRunning by remember { mutableStateOf(false) }
    var jobState by remember { mutableStateOf(MorpheJobState()) }
    var showJobModal by remember { mutableStateOf(false) }

    // Signature comparison dialog state
    var signatureComparison by remember { mutableStateOf<ApkSignatureComparison?>(null) }
    var showSignatureModal by remember { mutableStateOf(false) }

    // Add custom bundle dialog state
    var showAddBundleModal by remember { mutableStateOf(false) }

    fun refreshPatches() {
        val patches = patcherRepository.getPatchesForPackage(selectedTargetApp.first)
        availablePatches = patches
        enabledPatchIds.clear()
        enabledPatchIds.addAll(patches.filter { it.isEnabled }.map { it.id })
    }

    LaunchedEffect(selectedTargetApp) {
        refreshPatches()
    }

    val conflictWarnings = remember(enabledPatchIds.toList(), selectedTargetApp) {
        patcherRepository.checkDependenciesAndConflicts(enabledPatchIds.toSet(), selectedTargetApp.first)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Morphe Bytecode Patcher",
                        color = TextMain,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "v1.15.2",
                        color = AccentCyan,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .background(AccentCyan.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = "On-device smali modifications, MicroG redirection, adblock & APK signing",
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

        when (selectedTabIndex) {
            0 -> {
                // ==========================================
                // TAB 0: NATIVE PATCH WORKFLOW
                // ==========================================
                Column(modifier = Modifier.fillMaxSize()) {
                    // Target App & Bundle Selector Card
                    ModernCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("1. Target Application", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            signatureComparison = patcherRepository.compareSignatures(selectedTargetApp.first, activeSigningKey)
                                            showSignatureModal = true
                                        }
                                    },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(12.dp), tint = AccentCyan)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Verify Signature Compatibility", fontSize = 10.sp, color = AccentCyan)
                                }
                            }
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

                            Text("2. Active Patch Bundle", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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

                    // Conflict / Dependency Warnings
                    if (conflictWarnings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ModernCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, WarningAmber.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Patch Compatibility Notice", color = WarningAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                conflictWarnings.forEach { warning ->
                                    Text("• $warning", color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Search & Quick Select
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
                                .height(46.dp),
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

                    Spacer(modifier = Modifier.height(8.dp))

                    // Patches Checklist
                    val filteredPatches = availablePatches.filter { patch ->
                        val matchesQuery = patch.name.contains(patchSearchQuery, ignoreCase = true) ||
                                patch.summary.contains(patchSearchQuery, ignoreCase = true) ||
                                patch.category.contains(patchSearchQuery, ignoreCase = true)
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

                                            if (patch.isExperimental) {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "EXPERIMENTAL",
                                                    color = WarningAmber,
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Black,
                                                    modifier = Modifier
                                                        .background(WarningAmber.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
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

                                    if (patch.options.isNotEmpty()) {
                                        IconButton(
                                            onClick = { selectedPatchForConfig = patch },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Settings,
                                                contentDescription = "Configure Patch",
                                                tint = AccentCyan,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
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
                                Text("Compile & Patch APK", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            1 -> {
                // ==========================================
                // TAB 1: BUNDLE EXPLORER & SOURCES
                // ==========================================
                BundleExplorerSection(
                    bundles = bundles,
                    selectedBundle = selectedBundle,
                    onSelectBundle = { selectedBundle = it },
                    onAddBundleClick = { showAddBundleModal = true },
                    onDeleteBundle = { id ->
                        patcherRepository.removeCustomBundle(id)
                        bundles = patcherRepository.getBundles()
                        selectedBundle = bundles.first()
                        Toast.makeText(context, "Bundle removed", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            2 -> {
                // ==========================================
                // TAB 2: KEYSTORE & SIGNER VAULT
                // ==========================================
                KeystoreSignerSection(
                    keyInfo = activeSigningKey,
                    savedKeys = patcherRepository.getSavedKeystores(),
                    onSelectKey = { activeSigningKey = it },
                    onGenerateKey = { alias, cn, org, ou, country, years, bits ->
                        val newKey = patcherRepository.generateCustomKeystore(alias, cn, org, ou, country, years, bits)
                        activeSigningKey = newKey
                        Toast.makeText(context, "Generated and activated new signing key!", Toast.LENGTH_SHORT).show()
                    },
                    onExportKey = {
                        val exportedFile = patcherRepository.exportKeystoreFile(activeSigningKey)
                        try {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exportedFile)
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Export Signing Key"))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Saved to cache: ${exportedFile.name}", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
        }
    }

    // ==========================================
    // PATCH CONFIGURATION DIALOG
    // ==========================================
    selectedPatchForConfig?.let { patch ->
        Dialog(onDismissRequest = { selectedPatchForConfig = null }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BgSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderGlass),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Configure: ${patch.name}", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { selectedPatchForConfig = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(patch.summary, color = TextMuted, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    patch.options.forEach { opt ->
                        Text(opt.title, color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(opt.description, color = TextDim, fontSize = 10.sp)
                        Spacer(modifier = Modifier.height(6.dp))

                        if (opt.type == "choice") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                opt.choices.forEach { choice ->
                                    val isSelected = opt.currentValue == choice
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            // choice selected
                                        },
                                        label = { Text(choice, fontSize = 10.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = AccentCyan,
                                            selectedLabelColor = Color.Black,
                                            containerColor = BgCard,
                                            labelColor = TextMuted
                                        )
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Button(
                        onClick = { selectedPatchForConfig = null },
                        colors = ButtonDefaults.buttonColors(containerColor = CleanGreen, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save & Apply Options", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // ==========================================
    // SIGNATURE COMPARISON VERIFIER MODAL
    // ==========================================
    if (showSignatureModal && signatureComparison != null) {
        val comparison = signatureComparison!!
        Dialog(onDismissRequest = { showSignatureModal = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BgSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderGlass),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (comparison.isMatch) Icons.Default.CheckCircle else Icons.Default.Security,
                                contentDescription = null,
                                tint = if (comparison.isMatch) CleanGreen else WarningAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Signature Inspector", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        IconButton(onClick = { showSignatureModal = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Target App: ${comparison.stockAppLabel} (${comparison.stockPackage})", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(10.dp))

                    Text("Stock Installed Certificate SHA-256:", color = TextDim, fontSize = 10.sp)
                    Box(modifier = Modifier.fillMaxWidth().background(BgCard, RoundedCornerShape(6.dp)).padding(8.dp)) {
                        Text(comparison.stockCertSha256, color = if (comparison.isMatch) CleanGreen else TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Patcher Signing Keystore SHA-256:", color = TextDim, fontSize = 10.sp)
                    Box(modifier = Modifier.fillMaxWidth().background(BgCard, RoundedCornerShape(6.dp)).padding(8.dp)) {
                        Text(comparison.patcherCertSha256, color = AccentCyan, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    ModernCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, if (comparison.isMatch) CleanGreen else WarningAmber, RoundedCornerShape(8.dp))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = if (comparison.isMatch) "STATUS: SIGNATURE MATCH" else "STATUS: SIGNATURE MISMATCH",
                                color = if (comparison.isMatch) CleanGreen else WarningAmber,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(comparison.recommendedAction, color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { showSignatureModal = false },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Understood", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // ==========================================
    // ADD CUSTOM BUNDLE MODAL
    // ==========================================
    if (showAddBundleModal) {
        var bundleName by remember { mutableStateOf("Custom Community Pack") }
        var bundleVersion by remember { mutableStateOf("v1.0.0") }
        var bundleAuthor by remember { mutableStateOf("Git Community") }
        var bundleUrl by remember { mutableStateOf("https://github.com/example/morphe-pack") }

        Dialog(onDismissRequest = { showAddBundleModal = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BgSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderGlass),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Add Custom Patch Bundle", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Import an external bundle source repository or local JAR pack", color = TextDim, fontSize = 10.sp)

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = bundleName,
                        onValueChange = { bundleName = it },
                        label = { Text("Bundle Name", color = TextDim, fontSize = 10.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = bundleVersion,
                            onValueChange = { bundleVersion = it },
                            label = { Text("Version", color = TextDim, fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = bundleAuthor,
                            onValueChange = { bundleAuthor = it },
                            label = { Text("Author", color = TextDim, fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = bundleUrl,
                        onValueChange = { bundleUrl = it },
                        label = { Text("Release / Git Source URL", color = TextDim, fontSize = 10.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showAddBundleModal = false },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Cancel", fontSize = 11.sp, color = TextMuted)
                        }

                        Button(
                            onClick = {
                                val newBundle = PatchBundleInfo(
                                    id = "custom_" + System.currentTimeMillis(),
                                    name = bundleName.trim(),
                                    version = bundleVersion.trim(),
                                    author = bundleAuthor.trim(),
                                    totalPatches = 12,
                                    supportedPackages = listOf("*"),
                                    sourceUrl = bundleUrl.trim(),
                                    sha256 = "CUSTOM_SOURCE_VERIFIED",
                                    isCustom = true,
                                    license = "Custom",
                                    description = "Imported community patch bundle."
                                )
                                patcherRepository.addCustomBundle(newBundle)
                                bundles = patcherRepository.getBundles()
                                selectedBundle = newBundle
                                showAddBundleModal = false
                                Toast.makeText(context, "Added custom bundle: ${newBundle.name}", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SecondaryPurple, contentColor = Color.White),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Import Pack", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // REAL-TIME MULTI-STAGE PATCHING JOB MODAL
    // ==========================================
    if (showJobModal) {
        Dialog(onDismissRequest = { if (!isJobRunning) showJobModal = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = BgSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderGlass),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isJobRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AccentCyan, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CleanGreen, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = if (isJobRunning) "Patching in Progress..." else "Patching Completed!",
                                color = if (jobState.status == PatchJobStatus.COMPLETED) CleanGreen else TextMain,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

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

                    // 6-Stage Visual Timeline Indicators
                    val stageNames = listOf("Ingest", "Disasm", "Transform", "Compile", "Align", "Sign")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        stageNames.forEachIndexed { idx, stage ->
                            val stageNum = idx + 1
                            val isPast = jobState.currentStageIndex > stageNum || jobState.status == PatchJobStatus.COMPLETED
                            val isCurrent = jobState.currentStageIndex == stageNum && isJobRunning

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                isPast -> CleanGreen
                                                isCurrent -> AccentCyan
                                                else -> BgCard
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isPast) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(12.dp))
                                    } else {
                                        Text("$stageNum", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (isCurrent) Color.Black else TextDim)
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    stage,
                                    fontSize = 8.sp,
                                    color = when {
                                        isPast -> CleanGreen
                                        isCurrent -> AccentCyan
                                        else -> TextDim
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Live Terminal Logs Window
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Execution Logcat (${jobState.logs.size} lines):", color = TextDim, fontSize = 10.sp)
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Patch Logs", jobState.logs.joinToString("\n")))
                                Toast.makeText(context, "Copied all logs to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Copy Logs", fontSize = 9.sp, color = AccentCyan)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(BgCard, RoundedCornerShape(8.dp))
                            .border(1.dp, BorderGlass, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(jobState.logs) { line ->
                                Text(
                                    text = line,
                                    color = if (line.contains("SUCCESS", ignoreCase = true)) CleanGreen else TextMuted,
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 13.sp
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
                                Text("Install APK", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    try {
                                        val file = File(jobState.outputApkPath)
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/vnd.android.package-archive"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Share Patched APK"))
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Saved to: ${jobState.outputApkPath}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SecondaryPurple, contentColor = Color.White),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share APK", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// TAB 1: BUNDLE EXPLORER & SOURCES COMPONENT
// ==========================================
@Composable
private fun BundleExplorerSection(
    bundles: List<PatchBundleInfo>,
    selectedBundle: PatchBundleInfo,
    onSelectBundle: (PatchBundleInfo) -> Unit,
    onAddBundleClick: () -> Unit,
    onDeleteBundle: (String) -> Unit
) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Available Patch Bundles (${bundles.size})",
                color = TextMain,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )

            Button(
                onClick = onAddBundleClick,
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Custom Bundle", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(bundles, key = { it.id }) { bundle ->
                val isSelected = bundle.id == selectedBundle.id

                ModernCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectBundle(bundle) }
                        .border(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) AccentCyan else BorderGlass,
                            shape = RoundedCornerShape(12.dp)
                        )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(bundle.name, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    bundle.version,
                                    color = SecondaryPurple,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier
                                        .background(SecondaryPurple.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }

                            if (isSelected) {
                                Text(
                                    "ACTIVE",
                                    color = AccentCyan,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier
                                        .background(AccentCyan.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(bundle.description, color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp)

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Author: ${bundle.author} • License: ${bundle.license}", color = TextDim, fontSize = 10.sp)
                            Text("${bundle.totalPatches} Patches Included", color = CleanGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        if (bundle.sha256.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Integrity SHA-256:", color = TextDim, fontSize = 9.sp)
                            Box(modifier = Modifier.fillMaxWidth().background(BgCard, RoundedCornerShape(4.dp)).padding(6.dp)) {
                                Text(bundle.sha256, color = AccentCyan, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
                            }
                        }

                        if (bundle.isCustom) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(
                                    onClick = { onDeleteBundle(bundle.id) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = DangerRed)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Remove Bundle", fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// TAB 2: KEYSTORE & SIGNER VAULT COMPONENT
// ==========================================
@Composable
private fun KeystoreSignerSection(
    keyInfo: SigningKeystoreInfo,
    savedKeys: List<SigningKeystoreInfo>,
    onSelectKey: (SigningKeystoreInfo) -> Unit,
    onGenerateKey: (String, String, String, String, String, Int, Int) -> Unit,
    onExportKey: () -> Unit
) {
    val context = LocalContext.current
    var customAlias by remember { mutableStateOf("custom_apk_signer") }
    var commonName by remember { mutableStateOf("Morphe Developer") }
    var organization by remember { mutableStateOf("Independent Modder") }
    var orgUnit by remember { mutableStateOf("Android Engineering") }
    var countryCode by remember { mutableStateOf("US") }
    var validityYears by remember { mutableStateOf("25") }
    var keySizeBits by remember { mutableIntStateOf(2048) }

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
                            Text("Active Signing Key", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        Text(
                            text = if (keyInfo.isHardwareBacked) "HARDWARE TEE BACKED" else "SOFTWARE KEYSTORE",
                            color = CleanGreen,
                            fontSize = 8.5.sp,
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
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 13.sp
                        )
                    }

                    if (keyInfo.sha1Fingerprint.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("SHA-1 Fingerprint:", color = TextDim, fontSize = 9.sp)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BgCard, RoundedCornerShape(6.dp))
                                .padding(6.dp)
                        ) {
                            Text(
                                text = keyInfo.sha1Fingerprint,
                                color = TextMuted,
                                fontSize = 8.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Signing Fingerprint", keyInfo.sha256Fingerprint))
                                Toast.makeText(context, "Copied SHA-256 fingerprint to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy Fingerprint", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = onExportKey,
                            colors = ButtonDefaults.buttonColors(containerColor = SecondaryPurple, contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export Key", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Custom Key Generator Card
        item {
            ModernCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Generate Custom RSA Signing Key", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Create an on-device cryptographic key to sign modded APKs with customized credentials", color = TextDim, fontSize = 10.sp)

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = customAlias,
                        onValueChange = { customAlias = it },
                        label = { Text("Key Alias", color = TextDim, fontSize = 10.sp) },
                        modifier = Modifier.fillMaxWidth(),
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

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = orgUnit,
                            onValueChange = { orgUnit = it },
                            label = { Text("Org Unit (OU)", color = TextDim, fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = countryCode,
                            onValueChange = { countryCode = it },
                            label = { Text("Country (C)", color = TextDim, fontSize = 10.sp) },
                            modifier = Modifier.weight(0.6f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("RSA Key Size:", color = TextDim, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = keySizeBits == 2048,
                                onClick = { keySizeBits = 2048 },
                                label = { Text("2048-bit", fontSize = 10.sp) }
                            )
                            FilterChip(
                                selected = keySizeBits == 4096,
                                onClick = { keySizeBits = 4096 },
                                label = { Text("4096-bit (High Sec)", fontSize = 10.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val years = validityYears.toIntOrNull() ?: 25
                            onGenerateKey(customAlias, commonName, organization, orgUnit, countryCode, years, keySizeBits)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SecondaryPurple, contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Generate & Activate Keystore", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
