package com.bloatware.bingblop.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bloatware.bingblop.data.model.LogcatEntry
import com.bloatware.bingblop.data.model.LogcatLevel
import com.bloatware.bingblop.data.repository.LogcatRepository
import com.bloatware.bingblop.ui.theme.AccentCyan
import com.bloatware.bingblop.ui.theme.BgBase
import com.bloatware.bingblop.ui.theme.BgCard
import com.bloatware.bingblop.ui.theme.BgSurface
import com.bloatware.bingblop.ui.theme.BorderGlass
import com.bloatware.bingblop.ui.theme.CleanGreen
import com.bloatware.bingblop.ui.theme.StatusBloat
import com.bloatware.bingblop.ui.theme.StatusRunning
import com.bloatware.bingblop.ui.theme.TextDim
import com.bloatware.bingblop.ui.theme.TextMain
import com.bloatware.bingblop.ui.theme.TextMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogcatScreen(
    logcatRepository: LogcatRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val entries by logcatRepository.entries.collectAsState()
    val isStreaming by logcatRepository.isStreaming.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf(LogcatLevel.ALL) }
    var autoScroll by remember { mutableStateOf(true) }
    var selectedEntryForDetail by remember { mutableStateOf<LogcatEntry?>(null) }

    val listState = rememberLazyListState()

    val filteredEntries by remember(entries, searchQuery, selectedLevel) {
        derivedStateOf {
            entries.filter { entry ->
                val levelMatches = selectedLevel == LogcatLevel.ALL || entry.level == selectedLevel ||
                        (selectedLevel == LogcatLevel.WARN && (entry.level == LogcatLevel.WARN || entry.level == LogcatLevel.ERROR || entry.level == LogcatLevel.FATAL)) ||
                        (selectedLevel == LogcatLevel.ERROR && (entry.level == LogcatLevel.ERROR || entry.level == LogcatLevel.FATAL))
                val queryMatches = searchQuery.isBlank() ||
                        entry.tag.contains(searchQuery, ignoreCase = true) ||
                        entry.message.contains(searchQuery, ignoreCase = true) ||
                        entry.pid.contains(searchQuery, ignoreCase = true)
                levelMatches && queryMatches
            }
        }
    }

    // Auto-scroll when new logs arrive if enabled
    LaunchedEffect(filteredEntries.size, autoScroll) {
        if (autoScroll && filteredEntries.isNotEmpty()) {
            listState.animateScrollToItem(filteredEntries.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(horizontal = 14.dp)
    ) {
        // Top Toolbar: Status, Auto-scroll, Pause, Clear, Copy
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Live Status Indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isStreaming) CleanGreen else Color(0xFFFFB300))
                )
                Text(
                    text = if (isStreaming) "LIVE STREAMING" else "PAUSED",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    color = if (isStreaming) CleanGreen else Color(0xFFFFB300)
                )
                Text(
                    text = "(${filteredEntries.size} / ${entries.size})",
                    fontSize = 11.sp,
                    color = TextDim
                )
            }

            // Action Icons Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Auto-scroll toggle
                IconButton(
                    onClick = { autoScroll = !autoScroll },
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (autoScroll) AccentCyan.copy(alpha = 0.15f) else Color.Transparent)
                ) {
                    Icon(
                        Icons.Default.VerticalAlignBottom,
                        contentDescription = "Auto Scroll",
                        tint = if (autoScroll) AccentCyan else TextDim,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Pause / Resume toggle
                IconButton(
                    onClick = { logcatRepository.toggleStreaming() },
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgSurface)
                ) {
                    Icon(
                        if (isStreaming) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isStreaming) "Pause" else "Resume",
                        tint = if (isStreaming) AccentCyan else CleanGreen,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Clear Buffer
                IconButton(
                    onClick = {
                        logcatRepository.clearBuffer()
                        Toast.makeText(context, "Logcat buffer cleared", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgSurface)
                ) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = "Clear Logs",
                        tint = StatusBloat,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Copy Export
                IconButton(
                    onClick = {
                        val export = logcatRepository.getExportText()
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Logcat Export", export))
                        Toast.makeText(context, "Export copied (${entries.size} lines)", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgSurface)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy Logs",
                        tint = TextMain,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        // Search Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Filter tag, message, or PID...", color = TextDim, fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Clear Search", tint = TextDim, modifier = Modifier.size(14.dp))
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("logcat_search_input"),
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

        Spacer(modifier = Modifier.height(6.dp))

        // Level Filter Chips Row
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(LogcatLevel.values()) { level ->
                val isSelected = selectedLevel == level
                val levelColor = Color(level.colorHex)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) levelColor.copy(alpha = 0.2f) else BgSurface)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) levelColor else BorderGlass,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { selectedLevel = level }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (level != LogcatLevel.ALL) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(levelColor)
                            )
                        }
                        Text(
                            text = level.label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) levelColor else TextMuted
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Main Log Listing
        if (filteredEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 60.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isStreaming) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), color = AccentCyan, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Listening to system logcat stream...", color = TextMuted, fontSize = 12.sp)
                    } else {
                        Text("No logs match current filter", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Try selecting 'All' or clearing the search query", color = TextDim, fontSize = 11.sp)
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BgCard)
                    .border(width = 1.dp, color = BorderGlass, shape = RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredEntries, key = { it.id }) { entry ->
                    LogcatEntryRow(
                        entry = entry,
                        onClick = { selectedEntryForDetail = entry }
                    )
                }
            }
        }
    }

    // Detail Dialog on tap
    selectedEntryForDetail?.let { entry ->
        AlertDialog(
            onDismissRequest = { selectedEntryForDetail = null },
            containerColor = BgCard,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(entry.level.colorHex))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(entry.level.code, color = BgBase, fontWeight = FontWeight.Black, fontSize = 12.sp)
                    }
                    Text(entry.tag, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Time: ${entry.timestamp.ifEmpty { "N/A" }}", fontSize = 11.sp, color = TextMuted)
                        Text("PID: ${entry.pid}", fontSize = 11.sp, color = TextMuted)
                    }
                    SelectionContainer {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(BgSurface)
                                .border(width = 1.dp, color = BorderGlass, shape = RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = entry.message,
                                color = TextMain,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Logcat Row", entry.raw))
                    Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                    selectedEntryForDetail = null
                }) {
                    Text("Copy", color = AccentCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedEntryForDetail = null }) {
                    Text("Close", color = TextDim)
                }
            }
        )
    }
}

@Composable
fun LogcatEntryRow(
    entry: LogcatEntry,
    onClick: () -> Unit
) {
    val levelColor = Color(entry.level.colorHex)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Level Code Badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(levelColor.copy(alpha = 0.2f))
                .border(width = 0.5.dp, color = levelColor, shape = RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            Text(
                text = entry.level.code,
                color = levelColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
        }

        // Tag + Message
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.tag,
                    color = levelColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (entry.timestamp.isNotEmpty()) {
                    Text(
                        text = entry.timestamp.substringAfter(" "),
                        color = TextDim,
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Text(
                text = entry.message,
                color = if (entry.level == LogcatLevel.ERROR || entry.level == LogcatLevel.FATAL) StatusBloat else TextMain,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
