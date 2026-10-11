package com.bloatware.bingblop.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bloatware.bingblop.data.model.StorageCleanerItem
import com.bloatware.bingblop.data.repository.CleanerRepository
import com.bloatware.bingblop.ui.components.ModernCard
import com.bloatware.bingblop.ui.theme.AccentCyan
import com.bloatware.bingblop.ui.theme.BgBase
import com.bloatware.bingblop.ui.theme.BgSurface
import com.bloatware.bingblop.ui.theme.CleanGreen
import com.bloatware.bingblop.ui.theme.TextDim
import com.bloatware.bingblop.ui.theme.TextMain
import com.bloatware.bingblop.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun CleanerScreen(cleanerRepository: CleanerRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<StorageCleanerItem>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isScanning by remember { mutableStateOf(false) }
    var isCleaning by remember { mutableStateOf(false) }

    fun runScan() {
        scope.launch {
            isScanning = true
            val results = cleanerRepository.scanStorage()
            items = results
            selectedIds = results.map { it.id }.toSet()
            isScanning = false
        }
    }

    LaunchedEffect(Unit) {
        runScan()
    }

    val totalReclaimableBytes = items.filter { selectedIds.contains(it.id) }.sumOf { it.sizeBytes }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(16.dp)
    ) {
        // Overview Card
        ModernCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Reclaimable Disk Space", color = TextMuted, fontSize = 12.sp)
                        Text(
                            text = String.format("%.2f MB", totalReclaimableBytes / (1024.0 * 1024.0)),
                            color = CleanGreen,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    IconButton(
                        onClick = { runScan() },
                        enabled = !isScanning && !isCleaning
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan", tint = AccentCyan)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        scope.launch {
                            isCleaning = true
                            val result = cleanerRepository.cleanItems(selectedIds)
                            isCleaning = false
                            Toast.makeText(
                                context,
                                "Cleaned ${result.itemsCleaned} categories. Reclaimed ${(result.bytesReclaimed / 1024)} KB!",
                                Toast.LENGTH_LONG
                            ).show()
                            runScan()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cleaner_clean_btn"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CleanGreen,
                        contentColor = BgBase
                    ),
                    shape = RoundedCornerShape(8.dp),
                    enabled = !isScanning && !isCleaning && selectedIds.isNotEmpty()
                ) {
                    if (isCleaning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgBase, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Clean Selected Items", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Storage Categories (${items.size})",
            color = TextMain,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (isScanning) {
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
                items(items, key = { it.id }) { item ->
                    val isChecked = selectedIds.contains(item.id)
                    ModernCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedIds = if (isChecked) selectedIds - item.id else selectedIds + item.id
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
                                    selectedIds = if (checked) selectedIds + item.id else selectedIds - item.id
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = CleanGreen,
                                    uncheckedColor = TextDim
                                )
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(item.description, color = TextMuted, fontSize = 11.sp)
                                Text(
                                    text = String.format("%.2f KB", item.sizeBytes / 1024.0),
                                    color = AccentCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
