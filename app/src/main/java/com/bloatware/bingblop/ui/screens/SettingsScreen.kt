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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.bloatware.bingblop.data.model.SettingNamespace
import com.bloatware.bingblop.data.model.SystemSettingItem
import com.bloatware.bingblop.data.repository.SettingsRepository
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    var currentNamespace by remember { mutableStateOf(SettingNamespace.GLOBAL) }
    var settingsList by remember { mutableStateOf<List<SystemSettingItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }

    var editingItem by remember { mutableStateOf<SystemSettingItem?>(null) }
    var editValueText by remember { mutableStateOf("") }

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

    val filteredList = remember(settingsList, searchQuery) {
        settingsList.filter {
            searchQuery.isEmpty() ||
                    it.title.contains(searchQuery, ignoreCase = true) ||
                    it.key.contains(searchQuery, ignoreCase = true) ||
                    it.category.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(horizontal = 14.dp)
    ) {
        // Namespace Switcher Tabs
        val tabs = listOf("Global", "Secure", "System")
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
                        currentNamespace = when (index) {
                            0 -> SettingNamespace.GLOBAL
                            1 -> SettingNamespace.SECURE
                            else -> SettingNamespace.SYSTEM
                        }
                    },
                    text = {
                        Text(
                            text = title,
                            fontSize = 13.sp,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Search Bar with Undo Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter settings keys...", color = TextDim, fontSize = 13.sp) },
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

            Button(
                onClick = {
                    scope.launch {
                        val res = settingsRepository.undoLastChange()
                        Toast.makeText(context, res.getOrNull() ?: "Undo failed", Toast.LENGTH_SHORT).show()
                        refreshSettings()
                    }
                },
                enabled = settingsRepository.canUndo(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SecondaryPurple,
                    disabledContainerColor = BgCard
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.testTag("undo_settings_btn")
            ) {
                Icon(Icons.Default.Undo, contentDescription = "Undo", tint = TextMain, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

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
                                    text = setting.currentValue,
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
                            Text(
                                text = "Category: ${setting.category}",
                                fontSize = 10.sp,
                                color = TextDim
                            )
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
                    text = "Edit Setting: ${item.title}",
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

                Spacer(modifier = Modifier.height(12.dp))
                Text(item.description, fontSize = 12.sp, color = TextMuted)

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = editValueText,
                    onValueChange = { editValueText = it },
                    label = { Text("New Value") },
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
                                settingsRepository.writeSetting(item, editValueText)
                                Toast.makeText(context, "Saved ${item.key}", Toast.LENGTH_SHORT).show()
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
