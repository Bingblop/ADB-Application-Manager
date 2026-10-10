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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.bloatware.bingblop.data.model.ShellCommand
import com.bloatware.bingblop.data.model.ShellMode
import com.bloatware.bingblop.data.repository.ShellRepository
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TerminalScreen(
    shellRepository: ShellRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var commandInput by remember { mutableStateOf("pm list packages -3") }
    var selectedMode by remember { mutableStateOf(ShellMode.LOCAL_SHELL) }
    var history by remember { mutableStateOf<List<ShellCommand>>(emptyList()) }
    var isExecuting by remember { mutableStateOf(false) }

    fun runCmd(cmd: String) {
        if (cmd.isBlank() || isExecuting) return
        isExecuting = true
        scope.launch {
            val result = shellRepository.executeCommand(cmd, selectedMode)
            history = listOf(result) + history
            isExecuting = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(horizontal = 14.dp)
    ) {
        // Mode & Clear Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    ShellMode.LOCAL_SHELL to "Local Shell",
                    ShellMode.SHIZUKU to "Shizuku Privileged",
                    ShellMode.ROOT to "Root (su)"
                ).forEach { (mode, label) ->
                    val isSelected = selectedMode == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) AccentCyan.copy(alpha = 0.2f) else BgCard)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) AccentCyan else BorderGlass,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { selectedMode = mode }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) AccentCyan else TextMuted
                        )
                    }
                }
            }

            if (history.isNotEmpty()) {
                IconButton(onClick = {
                    shellRepository.clearHistory()
                    history = emptyList()
                    Toast.makeText(context, "Terminal cleared", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear History", tint = TextMuted)
                }
            }
        }

        // Categorized Presets Chips
        Text("ONE-TAP COMMAND PRESETS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextDim, letterSpacing = 0.5.sp)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(shellRepository.presets) { (cmd, desc) ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgSurface)
                        .border(width = 1.dp, color = BorderGlass, shape = RoundedCornerShape(8.dp))
                        .clickable {
                            commandInput = cmd
                            runCmd(cmd)
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Column {
                        Text(cmd, fontSize = 11.sp, color = AccentCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text(desc, fontSize = 9.sp, color = TextMuted)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Command Input Field with Run Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = commandInput,
                onValueChange = { commandInput = it },
                placeholder = { Text("Enter shell command...", color = TextDim, fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("terminal_command_input"),
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

            Button(
                onClick = { runCmd(commandInput) },
                enabled = !isExecuting && commandInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgBase),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("execute_command_btn")
            ) {
                if (isExecuting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgBase, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Run", modifier = Modifier.size(18.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Console Output Log
        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 100.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = TextDim, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Interactive ADB / Shell Console Ready", color = TextMuted, fontSize = 13.sp)
                    Text("Tap any preset above or type a custom command", color = TextDim, fontSize = 11.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(history, key = { it.id }) { cmd ->
                    TerminalResultCard(
                        cmd = cmd,
                        onCopy = {
                            val clip = ClipData.newPlainText("Terminal Output", cmd.output)
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(clip)
                            Toast.makeText(context, "Output copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        }
    }
}

@Composable
fun TerminalResultCard(
    cmd: ShellCommand,
    onCopy: () -> Unit
) {
    val timeFormatted = remember(cmd.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(cmd.timestamp))
    }

    CyberCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (cmd.exitCode == 0) BorderGlass else StatusBloat.copy(alpha = 0.5f)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("$", color = AccentCyan, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    Text(
                        text = cmd.command,
                        color = TextMain,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(timeFormatted, fontSize = 10.sp, color = TextDim)
                    Spacer(modifier = Modifier.width(6.dp))
                    StatusPill(
                        text = if (cmd.exitCode == 0) "EXIT 0" else "ERR ${cmd.exitCode}",
                        color = if (cmd.exitCode == 0) CleanGreen else StatusBloat
                    )
                    IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = TextMuted, modifier = Modifier.size(14.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            SelectionContainer {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgSurface)
                        .padding(8.dp)
                ) {
                    Text(
                        text = cmd.output,
                        color = if (cmd.exitCode == 0) TextMain else StatusBloat,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}
