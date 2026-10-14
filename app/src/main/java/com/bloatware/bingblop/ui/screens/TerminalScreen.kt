package com.bloatware.bingblop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bloatware.bingblop.data.model.LogcatItem
import com.bloatware.bingblop.data.model.ShellCommand
import com.bloatware.bingblop.data.model.ShellMode
import com.bloatware.bingblop.data.repository.LogcatRepository
import com.bloatware.bingblop.data.repository.ShellRepository
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
fun TerminalScreen(
    shellRepository: ShellRepository,
    logcatRepository: LogcatRepository
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Shell, 1: Logcat

    var commandInput by remember { mutableStateOf("pm list packages -3") }
    var history by remember { mutableStateOf<List<ShellCommand>>(emptyList()) }
    var isExecuting by remember { mutableStateOf(false) }

    var logcatFilter by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf<List<LogcatItem>>(emptyList()) }

    fun refreshLogs() {
        scope.launch {
            logs = logcatRepository.getRecentLogs(logcatFilter)
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 1) {
            refreshLogs()
        } else {
            history = shellRepository.getHistory()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(16.dp)
    ) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = BgSurface,
            contentColor = AccentCyan,
            modifier = Modifier.border(1.dp, BorderGlass, RoundedCornerShape(8.dp))
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("ADB Shell", fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Live Logcat", fontWeight = FontWeight.Bold) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (selectedTab == 0) {
            // Shell Console
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commandInput,
                    onValueChange = { commandInput = it },
                    placeholder = { Text("Enter shell command...", color = TextDim, fontSize = 12.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("shell_command_input"),
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

                Button(
                    onClick = {
                        scope.launch {
                            isExecuting = true
                            shellRepository.executeCommand(commandInput, ShellMode.LOCAL_SHELL)
                            history = shellRepository.getHistory()
                            isExecuting = false
                        }
                    },
                    modifier = Modifier.testTag("shell_run_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgBase),
                    shape = RoundedCornerShape(8.dp),
                    enabled = !isExecuting && commandInput.isNotBlank()
                ) {
                    if (isExecuting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgBase, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Run")
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(history, key = { it.id }) { item ->
                    ModernCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("$ " + item.command, color = AccentCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                Text(
                                    text = if (item.exitCode == 0) "SUCCESS (0)" else "EXIT ${item.exitCode}",
                                    color = if (item.exitCode == 0) CleanGreen else DangerRed,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.output,
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        } else {
            // Logcat View
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = logcatFilter,
                    onValueChange = {
                        logcatFilter = it
                        refreshLogs()
                    },
                    placeholder = { Text("Filter tag / regex...", color = TextDim, fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
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

                IconButton(
                    onClick = { refreshLogs() },
                    modifier = Modifier.background(BgSurface, RoundedCornerShape(8.dp)).border(1.dp, BorderGlass, RoundedCornerShape(8.dp))
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentCyan)
                }

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(
                    onClick = {
                        scope.launch {
                            logcatRepository.clearBuffer()
                            refreshLogs()
                        }
                    },
                    modifier = Modifier.background(BgSurface, RoundedCornerShape(8.dp)).border(1.dp, BorderGlass, RoundedCornerShape(8.dp))
                ) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = DangerRed)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgCard, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            val levelColor = when (log.level) {
                                "E", "F" -> DangerRed
                                "W" -> AccentCyan
                                "I" -> CleanGreen
                                else -> TextDim
                            }
                            Text(
                                text = "${log.level}/${log.tag}: ",
                                color = levelColor,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = log.message,
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}
