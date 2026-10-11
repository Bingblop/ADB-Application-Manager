package com.bloatware.bingblop.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.bloatware.bingblop.data.model.PackageItem
import com.bloatware.bingblop.data.model.ShellCommand
import com.bloatware.bingblop.data.model.ShellMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AppRepository(
    private val context: Context,
    private val shellRepository: ShellRepository
) {

    private val vendorBloatPresets = mapOf(
        "google" to setOf(
            "com.google.android.apps.tachyon",
            "com.google.android.music",
            "com.google.android.videos",
            "com.google.android.apps.docs",
            "com.google.android.apps.magazines",
            "com.google.android.apps.subscriptions.red"
        ),
        "samsung" to setOf(
            "com.samsung.android.bixby.agent",
            "com.samsung.android.bixby.wakeup",
            "com.samsung.android.app.spage",
            "com.samsung.android.game.gamehome",
            "com.sec.android.app.sbrowser",
            "com.samsung.android.voc"
        ),
        "xiaomi" to setOf(
            "com.miui.analytics",
            "com.miui.msa.global",
            "com.miui.yellowpage",
            "com.xiaomi.mipicks",
            "com.miui.bugreport"
        )
    )

    suspend fun loadPackages(
        filter: String = "",
        showSystemOnly: Boolean = false
    ): List<PackageItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val result = mutableListOf<PackageItem>()

        for (app in installed) {
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (showSystemOnly && !isSystem) continue

            val name = try {
                pm.getApplicationLabel(app).toString()
            } catch (_: Exception) {
                app.packageName
            }

            if (filter.isNotEmpty()) {
                val matchesName = name.contains(filter, ignoreCase = true)
                val matchesPkg = app.packageName.contains(filter, ignoreCase = true)
                if (!matchesName && !matchesPkg) continue
            }

            var versionName = "1.0"
            var versionCode = 1L
            try {
                val pInfo = pm.getPackageInfo(app.packageName, 0)
                versionName = pInfo.versionName ?: "1.0"
                versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    pInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode.toLong()
                }
            } catch (_: Exception) {}

            val apkSize = try {
                File(app.sourceDir).length()
            } catch (_: Exception) {
                0L
            }

            val matchingPreset = vendorBloatPresets.entries.firstOrNull { it.value.contains(app.packageName) }

            result.add(
                PackageItem(
                    packageName = app.packageName,
                    appName = name,
                    versionName = versionName,
                    versionCode = versionCode,
                    isSystemApp = isSystem,
                    isEnabled = app.enabled,
                    apkPath = app.sourceDir ?: "",
                    sizeBytes = apkSize,
                    isVendorBloatware = matchingPreset != null,
                    vendorPresetName = matchingPreset?.key
                )
            )
        }

        result.sortedWith(compareByDescending<PackageItem> { it.isVendorBloatware }.thenBy { it.appName.lowercase() })
    }

    suspend fun freezePackage(packageName: String): ShellCommand {
        return shellRepository.executeCommand("pm disable-user --user 0 $packageName", ShellMode.LOCAL_SHELL)
    }

    suspend fun unfreezePackage(packageName: String): ShellCommand {
        return shellRepository.executeCommand("pm enable $packageName", ShellMode.LOCAL_SHELL)
    }

    suspend fun uninstallPackage(packageName: String, keepData: Boolean = false): ShellCommand {
        val keepFlag = if (keepData) "-k " else ""
        return shellRepository.executeCommand("pm uninstall $keepFlag--user 0 $packageName", ShellMode.LOCAL_SHELL)
    }

    suspend fun reinstallPackage(packageName: String): ShellCommand {
        return shellRepository.executeCommand("cmd package install-existing $packageName", ShellMode.LOCAL_SHELL)
    }

    fun getVendorPresets(): Map<String, Set<String>> = vendorBloatPresets
}
