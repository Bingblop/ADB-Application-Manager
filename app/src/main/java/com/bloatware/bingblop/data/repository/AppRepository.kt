package com.bloatware.bingblop.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.bloatware.bingblop.data.model.DebloatCategory
import com.bloatware.bingblop.data.model.DebloatInfo
import com.bloatware.bingblop.data.model.DebloatProfile
import com.bloatware.bingblop.data.model.DebloatProfileEntry
import com.bloatware.bingblop.data.model.DebloatSafetyTier
import com.bloatware.bingblop.data.model.PackageItem
import com.bloatware.bingblop.data.model.ShellCommand
import com.bloatware.bingblop.data.model.ShellMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AppRepository(
    private val context: Context,
    private val shellRepository: ShellRepository
) {

    private var uadFileCache: Map<String, DebloatInfo>? = null

    private fun loadUadFileCache(): Map<String, DebloatInfo> {
        val cached = uadFileCache
        if (cached != null) return cached

        val file = File(context.filesDir, "uad_lists.json")
        if (!file.exists()) {
            uadFileCache = emptyMap()
            return emptyMap()
        }

        val map = mutableMapOf<String, DebloatInfo>()
        try {
            val content = file.readText(Charsets.UTF_8)
            val json = JSONObject(content)
            val keys = json.keys()
            while (keys.hasNext()) {
                val pkg = keys.next()
                val obj = json.optJSONObject(pkg) ?: continue
                val rawList = obj.optString("list", "Misc")
                val category = when {
                    rawList.contains("Samsung", ignoreCase = true) -> DebloatCategory.SAMSUNG
                    rawList.contains("Xiaomi", ignoreCase = true) -> DebloatCategory.XIAOMI
                    rawList.contains("Google", ignoreCase = true) || rawList.contains("Aosp", ignoreCase = true) -> DebloatCategory.GOOGLE
                    rawList.contains("Carrier", ignoreCase = true) -> DebloatCategory.CARRIER
                    rawList.contains("Facebook", ignoreCase = true) || rawList.contains("Amazon", ignoreCase = true) || rawList.contains("Microsoft", ignoreCase = true) -> DebloatCategory.TELEMETRY
                    else -> DebloatCategory.OEM
                }
                val rawRemoval = obj.optString("removal", "Recommended")
                val safetyTier = DebloatSafetyTier.fromUad(rawRemoval)
                val description = obj.optString("description", "")
                val deps = mutableListOf<String>()
                val depsArray = obj.optJSONArray("dependencies")
                if (depsArray != null) {
                    for (i in 0 until depsArray.length()) {
                        deps.add(depsArray.getString(i))
                    }
                }
                map[pkg] = DebloatInfo(
                    packageName = pkg,
                    category = category,
                    safetyTier = safetyTier,
                    summary = rawList,
                    description = description,
                    dependencies = deps
                )
            }
        } catch (_: Exception) {}

        uadFileCache = map
        return map
    }

    suspend fun loadPackages(
        filter: String = "",
        showSystemOnly: Boolean = false,
        categoryFilter: DebloatCategory = DebloatCategory.ALL,
        safetyFilter: DebloatSafetyTier? = null,
        bloatOnly: Boolean = false
    ): List<PackageItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val result = mutableListOf<PackageItem>()
        val externalUad = loadUadFileCache()

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
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode.toLong()
                }
            } catch (_: Exception) {}

            val apkSize = try {
                File(app.sourceDir ?: "").length()
            } catch (_: Exception) {
                0L
            }

            // Find matching debloat information from curated database or UAD-NG cache
            val debloatInfo = DebloatDatabase.find(app.packageName) ?: externalUad[app.packageName]
            val isBloat = debloatInfo != null

            if (bloatOnly && !isBloat) continue

            if (categoryFilter != DebloatCategory.ALL) {
                if (debloatInfo == null || debloatInfo.category != categoryFilter) continue
            }

            if (safetyFilter != null) {
                if (debloatInfo == null || debloatInfo.safetyTier != safetyFilter) continue
            }

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
                    isVendorBloatware = isBloat,
                    vendorPresetName = debloatInfo?.category?.displayName,
                    debloatInfo = debloatInfo,
                    isSelected = false
                )
            )
        }

        result.sortedWith(
            compareByDescending<PackageItem> { it.isVendorBloatware }
                .thenBy { it.debloatInfo?.safetyTier?.ordinal ?: 99 }
                .thenBy { it.appName.lowercase() }
        )
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

    // ==========================================
    // BATCH OPERATIONS & SAFE STAGING
    // ==========================================

    suspend fun batchFreeze(packageNames: List<String>): List<ShellCommand> = withContext(Dispatchers.IO) {
        val commands = mutableListOf<ShellCommand>()
        for (pkg in packageNames) {
            val res = shellRepository.executeCommand("pm disable-user --user 0 $pkg", ShellMode.LOCAL_SHELL)
            commands.add(res)
        }
        commands
    }

    suspend fun batchUnfreeze(packageNames: List<String>): List<ShellCommand> = withContext(Dispatchers.IO) {
        val commands = mutableListOf<ShellCommand>()
        for (pkg in packageNames) {
            val res = shellRepository.executeCommand("pm enable $pkg", ShellMode.LOCAL_SHELL)
            commands.add(res)
        }
        commands
    }

    suspend fun batchUninstall(packageNames: List<String>, keepData: Boolean = false): List<ShellCommand> = withContext(Dispatchers.IO) {
        val commands = mutableListOf<ShellCommand>()
        val keepFlag = if (keepData) "-k " else ""
        for (pkg in packageNames) {
            val res = shellRepository.executeCommand("pm uninstall $keepFlag--user 0 $pkg", ShellMode.LOCAL_SHELL)
            commands.add(res)
        }
        commands
    }

    suspend fun batchReinstall(packageNames: List<String>): List<ShellCommand> = withContext(Dispatchers.IO) {
        val commands = mutableListOf<ShellCommand>()
        for (pkg in packageNames) {
            val res = shellRepository.executeCommand("cmd package install-existing $pkg", ShellMode.LOCAL_SHELL)
            commands.add(res)
        }
        commands
    }

    // ==========================================
    // DEBLOAT PROFILE EXPORT & IMPORT (JSON)
    // ==========================================

    fun exportDebloatProfileJson(
        profileName: String,
        packages: List<PackageItem>,
        defaultAction: String = "DISABLE"
    ): String {
        val root = JSONObject()
        root.put("profileName", profileName)
        root.put("createdAt", System.currentTimeMillis())
        root.put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
        root.put("version", "1.0")

        val array = JSONArray()
        for (pkg in packages) {
            val entry = JSONObject()
            entry.put("package", pkg.packageName)
            entry.put("appName", pkg.appName)
            entry.put("action", defaultAction)
            entry.put("safety", pkg.debloatInfo?.safetyTier?.name ?: "SAFE")
            entry.put("category", pkg.debloatInfo?.category?.name ?: "OEM")
            entry.put("comment", pkg.debloatInfo?.summary ?: "")
            array.put(entry)
        }
        root.put("packages", array)
        return root.toString(2)
    }

    fun importDebloatProfileJson(jsonStr: String): DebloatProfile {
        val json = JSONObject(jsonStr)
        val name = json.optString("profileName", "Imported Profile")
        val desc = json.optString("description", "")
        val createdAt = json.optLong("createdAt", System.currentTimeMillis())
        val device = json.optString("deviceModel", "")

        val entries = mutableListOf<DebloatProfileEntry>()
        val array = json.optJSONArray("packages") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            entries.add(
                DebloatProfileEntry(
                    packageName = item.getString("package"),
                    action = item.optString("action", "DISABLE"),
                    safety = item.optString("safety", "SAFE"),
                    comment = item.optString("comment", "")
                )
            )
        }

        return DebloatProfile(
            name = name,
            description = desc,
            createdAt = createdAt,
            deviceModel = device,
            packages = entries
        )
    }
}
