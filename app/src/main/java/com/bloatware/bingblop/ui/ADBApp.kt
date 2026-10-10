package com.bloatware.bingblop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bloatware.bingblop.data.repository.AppRepository
import com.bloatware.bingblop.data.repository.CleanerRepository
import com.bloatware.bingblop.data.repository.LogcatRepository
import com.bloatware.bingblop.data.repository.MonitorRepository
import com.bloatware.bingblop.data.repository.SettingsRepository
import com.bloatware.bingblop.data.repository.ShellRepository
import com.bloatware.bingblop.ui.components.AppHeader
import com.bloatware.bingblop.ui.screens.AppsScreen
import com.bloatware.bingblop.ui.screens.CleanerScreen
import com.bloatware.bingblop.ui.screens.InstallerScreen
import com.bloatware.bingblop.ui.screens.MonitorScreen
import com.bloatware.bingblop.ui.screens.SettingsScreen
import com.bloatware.bingblop.ui.screens.TerminalScreen
import com.bloatware.bingblop.ui.theme.AccentCyan
import com.bloatware.bingblop.ui.theme.BgBase
import com.bloatware.bingblop.ui.theme.BgCard
import com.bloatware.bingblop.ui.theme.BgSurface
import com.bloatware.bingblop.ui.theme.BorderGlass
import com.bloatware.bingblop.ui.theme.TextDim
import com.bloatware.bingblop.ui.theme.TextMuted

data class NavItem(
    val title: String,
    val icon: ImageVector,
    val testTag: String
)

@Composable
fun ADBApp(
    appRepository: AppRepository,
    settingsRepository: SettingsRepository,
    shellRepository: ShellRepository,
    cleanerRepository: CleanerRepository,
    monitorRepository: MonitorRepository,
    logcatRepository: LogcatRepository
) {
    var selectedIndex by remember { mutableIntStateOf(0) }

    // Navigation back press handler: return to Apps tab when on sub-screens
    BackHandler(enabled = selectedIndex != 0) {
        selectedIndex = 0
    }

    val navItems = listOf(
        NavItem("Apps", Icons.Default.Apps, "nav_apps"),
        NavItem("Cleaner", Icons.Default.CleaningServices, "nav_cleaner"),
        NavItem("Settings", Icons.Default.Tune, "nav_settings"),
        NavItem("Install", Icons.Default.InstallMobile, "nav_install"),
        NavItem("Console", Icons.Default.Terminal, "nav_terminal"),
        NavItem("Specs", Icons.Default.Memory, "nav_specs")
    )

    Scaffold(
        topBar = {
            AppHeader(
                modeLabel = "Privileged Mode",
                subtitle = when (selectedIndex) {
                    0 -> "Application & Debloat Manager"
                    1 -> "SD Maid Storage Optimizer"
                    2 -> "Hidden System Settings"
                    3 -> "Package Installer & Inspector"
                    4 -> "ADB Terminal & Live Logcat"
                    else -> "Telemetry & Power Controls"
                },
                onModeClick = { selectedIndex = 4 /* jump to console */ }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = BgSurface,
                modifier = Modifier
                    .border(width = 1.dp, color = BorderGlass)
                    .height(68.dp)
            ) {
                navItems.forEachIndexed { index, item ->
                    val isSelected = selectedIndex == index
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedIndex = index },
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title,
                                modifier = Modifier.size(19.dp)
                            )
                        },
                        label = {
                            Text(
                                text = item.title,
                                fontSize = 9.5.sp,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BgBase,
                            selectedTextColor = AccentCyan,
                            indicatorColor = AccentCyan,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextDim
                        ),
                        modifier = Modifier.testTag(item.testTag)
                    )
                }
            }
        },
        containerColor = BgBase
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedIndex) {
                0 -> AppsScreen(appRepository = appRepository)
                1 -> CleanerScreen(cleanerRepository = cleanerRepository)
                2 -> SettingsScreen(settingsRepository = settingsRepository, appRepository = appRepository)
                3 -> InstallerScreen()
                4 -> TerminalScreen(shellRepository = shellRepository, logcatRepository = logcatRepository)
                5 -> MonitorScreen(monitorRepository = monitorRepository, shellRepository = shellRepository, appRepository = appRepository)
            }
        }
    }
}
