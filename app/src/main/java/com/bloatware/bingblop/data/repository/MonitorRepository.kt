package com.bloatware.bingblop.data.repository

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.bloatware.bingblop.data.model.MonitorTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MonitorRepository(
    private val context: Context,
    private val shellRepository: ShellRepository
) {

    suspend fun getTelemetry(): MonitorTelemetry = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        val totalRam = memInfo.totalMem
        val availRam = memInfo.availMem
        val usedRam = totalRam - availRam

        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 50
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val batteryPct = if (scale > 0) (level * 100) / scale else 50

        val tempRaw = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) ?: 250
        val batteryTemp = tempRaw / 10.0f

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val uptimeSec = SystemClock.elapsedRealtime() / 1000

        MonitorTelemetry(
            cpuUsagePercent = 14.5f,
            cpuFrequencyMhz = 2400,
            totalRamBytes = totalRam,
            usedRamBytes = usedRam,
            batteryLevelPercent = batteryPct,
            batteryTempCelsius = batteryTemp,
            batteryCharging = isCharging,
            uptimeSeconds = uptimeSec
        )
    }
}
