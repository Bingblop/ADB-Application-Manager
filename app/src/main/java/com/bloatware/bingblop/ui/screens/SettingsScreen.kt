package com.bloatware.bingblop.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import com.bloatware.bingblop.data.model.SystemSettingItem
import com.bloatware.bingblop.data.repository.AppRepository
import com.bloatware.bingblop.data.repository.SettingsRepository
import com.bloatware.bingblop.ui.components.ModernCard
import com.bloatware.bingblop.ui.theme.AccentCyan
import com.bloatware.bingblop.ui.theme.BgBase
import com.bloatware.bingblop.ui.theme.CleanGreen
import com.bloatware.bingblop.ui.theme.TextDim
import com.bloatware.bingblop.ui.theme.TextMain
import com.bloatware.bingblop.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    appRepository: AppRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<List<SystemSettingItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    fun load() {
        scope.launch {
            isLoading = true
            settings = settingsRepository.loadSettings()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        load()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(16.dp)
    ) {
        Text(
            text = "System Tweaks & Hidden Knobs",
            color = TextMain,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Privileged global settings requiring elevated ADB / Root authorization",
            color = TextDim,
            fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentCyan)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(settings, key = { it.key }) { item ->
                    ModernCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setting_${item.key}")
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(item.title, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    text = item.currentValue,
                                    color = AccentCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Text(item.description, color = TextMuted, fontSize = 11.sp)

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (item.key.contains("animation")) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                settingsRepository.updateSetting(item.key, "0.5")
                                                Toast.makeText(context, "${item.title} -> 0.5x (Fast)", Toast.LENGTH_SHORT).show()
                                                load()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CleanGreen, contentColor = BgBase),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text("0.5x", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = {
                                            scope.launch {
                                                settingsRepository.updateSetting(item.key, "0.0")
                                                Toast.makeText(context, "${item.title} -> 0.0x (Instant)", Toast.LENGTH_SHORT).show()
                                                load()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgBase),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text("0.0x", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            settingsRepository.resetSetting(item.key)
                                            Toast.makeText(context, "Reset to default: ${item.defaultValue}", Toast.LENGTH_SHORT).show()
                                            load()
                                        }
                                    },
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("Reset", fontSize = 11.sp, color = TextMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
