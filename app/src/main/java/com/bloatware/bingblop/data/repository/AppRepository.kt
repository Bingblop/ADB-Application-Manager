package com.bloatware.bingblop.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.bloatware.bingblop.data.model.AppItem
import com.bloatware.bingblop.data.model.DebloatLevel
import com.bloatware.bingblop.data.model.DebloatPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class AppRepository(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager

    // Universal Android Debloater (UAD) database markers
    private val debloatRegistry = mapOf(
        "com.facebook.katana" to Pair(DebloatLevel.RECOMMENDED, "Facebook social client - background telemetry"),
        "com.facebook.system" to Pair(DebloatLevel.RECOMMENDED, "Facebook App Installer stub daemon"),
        "com.facebook.appmanager" to Pair(DebloatLevel.RECOMMENDED, "Facebook update manager background telemetry"),
        "com.facebook.services" to Pair(DebloatLevel.RECOMMENDED, "Facebook Background system service"),
        "com.google.android.apps.tachyon" to Pair(DebloatLevel.RECOMMENDED, "Google Duo/Meet pre-installed stub"),
        "com.google.android.videos" to Pair(DebloatLevel.RECOMMENDED, "Google TV pre-installed rental market"),
        "com.google.android.music" to Pair(DebloatLevel.RECOMMENDED, "Google Play Music legacy service"),
        "com.google.android.apps.subscriptions.red" to Pair(DebloatLevel.RECOMMENDED, "Google One subscription prompt service"),
        "com.google.android.feedback" to Pair(DebloatLevel.ADVANCED, "Android System Feedback collector"),
        "com.google.android.partnersetup" to Pair(DebloatLevel.ADVANCED, "Google Partner Setup client configuration"),
        "com.android.hotwordenrollment.okgoogle" to Pair(DebloatLevel.ADVANCED, "Voice activation listener background service"),
        "com.android.hotwordenrollment.xgoogle" to Pair(DebloatLevel.ADVANCED, "Voice activation secondary hotword model"),
        "com.amazon.mShop.android.shopping" to Pair(DebloatLevel.RECOMMENDED, "Amazon Shopping carrier preload"),
        "com.amazon.appmanager" to Pair(DebloatLevel.RECOMMENDED, "Amazon App Manager background service"),
        "com.samsung.android.bixby.agent" to Pair(DebloatLevel.RECOMMENDED, "Samsung Bixby Voice background service"),
        "com.samsung.android.bixby.service" to Pair(DebloatLevel.RECOMMENDED, "Samsung Bixby System provider"),
        "com.samsung.android.game.gamehome" to Pair(DebloatLevel.RECOMMENDED, "Samsung Gaming Hub promotion"),
        "com.sec.android.app.sbrowser" to Pair(DebloatLevel.ADVANCED, "Samsung Internet Browser"),
        "com.miui.analytics" to Pair(DebloatLevel.RECOMMENDED, "Xiaomi MIUI Analytics tracker daemon"),
        "com.miui.msa.global" to Pair(DebloatLevel.RECOMMENDED, "Xiaomi System Ad Service (MSA)"),
        "com.xiaomi.midrop" to Pair(DebloatLevel.ADVANCED, "Xiaomi Share background sync"),
        "com.huawei.hwid" to Pair(DebloatLevel.ADVANCED, "Huawei ID Account Sync"),
        "com.coloros.gamespace" to Pair(DebloatLevel.RECOMMENDED, "OPPO ColorOS Game Space overlay"),
        "com.heytap.mcs" to Pair(DebloatLevel.ADVANCED, "OPPO Push Message Service")
    )

    // Cached tracker definitions from Exodus privacy database
    private var cachedTrackers: List<Pair<String, List<String>>>? = null

    private fun loadTrackers(): List<Pair<String, List<String>>> {
        cachedTrackers?.let { return it }
        val list = mutableListOf<Pair<String, List<String>>>()
        try {
            val jsonStr = context.assets.open("trackers.json").bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)
            val trackersObj = root.optJSONObject("trackers")
            if (trackersObj != null) {
                val keys = trackersObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val item = trackersObj.getJSONObject(key)
                    val name = item.optString("name")
                    val sig = item.optString("code_signature")
                    if (name.isNotEmpty() && sig.isNotEmpty()) {
                        val signatures = sig.split("|").filter { it.isNotBlank() }
                        list.add(Pair(name, signatures))
                    }
                }
            }
        } catch (_: Exception) {}
        cachedTrackers = list
        return list
    }

    suspend fun getInstalledApps(): List<AppItem> = withContext(Dispatchers.IO) {
        // Query packages without heavy flags to prevent transaction buffer overload
        val packages: List<PackageInfo> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledPackages(0)
            }
        } catch (_: Exception) {
            emptyList()
        }

        val knownTrackers = loadTrackers()

        packages.map { pkgInfo ->
            val appInfo = pkgInfo.applicationInfo
            val isSystem = if (appInfo != null) {
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            } else false

            val isEnabled = if (appInfo != null) appInfo.enabled else true
            val appLabel = appInfo?.loadLabel(packageManager)?.toString() ?: pkgInfo.packageName
            val debloatEntry = debloatRegistry[pkgInfo.packageName]
            val icon = appInfo?.loadIcon(packageManager)

            // Approximate app apk size
            val apkSize = try {
                appInfo?.sourceDir?.let { File(it).length() } ?: 0L
            } catch (_: Exception) { 0L }

            // Match trackers against package name
            val matchedTrackers = knownTrackers.filter { (_, sigs) ->
                sigs.any { sig -> pkgInfo.packageName.contains(sig, ignoreCase = true) }
            }.map { it.first }

            AppItem(
                packageName = pkgInfo.packageName,
                appName = appLabel,
                versionName = pkgInfo.versionName ?: "1.0",
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pkgInfo.versionCode.toLong()
                },
                isSystemApp = isSystem,
                isEnabled = isEnabled,
                isSuspended = false,
                targetSdk = appInfo?.targetSdkVersion ?: 34,
                minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo?.minSdkVersion ?: 21 else 21,
                uid = appInfo?.uid ?: 0,
                apkPath = appInfo?.sourceDir ?: "",
                dataDir = appInfo?.dataDir ?: "",
                isBloatware = debloatEntry != null,
                bloatLevel = debloatEntry?.first ?: DebloatLevel.RECOMMENDED,
                bloatDescription = debloatEntry?.second,
                permissions = emptyList(),
                activities = emptyList(),
                trackers = matchedTrackers,
                appSize = apkSize,
                icon = icon
            )
        }.sortedBy { it.appName.lowercase() }
    }

    suspend fun getAppDetails(packageName: String): Pair<List<String>, List<String>> = withContext(Dispatchers.IO) {
        try {
            val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES
            val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, flags)
            }
            val perms = pkg.requestedPermissions?.toList() ?: emptyList()
            val acts = pkg.activities?.map { it.name } ?: emptyList()
            Pair(perms, acts)
        } catch (_: Exception) {
            Pair(emptyList(), emptyList())
        }
    }

    fun getDebloatList(installedApps: List<AppItem>): List<DebloatPackage> {
        val installedMap = installedApps.associateBy { it.packageName }
        return debloatRegistry.map { (pkg, pair) ->
            val app = installedMap[pkg]
            DebloatPackage(
                packageName = pkg,
                label = app?.appName ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() },
                level = pair.first,
                description = pair.second,
                isInstalled = app != null,
                isFrozen = app?.isEnabled == false,
                isSelected = app != null && app.isEnabled
            )
        }
    }

    suspend fun freezeApp(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        val cmd = "pm disable-user --user 0 $packageName"
        val output = runShellCommand(cmd)
        if (output.exitCode == 0 || output.stdout.contains("new state: disabled", ignoreCase = true)) {
            Result.success("Frozen: $packageName")
        } else {
            val err = output.stderr.ifEmpty { output.stdout.ifEmpty { "Permission denied" } }
            if (err.contains("Permission Denial", ignoreCase = true) || err.contains("SecurityException", ignoreCase = true)) {
                Result.failure(Exception("Privileged access required: Grant ADB or Shizuku permission to freeze packages"))
            } else {
                Result.success("Freeze executed: $err")
            }
        }
    }

    suspend fun unfreezeApp(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        val cmd = "pm enable $packageName"
        val output = runShellCommand(cmd)
        if (output.exitCode == 0 || output.stdout.contains("new state: enabled", ignoreCase = true)) {
            Result.success("Unfrozen: $packageName")
        } else {
            val err = output.stderr.ifEmpty { output.stdout.ifEmpty { "Permission denied" } }
            Result.success("Result: $err")
        }
    }

    suspend fun uninstallApp(packageName: String, userZero: Boolean = true): Result<String> = withContext(Dispatchers.IO) {
        val cmd = if (userZero) "pm uninstall --user 0 $packageName" else "pm uninstall $packageName"
        val output = runShellCommand(cmd)
        if (output.exitCode == 0 || output.stdout.contains("Success", ignoreCase = true)) {
            Result.success("Uninstalled: $packageName")
        } else {
            val err = output.stderr.ifEmpty { output.stdout }
            if (err.contains("Permission Denial", ignoreCase = true)) {
                Result.failure(Exception("Privileged access required: Root, Shizuku, or ADB connection needed to uninstall system apps"))
            } else {
                Result.success("Result: ${err.ifEmpty { "Finished" }}")
            }
        }
    }

    suspend fun reinstallApp(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        val cmd = "cmd package install-existing $packageName"
        val output = runShellCommand(cmd)
        Result.success("Restored: $packageName (${output.stdout.ifEmpty { output.stderr.ifEmpty { "Done" } }})")
    }

    suspend fun clearAppData(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        val cmd = "pm clear $packageName"
        val output = runShellCommand(cmd)
        Result.success("Cleared: ${output.stdout.ifEmpty { output.stderr.ifEmpty { "Done" } }}")
    }

    suspend fun forceStopApp(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        val cmd = "am force-stop $packageName"
        val output = runShellCommand(cmd)
        Result.success("Force stopped: $packageName (${output.stdout.ifEmpty { "Success" }})")
    }

    fun launchApp(packageName: String): Boolean {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    fun openAppDetails(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    private suspend fun runShellCommand(cmd: String): CommandResult = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val stdoutDef = async(Dispatchers.IO) { process.inputStream.bufferedReader().use { it.readText() }.trim() }
            val stderrDef = async(Dispatchers.IO) { process.errorStream.bufferedReader().use { it.readText() }.trim() }
            val stdout = stdoutDef.await()
            val stderr = stderrDef.await()
            val exit = process.waitFor()
            CommandResult(exit, stdout, stderr)
        } catch (e: Exception) {
            CommandResult(-1, "", e.localizedMessage ?: "Execution failed")
        } finally {
            process?.destroy()
        }
    }

    data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)
}
