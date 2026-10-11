package com.bloatware.bingblop.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.bloatware.bingblop.ui.theme.TextDim
import com.bloatware.bingblop.ui.theme.TextMain
import com.bloatware.bingblop.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun AppsScreen(appRepository: AppRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var packages by remember { mutableStateOf<List<PackageItem>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemOnly by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedPackage by remember { mutableStateOf<PackageItem?>(null) }

    fun refreshList() {
        scope.launch {
            isLoading = true
            packages = appRepository.loadPackages(searchQuery, showSystemOnly)
            isLoading = false
        }
    }

    LaunchedEffect(searchQuery, showSystemOnly) {
        refreshList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(16.dp)
    ) {
        // Search & Filter Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search apps or package name...", color = TextDim, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = AccentCyan) },
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

        // Preset Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { showSystemOnly = !showSystemOnly },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (showSystemOnly) AccentCyan else BgSurface,
                    contentColor = if (showSystemOnly) BgBase else TextMuted
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(if (showSystemOnly) "System Apps (Active)" else "All Apps", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            Text(
                text = "${packages.size} packages listed",
                color = TextDim,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

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
                    ModernCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedPackage = app }
                            .testTag("app_item_${app.packageName.replace('.', '_')}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = app.appName,
                                        color = TextMain,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (app.isVendorBloatware) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "DEBLOAT",
                                            color = DangerRed,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black,
                                            modifier = Modifier
                                                .background(DangerRed.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = app.packageName,
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "v${app.versionName} • ${(app.sizeBytes / (1024 * 1024))} MB",
                                    color = TextDim,
                                    fontSize = 10.sp
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = if (app.isEnabled) Icons.Default.AcUnit else Icons.Default.WbSunny,
                                        contentDescription = if (app.isEnabled) "Freeze" else "Unfreeze",
                                        tint = if (app.isEnabled) AccentCyan else CleanGreen,
                                        modifier = Modifier.size(18.dp)
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
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Uninstall",
                                        tint = DangerRed,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
