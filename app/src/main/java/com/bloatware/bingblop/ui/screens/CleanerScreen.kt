package com.bloatware.bingblop.ui.screens

import android.text.format.Formatter
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
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bloatware.bingblop.data.model.StorageCleanerItem
import com.bloatware.bingblop.data.repository.CleanerRepository
import com.bloatware.bingblop.ui.components.CyberCard
import com.bloatware.bingblop.ui.components.StatusPill
import com.bloatware.bingblop.ui.theme.AccentCyan
import com.bloatware.bingblop.ui.theme.BgBase
import com.bloatware.bingblop.ui.theme.BgCard
import com.bloatware.bingblop.ui.theme.BgSurface
import com.bloatware.bingblop.ui.theme.BorderGlass
import com.bloatware.bingblop.ui.theme.CleanGreen
import com.bloatware.bingblop.ui.theme.SecondaryPurple
import com.bloatware.bingblop.ui.theme.StatusBloat
import com.bloatware.bingblop.ui.theme.StatusRunning
import com.bloatware.bingblop.ui.theme.TextDim
import com.bloatware.bingblop.ui.theme.TextMain
import com.bloatware.bingblop.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun CleanerScreen(
    cleanerRepository: CleanerRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<StorageCleanerItem>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isScanning by remember { mutableStateOf(true) }
    var isCleaning by remember { mutableStateOf(false) }
    var cleanSummaryText by remember { mutableStateOf<String?>(null) }

    fun runScan() {
        isScanning = true
        scope.launch {
            val result = cleanerRepository.scanStorage()
            items = result
            selectedIds = result.map { it.id }.toSet()
            isScanning = false
        }
    }

    LaunchedEffect(Unit) {
        runScan()
    }

    val totalReclaimable = remember(items, selectedIds) {
        items.filter { selectedIds.contains(it.id) }.sumOf { it.sizeBytes }
    }

    val formattedReclaimable = remember(totalReclaimable) {
        Formatter.formatFileSize(context, totalReclaimable)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(horizontal = 14.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        // Hero Summary Card
        CyberCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "SD Maid Storage Hygiene",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            color = TextMain
                        )
                        Text(
                            text = "SystemCleaner, CorpseFinder & Clutter Scanner",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                    StatusPill("PORT SE", SecondaryPurple)
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = formattedReclaimable,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            color = AccentCyan
                        )
                        Text(
                            text = "Potential Space to Reclaim",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { runScan() },
                            enabled = !isScanning && !isCleaning,
                            colors = ButtonDefaults.buttonColors(containerColor = BgSurface, contentColor = TextMain),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rescan", modifier = Modifier.size(16.dp))
                        }

                        Button(
                            onClick = {
                                isCleaning = true
                                scope.launch {
                                    val res = cleanerRepository.cleanItems(selectedIds)
                                    val cleanedFmt = Formatter.formatFileSize(context, res.bytesReclaimed)
                                    cleanSummaryText = "Reclaimed $cleanedFmt across ${res.itemsCleaned} categories!"
                                    Toast.makeText(context, cleanSummaryText, Toast.LENGTH_LONG).show()
                                    isCleaning = false
                                    runScan()
                                }
                            },
                            enabled = !isScanning && !isCleaning && selectedIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = CleanGreen, contentColor = BgBase),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("clean_now_btn")
                        ) {
                            if (isCleaning) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgBase, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Clean Now", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        cleanSummaryText?.let { summary ->
            Spacer(modifier = Modifier.height(8.dp))
            CyberCard(modifier = Modifier.fillMaxWidth(), borderColor = CleanGreen) {
                Text(summary, color = CleanGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("SCANNED CLEANUP CATEGORIES", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextMuted, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(6.dp))

        if (isScanning) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentCyan)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    val isChecked = selectedIds.contains(item.id)
                    val sizeFormatted = Formatter.formatFileSize(context, item.sizeBytes)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(BgCard)
                            .border(width = 1.dp, color = BorderGlass, shape = RoundedCornerShape(12.dp))
                            .clickable {
                                selectedIds = if (isChecked) selectedIds - item.id else selectedIds + item.id
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = {
                                selectedIds = if (isChecked) selectedIds - item.id else selectedIds + item.id
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = AccentCyan,
                                uncheckedColor = TextDim
                            )
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(item.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextMain)
                                Text(sizeFormatted, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AccentCyan)
                            }

                            Text(item.description, fontSize = 11.sp, color = TextMuted)
                            Text(item.path, fontSize = 10.sp, color = TextDim, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        }
    }
}
