package com.bloatware.bingblop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.bloatware.bingblop.data.repository.AppRepository
import com.bloatware.bingblop.data.repository.CleanerRepository
import com.bloatware.bingblop.data.repository.LogcatRepository
import com.bloatware.bingblop.data.repository.SettingsRepository
import com.bloatware.bingblop.data.repository.ShellRepository
import com.bloatware.bingblop.ui.screens.ClassicWebViewScreen
import com.bloatware.bingblop.ui.theme.ADBTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shellRepository = ShellRepository()
        val appRepository = AppRepository(applicationContext, shellRepository)
        val settingsRepository = SettingsRepository(applicationContext, shellRepository)
        val cleanerRepository = CleanerRepository(applicationContext)
        val logcatRepository = LogcatRepository(shellRepository)

        setContent {
            ADBTheme {
                ClassicWebViewScreen(
                    appRepository = appRepository,
                    settingsRepository = settingsRepository,
                    shellRepository = shellRepository,
                    cleanerRepository = cleanerRepository,
                    logcatRepository = logcatRepository,
                    onSwitchToModern = null // Pure full-screen native WebView experience
                )
            }
        }
    }
}
