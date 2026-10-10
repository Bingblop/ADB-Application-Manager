package com.bloatware.bingblop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bloatware.bingblop.data.repository.AppRepository
import com.bloatware.bingblop.data.repository.CleanerRepository
import com.bloatware.bingblop.data.repository.MonitorRepository
import com.bloatware.bingblop.data.repository.SettingsRepository
import com.bloatware.bingblop.data.repository.ShellRepository
import com.bloatware.bingblop.ui.ADBApp
import com.bloatware.bingblop.ui.theme.ADBAppTheme

class MainActivity : ComponentActivity() {

    private lateinit var appRepository: AppRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var shellRepository: ShellRepository
    private lateinit var cleanerRepository: CleanerRepository
    private lateinit var monitorRepository: MonitorRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appRepository = AppRepository(this)
        settingsRepository = SettingsRepository(this)
        shellRepository = ShellRepository()
        cleanerRepository = CleanerRepository(this)
        monitorRepository = MonitorRepository(this)

        setContent {
            ADBAppTheme {
                ADBApp(
                    appRepository = appRepository,
                    settingsRepository = settingsRepository,
                    shellRepository = shellRepository,
                    cleanerRepository = cleanerRepository,
                    monitorRepository = monitorRepository
                )
            }
        }
    }
}
