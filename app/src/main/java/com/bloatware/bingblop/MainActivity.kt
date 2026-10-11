package com.bloatware.bingblop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.bloatware.bingblop.data.repository.AppRepository
import com.bloatware.bingblop.data.repository.CleanerRepository
import com.bloatware.bingblop.data.repository.LogcatRepository
import com.bloatware.bingblop.data.repository.MonitorRepository
import com.bloatware.bingblop.data.repository.SettingsRepository
import com.bloatware.bingblop.data.repository.ShellRepository
import com.bloatware.bingblop.ui.ADBApp
import com.bloatware.bingblop.ui.theme.ADBTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shellRepository = ShellRepository()
        val appRepository = AppRepository(applicationContext, shellRepository)
        val settingsRepository = SettingsRepository(shellRepository)
        val cleanerRepository = CleanerRepository(applicationContext)
        val monitorRepository = MonitorRepository(applicationContext, shellRepository)
        val logcatRepository = LogcatRepository(shellRepository)

        setContent {
            ADBTheme {
                ADBApp(
                    appRepository = appRepository,
                    settingsRepository = settingsRepository,
                    shellRepository = shellRepository,
                    cleanerRepository = cleanerRepository,
                    monitorRepository = monitorRepository,
                    logcatRepository = logcatRepository
                )
            }
        }
    }
}
