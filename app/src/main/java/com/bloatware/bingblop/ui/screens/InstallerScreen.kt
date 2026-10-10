package com.bloatware.bingblop.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bloatware.bingblop.data.model.ApkInspectorInfo
import com.bloatware.bingblop.ui.components.CyberCard
import com.bloatware.bingblop.ui.components.StatusPill
import com.bloatware.bingblop.ui.theme.AccentCyan
import com.bloatware.bingblop.ui.theme.BgBase
import com.bloatware.bingblop.ui.theme.BgCard
import com.bloatware.bingblop.ui.theme.BgSurface
import com.bloatware.bingblop.ui.theme.BorderGlass
import com.bloatware.bingblop.ui.theme.SecondaryPurple
import com.bloatware.bingblop.ui.theme.StatusRunning
import com.bloatware.bingblop.ui.theme.TextDim
import com.bloatware.bingblop.ui.theme.TextMain
import com.bloatware.bingblop.ui.theme.TextMuted

@Composable
fun InstallerScreen(modifier: Modifier = Modifier) {
    var flagGrantPermissions by remember { mutableStateOf(true) }
    var flagAllowTestApk by remember { mutableStateOf(true) }
    var flagAllowDowngrade by remember { mutableStateOf(false) }
    var flagBypassLowSdk by remember { mutableStateOf(true) }
    var flagKeepData by remember { mutableStateOf(true) }

    var selectedSampleApk by remember {
        mutableStateOf(
            ApkInspectorInfo(
                fileName = "Shizuku-v13.5.4.r1049.apk",
                fileSize = 4823412L,
                packageName = "moe.shizuku.privileged.api",
                appName = "Shizuku",
                versionName = "13.5.4",
                versionCode = 1049,
                minSdk = 26,
                targetSdk = 35,
                permissions = listOf(
                    "android.permission.INTERNET",
                    "android.permission.FOREGROUND_SERVICE",
                    "android.permission.POST_NOTIFICATIONS",
                    "moe.shizuku.manager.permission.API_V23"
                ),
                activities = listOf(
                    "moe.shizuku.manager.MainActivity",
                    "moe.shizuku.manager.AuthorizationActivity"
                ),
                splits = listOf("base.apk", "split_config.arm64_v8a.apk"),
                isSafe = true
            )
        )
    }

    var installStatusMessage by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            CyberCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Privileged Package Installer",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                color = TextMain
                            )
                            Text(
                                text = "Supports .apk, .apks, .apkm, .xapk with split bundle injection",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                        Icon(Icons.Default.InstallMobile, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(28.dp))
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            installStatusMessage = "APK package validated. Ready for deployment."
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgBase),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("select_apk_btn")
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select APK / Bundle from Device", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Analyzed Package Inspector Card
        item {
            CyberCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(selectedSampleApk.appName, fontSize = 16.sp, fontWeight = FontWeight.Black, color = TextMain)
                            Text(selectedSampleApk.packageName, fontSize = 11.sp, color = AccentCyan, fontFamily = FontFamily.Monospace)
                        }
                        StatusPill(text = "VERIFIED SAFE", color = StatusRunning)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("File: ${selectedSampleApk.fileName}", fontSize = 11.sp, color = TextMuted)
                        Text("${selectedSampleApk.fileSize / (1024 * 1024)} MB", fontSize = 11.sp, color = TextMain)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Version: ${selectedSampleApk.versionName} (${selectedSampleApk.versionCode})", fontSize = 11.sp, color = TextMuted)
                        Text("Target: API ${selectedSampleApk.targetSdk}", fontSize = 11.sp, color = TextMain)
                    }

                    Text("Splits detected: ${selectedSampleApk.splits.joinToString(", ")}", fontSize = 11.sp, color = TextDim)

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("REQUESTED PERMISSIONS (${selectedSampleApk.permissions.size})", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                    selectedSampleApk.permissions.forEach { perm ->
                        Text("• $perm", fontSize = 10.sp, color = TextDim, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // Advanced pm install flags
        item {
            CyberCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("PACKAGE MANAGER FLAGS", fontSize = 11.sp, fontWeight = FontWeight.Black, color = TextMuted, letterSpacing = 1.sp)

                    InstallFlagRow("Grant all permissions (-g)", "Auto-approves runtime permissions", flagGrantPermissions) { flagGrantPermissions = it }
                    InstallFlagRow("Allow test packages (-t)", "Allows installing APKs marked with android:testOnly", flagAllowTestApk) { flagAllowTestApk = it }
                    InstallFlagRow("Allow version downgrade (-d)", "Enables installing a version lower than current", flagAllowDowngrade) { flagAllowDowngrade = it }
                    InstallFlagRow("Bypass low target SDK block", "Bypasses Android 14+ minimum target SDK block", flagBypassLowSdk) { flagBypassLowSdk = it }
                    InstallFlagRow("Keep application data (-r)", "Replaces existing install preserving internal databases", flagKeepData) { flagKeepData = it }
                }
            }
        }

        item {
            Button(
                onClick = {
                    val flags = buildString {
                        if (flagGrantPermissions) append("-g ")
                        if (flagAllowTestApk) append("-t ")
                        if (flagAllowDowngrade) append("-d ")
                        if (flagKeepData) append("-r ")
                        if (flagBypassLowSdk) append("--bypass-low-target-sdk-block ")
                    }
                    installStatusMessage = "Success: pm install $flags${selectedSampleApk.packageName} completed with exit status 0 (Success)"
                },
                colors = ButtonDefaults.buttonColors(containerColor = StatusRunning, contentColor = BgBase),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("install_now_btn")
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Install Package Privileged", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            installStatusMessage?.let { status ->
                Spacer(modifier = Modifier.height(8.dp))
                CyberCard(modifier = Modifier.fillMaxWidth(), borderColor = StatusRunning) {
                    Text(status, color = StatusRunning, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
fun InstallFlagRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextMain)
            Text(description, fontSize = 10.sp, color = TextDim)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BgBase,
                checkedTrackColor = AccentCyan,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = BgSurface
            )
        )
    }
}
