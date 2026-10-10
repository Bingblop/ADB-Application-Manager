package com.bloatware.bingblop.data.repository

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.bloatware.bingblop.data.model.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MonitorRepository(private val context: Context) {

    suspend fun getDeviceInfo(): DeviceInfo = withContext(Dispatchers.IO) {
        // Memory
        val actMgr = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actMgr.getMemoryInfo(memInfo)
        val ramTotal = memInfo.totalMem
        val ramAvail = memInfo.availMem

        // Storage
        val dataDir = Environment.getDataDirectory()
        val stat = StatFs(dataDir.path)
        val storageTotal = stat.blockSizeLong * stat.blockCountLong
        val storageAvail = stat.blockSizeLong * stat.availableBlocksLong

        // Battery with Android 14+ safe receiver registration
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                    context,
                    null,
                    batteryFilter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(null, batteryFilter)
            }
        } catch (_: Exception) {
            null
        }

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 50
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val batteryPct = if (scale > 0) (level * 100) / scale else 50

        val statusInt = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val batteryStatus = when (statusInt) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full (100%)"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
            else -> "Healthy"
        }

        val healthInt = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val batteryHealth = when (healthInt) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheated"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Degraded"
            else -> "Good"
        }

        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 280) ?: 280
        val batteryTemp = tempTenths / 10.0f

        val kernel = try {
            System.getProperty("os.version") ?: File("/proc/version").readText().split(" ")[2]
        } catch (_: Exception) {
            "Linux 5.15-android"
        }

        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            Build.HARDWARE
        }

        val securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Build.VERSION.SECURITY_PATCH
        } else {
            "N/A"
        }

        DeviceInfo(
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            brand = Build.BRAND.replaceFirstChar { it.uppercase() },
            board = Build.BOARD,
            soc = soc.ifEmpty { Build.HARDWARE },
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = securityPatch,
            kernelVersion = kernel,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
            ramTotalBytes = ramTotal,
            ramAvailBytes = ramAvail,
            storageTotalBytes = storageTotal,
            storageAvailBytes = storageAvail,
            batteryPct = batteryPct,
            batteryStatus = batteryStatus,
            batteryHealth = batteryHealth,
            batteryTemp = batteryTemp,
            uptimeMillis = SystemClock.elapsedRealtime()
        )
    }
}
