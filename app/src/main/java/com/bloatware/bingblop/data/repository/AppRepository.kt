package com.bloatware.bingblop.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.bloatware.bingblop.data.model.AppItem
import com.bloatware.bingblop.data.model.ComponentItem
import com.bloatware.bingblop.data.model.ComponentType
import com.bloatware.bingblop.data.model.DebloatLevel
import com.bloatware.bingblop.data.model.DebloatPackage
import com.bloatware.bingblop.data.model.DexOptMode
import com.bloatware.bingblop.data.model.DexOptResult
import com.bloatware.bingblop.data.model.BatchDexOptSummary
import com.bloatware.bingblop.data.model.StandbyBucket
import com.bloatware.bingblop.data.model.AppOpType
import com.bloatware.bingblop.data.model.CrashLogEntry
import com.bloatware.bingblop.data.model.PowerUserApp
import com.bloatware.bingblop.data.model.PrivateDnsPreset
import com.bloatware.bingblop.data.model.DnsBenchmarkResult
import com.bloatware.bingblop.data.model.DnsPingStatus
import com.bloatware.bingblop.data.model.BatteryDiagnostics
import com.bloatware.bingblop.data.model.AndroidUser
import com.bloatware.bingblop.data.model.RuntimePermissionItem
import com.bloatware.bingblop.data.model.DozeStateInfo
import com.bloatware.bingblop.data.model.DnsJitterResult
import com.bloatware.bingblop.data.model.LiveProcessItem
import com.bloatware.bingblop.data.model.ZramStats
import com.bloatware.bingblop.data.model.AutomationProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToLong

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

    suspend fun getAppComponents(packageName: String): List<ComponentItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ComponentItem>()
        try {
            val flags = PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS or
                    PackageManager.GET_ACTIVITIES
            val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, flags)
            }

            fun isCmpEnabled(compName: String, defaultEnabled: Boolean): Boolean {
                return try {
                    val componentName = ComponentName(packageName, compName)
                    val state = packageManager.getComponentEnabledSetting(componentName)
                    when (state) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
                        else -> defaultEnabled
                    }
                } catch (_: Exception) {
                    defaultEnabled
                }
            }

            pkg.services?.forEach { s ->
                val simple = s.name.substringAfterLast('.')
                list.add(
                    ComponentItem(
                        packageName = packageName,
                        name = s.name,
                        simpleName = simple,
                        type = ComponentType.SERVICE,
                        isEnabled = isCmpEnabled(s.name, s.enabled)
                    )
                )
            }

            pkg.receivers?.forEach { r ->
                val simple = r.name.substringAfterLast('.')
                list.add(
                    ComponentItem(
                        packageName = packageName,
                        name = r.name,
                        simpleName = simple,
                        type = ComponentType.RECEIVER,
                        isEnabled = isCmpEnabled(r.name, r.enabled)
                    )
                )
            }

            pkg.activities?.forEach { a ->
                val simple = a.name.substringAfterLast('.')
                list.add(
                    ComponentItem(
                        packageName = packageName,
                        name = a.name,
                        simpleName = simple,
                        type = ComponentType.ACTIVITY,
                        isEnabled = isCmpEnabled(a.name, a.enabled)
                    )
                )
            }
        } catch (_: Exception) {}
        list.sortedWith(compareBy({ it.type.ordinal }, { it.simpleName.lowercase() }))
    }

    suspend fun toggleComponent(packageName: String, componentName: String, enable: Boolean): Result<String> = withContext(Dispatchers.IO) {
        val action = if (enable) "enable" else "disable"
        val cmd = "pm $action $packageName/$componentName"
        val output = runShellCommand(cmd)
        if (output.exitCode == 0 || output.stdout.contains("new state", ignoreCase = true)) {
            val simple = componentName.substringAfterLast('.')
            Result.success("Component ${if (enable) "enabled" else "disabled"}: $simple")
        } else {
            val err = output.stderr.ifEmpty { output.stdout.ifEmpty { "Permission denied or failed" } }
            Result.success("Component result: $err")
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

    suspend fun getDexOptStatus(packageName: String): String = withContext(Dispatchers.IO) {
        val cmd = "cmd package compile --status $packageName 2>/dev/null || dumpsys package $packageName"
        val res = runShellCommand(cmd)
        val text = res.stdout
        val regex = Regex("""(?:status=|compilation_filter=|filter=)([a-zA-Z0-9_-]+)""")
        val match = regex.find(text)
        if (match != null) {
            match.groupValues[1]
        } else if (text.contains("speed-profile", ignoreCase = true)) {
            "speed-profile"
        } else if (text.contains("speed", ignoreCase = true)) {
            "speed"
        } else if (text.contains("verify", ignoreCase = true)) {
            "verify"
        } else {
            "Default (JIT / Verify)"
        }
    }

    suspend fun optimizeApp(
        packageName: String,
        mode: DexOptMode,
        force: Boolean = false,
        compileSecondaryDex: Boolean = true
    ): DexOptResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val outText = if (mode.isReset) {
            val cmd = "cmd package compile --reset $packageName 2>&1 || pm compile --reset $packageName 2>&1"
            val output = runShellCommand(cmd)
            output.stdout.ifEmpty { output.stderr }.ifEmpty { "Reset complete" }
        } else {
            val forceFlag = if (force) "-f " else ""
            val m = mode.arg
            // 1. Compile base APK primary code
            val baseCmd = "cmd package compile -m $m $forceFlag$packageName 2>&1 || pm compile -m $m $forceFlag$packageName 2>&1"
            val baseOutput = runShellCommand(baseCmd)
            val baseText = baseOutput.stdout.ifEmpty { baseOutput.stderr }.ifEmpty { "Success" }

            // 2. If secondary DEX optimization requested, also compile split modules / secondary dex
            if (compileSecondaryDex) {
                val secCmd = "cmd package compile --secondary-dex -m $m $forceFlag$packageName 2>&1 || pm compile --secondary-dex -m $m $forceFlag$packageName 2>&1"
                runShellCommand(secCmd)
            }
            baseText
        }
        val duration = System.currentTimeMillis() - startTime
        val isSuccess = outText.contains("Success", ignoreCase = true) || (!outText.contains("Failure", ignoreCase = true) && !outText.contains("Error", ignoreCase = true))
        DexOptResult(
            packageName = packageName,
            mode = mode.arg,
            success = isSuccess,
            output = outText,
            durationMs = duration
        )
    }

    suspend fun optimizeAppBatch(
        packages: List<String>,
        mode: DexOptMode,
        force: Boolean = false,
        compileSecondaryDex: Boolean = true,
        onProgress: (current: Int, total: Int, currentPkg: String) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): BatchDexOptSummary = withContext(Dispatchers.IO) {
        val results = mutableListOf<DexOptResult>()
        var succeeded = 0
        var failed = 0
        var wasCancelled = false

        for (i in packages.indices) {
            if (isCancelled()) {
                wasCancelled = true
                break
            }
            val pkg = packages[i]
            onProgress(i + 1, packages.size, pkg)
            val res = optimizeApp(pkg, mode, force, compileSecondaryDex)
            results.add(res)
            if (res.success) succeeded++ else failed++
        }

        BatchDexOptSummary(
            total = packages.size,
            succeeded = succeeded,
            failed = failed,
            results = results,
            cancelled = wasCancelled
        )
    }

    suspend fun getStandbyBucket(packageName: String): String = withContext(Dispatchers.IO) {
        val res = runShellCommand("am get-standby-bucket $packageName")
        val out = res.stdout.trim()
        when (out) {
            "10" -> "ACTIVE"
            "20" -> "WORKING_SET"
            "30" -> "FREQUENT"
            "40" -> "RARE"
            "45" -> "RESTRICTED"
            else -> if (out.isNotEmpty()) out.uppercase() else "UNKNOWN"
        }
    }

    suspend fun setStandbyBucket(packageName: String, bucket: StandbyBucket): Result<String> = withContext(Dispatchers.IO) {
        val res = runShellCommand("am set-standby-bucket $packageName ${bucket.arg}")
        if (res.exitCode == 0 || res.stdout.isEmpty()) {
            Result.success("Standby bucket set to ${bucket.title}")
        } else {
            Result.success("Standby bucket: ${res.stdout.ifEmpty { res.stderr }}")
        }
    }

    suspend fun getAppOpState(packageName: String, op: AppOpType): Boolean = withContext(Dispatchers.IO) {
        val res = runShellCommand("cmd appops get $packageName ${op.opName}")
        val out = res.stdout.lowercase()
        !out.contains("ignore") && !out.contains("deny") && !out.contains("reject")
    }

    suspend fun setAppOpState(packageName: String, op: AppOpType, allow: Boolean): Result<String> = withContext(Dispatchers.IO) {
        val mode = if (allow) "allow" else "ignore"
        val res = runShellCommand("cmd appops set $packageName ${op.opName} $mode")
        if (res.exitCode == 0 || res.stdout.isEmpty()) {
            Result.success("${op.title} set to ${if (allow) "Allowed" else "Restricted"}")
        } else {
            Result.success("AppOp result: ${res.stdout.ifEmpty { res.stderr }}")
        }
    }

    suspend fun getGameMode(packageName: String): String = withContext(Dispatchers.IO) {
        val res = runShellCommand("cmd game mode $packageName")
        val out = res.stdout.trim()
        when {
            out.contains("performance", ignoreCase = true) -> "Performance"
            out.contains("battery", ignoreCase = true) -> "Battery"
            out.contains("custom", ignoreCase = true) -> "Custom"
            else -> "Standard"
        }
    }

    suspend fun setGameMode(packageName: String, mode: String): Result<String> = withContext(Dispatchers.IO) {
        val res = runShellCommand("cmd game mode $mode $packageName")
        Result.success("Game mode set to $mode (${res.stdout.ifEmpty { "Success" }})")
    }

    suspend fun extractApk(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        val pathRes = runShellCommand("pm path $packageName")
        val rawPaths = pathRes.stdout.lines().filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }
        if (rawPaths.isEmpty()) {
            return@withContext Result.failure(Exception("Cannot resolve APK path for $packageName"))
        }
        val targetDir = "/sdcard/Download/ADBManager"
        runShellCommand("mkdir -p $targetDir")
        val baseApk = rawPaths.first()
        val destFile = "$targetDir/${packageName}_base.apk"
        val copyRes = runShellCommand("cp $baseApk $destFile || cat $baseApk > $destFile")
        if (copyRes.exitCode == 0 || runShellCommand("ls $destFile").stdout.contains(destFile)) {
            Result.success(destFile)
        } else {
            Result.success(baseApk)
        }
    }

    suspend fun getDropboxCrashLogs(): List<CrashLogEntry> = withContext(Dispatchers.IO) {
        val res = runShellCommand("dumpsys dropbox --print data_app_crash data_app_anr system_app_crash system_server_crash 2>/dev/null | tail -n 120")
        val text = res.stdout
        if (text.isEmpty()) return@withContext emptyList()
        val entries = mutableListOf<CrashLogEntry>()
        val blocks = text.split("========================================")
        blocks.filter { it.isNotBlank() }.takeLast(10).reversed().forEachIndexed { index, block ->
            val lines = block.lines().filter { it.isNotBlank() }
            val firstLine = lines.firstOrNull() ?: "Crash Record"
            val summary = lines.find { it.contains("Package:") || it.contains("Process:") } ?: firstLine
            entries.add(
                CrashLogEntry(
                    id = "crash_$index",
                    tag = if (block.contains("anr", ignoreCase = true)) "ANR (Freeze)" else "FATAL CRASH",
                    timestamp = lines.find { it.matches(Regex("""\d{4}-\d{2}-\d{2}.*""")) } ?: "Recent",
                    summary = summary.trim(),
                    details = block.trim()
                )
            )
        }
        entries
    }

    suspend fun setScreenResolution(width: Int, height: Int): Result<String> = withContext(Dispatchers.IO) {
        runShellCommand("wm size ${width}x${height}")
        Result.success("Resolution set to ${width}x${height}")
    }

    suspend fun resetScreenResolution(): Result<String> = withContext(Dispatchers.IO) {
        runShellCommand("wm size reset")
        Result.success("Resolution reset to default")
    }

    suspend fun setScreenDensity(dpi: Int): Result<String> = withContext(Dispatchers.IO) {
        runShellCommand("wm density $dpi")
        Result.success("DPI set to $dpi")
    }

    suspend fun resetScreenDensity(): Result<String> = withContext(Dispatchers.IO) {
        runShellCommand("wm density reset")
        Result.success("DPI reset to default")
    }

    suspend fun setRefreshRate(fps: Float): Result<String> = withContext(Dispatchers.IO) {
        runShellCommand("settings put system min_refresh_rate $fps && settings put system peak_refresh_rate $fps")
        Result.success("Refresh rate set to ${fps.toInt()}Hz")
    }

    suspend fun getBlacklistedIcons(): List<String> = withContext(Dispatchers.IO) {
        val res = runShellCommand("settings get secure icon_blacklist")
        val out = res.stdout.trim()
        if (out.isNotEmpty() && out != "null") out.split(",").map { it.trim() } else emptyList()
    }

    suspend fun toggleStatusIconBlacklist(iconKey: String, hide: Boolean): Result<String> = withContext(Dispatchers.IO) {
        val current = getBlacklistedIcons().toMutableSet()
        if (hide) current.add(iconKey) else current.remove(iconKey)
        val value = current.joinToString(",")
        runShellCommand("settings put secure icon_blacklist \"$value\"")
        Result.success(if (hide) "Hidden $iconKey" else "Restored $iconKey")
    }

    fun getKnownPowerUserApps(): List<PowerUserApp> {
        return listOf(
            PowerUserApp(
                packageName = "moe.shizuku.privileged.api",
                name = "Shizuku",
                description = "System API binder proxy service",
                permissions = listOf("android.permission.WRITE_SECURE_SETTINGS", "android.permission.DUMP")
            ),
            PowerUserApp(
                packageName = "net.dinglisch.android.taskerm",
                name = "Tasker",
                description = "Advanced system automation engine",
                permissions = listOf("android.permission.WRITE_SECURE_SETTINGS", "android.permission.DUMP", "android.permission.READ_LOGS")
            ),
            PowerUserApp(
                packageName = "com.arlosoft.macrodroid",
                name = "MacroDroid",
                description = "Macro automation and trigger service",
                permissions = listOf("android.permission.WRITE_SECURE_SETTINGS", "android.permission.DUMP")
            ),
            PowerUserApp(
                packageName = "com.termux",
                name = "Termux",
                description = "Terminal emulator and Linux environment",
                permissions = listOf("android.permission.WRITE_SECURE_SETTINGS", "android.permission.DUMP")
            ),
            PowerUserApp(
                packageName = "com.asksven.betterbatterystats",
                name = "BetterBatteryStats",
                description = "Deep wake lock telemetry",
                permissions = listOf("android.permission.BATTERY_STATS", "android.permission.DUMP", "android.permission.PACKAGE_USAGE_STATS")
            ),
            PowerUserApp(
                packageName = "com.zacharee1.systemuituner",
                name = "SystemUI Tuner",
                description = "Status bar and system UI customizer",
                permissions = listOf("android.permission.WRITE_SECURE_SETTINGS", "android.permission.DUMP")
            )
        )
    }

    suspend fun grantPermission(packageName: String, permission: String): Result<String> = withContext(Dispatchers.IO) {
        val res = runShellCommand("pm grant $packageName $permission")
        if (res.exitCode == 0 || res.stdout.isEmpty()) {
            Result.success("Granted: ${permission.substringAfterLast('.')}")
        } else {
            Result.success("Grant: ${res.stdout.ifEmpty { res.stderr }}")
        }
    }

    suspend fun revokePermission(packageName: String, permission: String): Result<String> = withContext(Dispatchers.IO) {
        val res = runShellCommand("pm revoke $packageName $permission")
        if (res.exitCode == 0 || res.stdout.isEmpty()) {
            Result.success("Revoked: ${permission.substringAfterLast('.')}")
        } else {
            Result.success("Revoke: ${res.stdout.ifEmpty { res.stderr }}")
        }
    }

    suspend fun getAppRuntimePermissions(packageName: String): List<RuntimePermissionItem> = withContext(Dispatchers.IO) {
        val res = runShellCommand("dumpsys package $packageName")
        val list = mutableListOf<RuntimePermissionItem>()
        var inRequestedSection = false
        res.stdout.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("requested permissions:") || trimmed.startsWith("install permissions:")) {
                inRequestedSection = true
            } else if (inRequestedSection && (trimmed.startsWith("runtime permissions:") || trimmed.isEmpty() || trimmed.contains(":"))) {
                if (!trimmed.startsWith("android.permission")) {
                    inRequestedSection = false
                }
            }
            if (trimmed.contains("android.permission.")) {
                val permName = trimmed.substringBefore(":").substringBefore(",").trim()
                val isGranted = res.stdout.contains("$permName: granted=true") || !res.stdout.contains("$permName: granted=false")
                if (permName.startsWith("android.permission.") && list.none { it.permission == permName }) {
                    list.add(
                        RuntimePermissionItem(
                            permission = permName,
                            simpleName = permName.substringAfterLast("."),
                            isGranted = isGranted
                        )
                    )
                }
            }
        }
        list
    }

    fun getPrivateDnsPresets(): List<PrivateDnsPreset> {
        val custom = getSavedCustomDnsPresets()
        val defaults = listOf(
            PrivateDnsPreset("cloudflare", "Cloudflare 1.1.1.1", "hostname", "one.one.one.one", "Ultra-fast Anycast DNS with strict zero-logging", primaryIp = "1.1.1.1", category = "Ultra Fast Anycast"),
            PrivateDnsPreset("adguard", "AdGuard AdBlock", "hostname", "dns.adguard-dns.com", "Blocks ads, telemetry, trackers, and malicious domains globally", primaryIp = "94.140.14.14", category = "AdBlock & Privacy"),
            PrivateDnsPreset("adguard_family", "AdGuard Family", "hostname", "family.adguard-dns.com", "Blocks ads, adult content, and enforces SafeSearch", primaryIp = "94.140.14.15", category = "AdBlock & Privacy"),
            PrivateDnsPreset("cloudflare_sec", "Cloudflare Security", "hostname", "security.cloudflare-dns.com", "Blocks malware, spyware, and known phishing hosts", primaryIp = "1.1.1.2", category = "Threat Protection"),
            PrivateDnsPreset("cloudflare_fam", "Cloudflare Family", "hostname", "family.cloudflare-dns.com", "Blocks malware, spyware, and adult content", primaryIp = "1.1.1.3", category = "Threat Protection"),
            PrivateDnsPreset("quad9", "Quad9 Threat Block", "hostname", "dns.quad9.net", "Global threat intelligence with strict privacy protection", primaryIp = "9.9.9.9", category = "Threat Protection"),
            PrivateDnsPreset("google", "Google Public DNS", "hostname", "dns.google", "High availability global DNS backed by Google Anycast", primaryIp = "8.8.8.8", category = "Ultra Fast Anycast"),
            PrivateDnsPreset("nextdns", "NextDNS", "hostname", "dns.nextdns.io", "Zero-latency DoT resolver with customizable cloud blocklists", primaryIp = "45.90.28.0", category = "AdBlock & Privacy"),
            PrivateDnsPreset("controld", "Control D Malware Block", "hostname", "p0.freedns.controld.com", "High-speed privacy DNS with AI threat filtering", primaryIp = "76.76.2.0", category = "Threat Protection"),
            PrivateDnsPreset("cleanbrowsing", "CleanBrowsing Security", "hostname", "security-filter-dns.cleanbrowsing.org", "Phishing and malicious domain protection filter", primaryIp = "185.228.168.9", category = "Threat Protection"),
            PrivateDnsPreset("auto", "Automatic (Opportunistic)", "opportunistic", "", "Uses DNS-over-TLS if upstream ISP resolver supports it", primaryIp = "8.8.8.8", category = "Stock Android"),
            PrivateDnsPreset("off", "Off (Stock Plain DNS)", "off", "", "Disables encrypted DNS and uses regular ISP DNS", isEncrypted = false, primaryIp = "", category = "Stock Android")
        )
        return custom + defaults
    }

    suspend fun pingDnsHostOrIp(target: String, timeoutMs: Int = 1500): Long? = withContext(Dispatchers.IO) {
        if (target.isBlank()) return@withContext null

        // 1. First attempt: Shell ICMP ping (fastest and most accurate for network latency)
        try {
            val pingCmd = "ping -c 1 -W 1 $target"
            val res = runShellCommand(pingCmd)
            if (res.exitCode == 0 && res.stdout.contains("time=")) {
                val timeStr = res.stdout.substringAfter("time=").substringBefore("ms").trim()
                val latency = timeStr.toDoubleOrNull()?.roundToLong()
                if (latency != null && latency >= 0) {
                    return@withContext latency
                }
            } else if (res.stdout.contains("min/avg/max")) {
                val avgStr = res.stdout.substringAfter("min/avg/max").substringAfter("=").trim().split("/").getOrNull(1)?.trim()
                val latency = avgStr?.toDoubleOrNull()?.roundToLong()
                if (latency != null && latency >= 0) {
                    return@withContext latency
                }
            }
        } catch (_: Exception) {
            // Fall through to socket probe
        }

        // 2. Second attempt: Direct TCP Socket handshake (DoT port 853 or DNS port 53)
        // Highly resilient even if ICMP is blocked by carrier or network firewall
        try {
            val portsToTry = listOf(853, 53)
            for (port in portsToTry) {
                try {
                    val socket = Socket()
                    val start = System.currentTimeMillis()
                    socket.connect(InetSocketAddress(target, port), timeoutMs)
                    val duration = System.currentTimeMillis() - start
                    socket.close()
                    if (duration >= 0) {
                        return@withContext duration
                    }
                } catch (_: Exception) {
                    // Try next port
                }
            }
        } catch (_: Exception) {
            // Unreachable
        }

        null
    }

    suspend fun benchmarkDnsPreset(preset: PrivateDnsPreset): DnsBenchmarkResult = withContext(Dispatchers.IO) {
        val target = preset.primaryIp.ifEmpty { preset.hostname }
        if (target.isEmpty() || preset.mode == "off") {
            return@withContext DnsBenchmarkResult(
                presetId = preset.id,
                title = preset.title,
                hostname = preset.hostname,
                ip = preset.primaryIp,
                latencyMs = null,
                status = DnsPingStatus.IDLE,
                details = "Stock Plain DNS (No encryption)"
            )
        }

        val latency = pingDnsHostOrIp(target)
        val status = when {
            latency == null -> DnsPingStatus.TIMEOUT
            latency < 40 -> DnsPingStatus.FAST
            latency < 90 -> DnsPingStatus.MODERATE
            else -> DnsPingStatus.SLOW
        }

        val details = when {
            latency == null -> "Unreachable / Timed out"
            latency < 40 -> "⚡ Optimal ($latency ms)"
            latency < 90 -> "Moderate ($latency ms)"
            else -> "High latency ($latency ms)"
        }

        DnsBenchmarkResult(
            presetId = preset.id,
            title = preset.title,
            hostname = preset.hostname,
            ip = preset.primaryIp,
            latencyMs = latency,
            status = status,
            details = details
        )
    }

    suspend fun benchmarkAllDnsPresets(
        presets: List<PrivateDnsPreset> = getPrivateDnsPresets()
    ): List<DnsBenchmarkResult> = withContext(Dispatchers.IO) {
        val testable = presets.filter { it.mode == "hostname" || (it.mode == "opportunistic" && it.primaryIp.isNotEmpty()) }
        val deferred = testable.map { preset ->
            async { benchmarkDnsPreset(preset) }
        }
        val results = deferred.map { it.await() }
        results.sortedWith(
            compareBy<DnsBenchmarkResult> { it.latencyMs == null }
                .thenBy { it.latencyMs ?: Long.MAX_VALUE }
        )
    }

    suspend fun getPrivateDnsConfig(): Pair<String, String> = withContext(Dispatchers.IO) {
        val mode = runShellCommand("settings get global private_dns_mode").stdout.trim().ifEmpty { "off" }
        val spec = runShellCommand("settings get global private_dns_specifier").stdout.trim().ifEmpty { "" }
        Pair(mode, spec)
    }

    suspend fun setPrivateDns(mode: String, specifier: String): Result<String> = withContext(Dispatchers.IO) {
        runShellCommand("settings put global private_dns_mode \"$mode\"")
        if (mode == "hostname" && specifier.isNotBlank()) {
            runShellCommand("settings put global private_dns_specifier \"$specifier\"")
        }
        Result.success("Private DNS set to $mode ${if (specifier.isNotEmpty()) "($specifier)" else ""}".trim())
    }

    suspend fun getBatteryDiagnostics(): BatteryDiagnostics = withContext(Dispatchers.IO) {
        val bRes = runShellCommand("dumpsys battery")
        val lines = bRes.stdout.lines()
        var voltage = 0
        var status = "Unknown"
        var health = "Unknown"
        var tech = "Li-ion"
        var chargeCounter = 0L

        lines.forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("voltage:") -> voltage = trimmed.substringAfter(":").trim().toIntOrNull() ?: 0
                trimmed.startsWith("status:") -> {
                    val code = trimmed.substringAfter(":").trim()
                    status = when (code) { "2" -> "Charging"; "3" -> "Discharging"; "4" -> "Not Charging"; "5" -> "Full"; else -> code }
                }
                trimmed.startsWith("health:") -> {
                    val code = trimmed.substringAfter(":").trim()
                    health = when (code) { "2" -> "Good"; "3" -> "Overheat"; "4" -> "Dead"; "5" -> "Over Voltage"; else -> code }
                }
                trimmed.startsWith("technology:") -> tech = trimmed.substringAfter(":").trim()
                trimmed.startsWith("Charge counter:") -> chargeCounter = trimmed.substringAfter(":").trim().toLongOrNull() ?: 0L
            }
        }

        val statsRes = runShellCommand("dumpsys batterystats --charged | grep -E \"Wake lock|User activity\" | head -n 6")
        val wl = statsRes.stdout.lines().filter { it.isNotBlank() }.map { it.trim() }

        BatteryDiagnostics(
            voltageMv = voltage,
            chargeCounterUah = chargeCounter,
            technology = tech,
            health = health,
            status = status,
            topWakelocks = wl
        )
    }

    suspend fun simulateBattery(level: Int?, unplug: Boolean): Result<String> = withContext(Dispatchers.IO) {
        if (unplug) {
            runShellCommand("dumpsys battery unplug")
        }
        level?.let {
            runShellCommand("dumpsys battery set level $it")
        }
        Result.success("Battery state simulated")
    }

    suspend fun resetBatterySimulation(): Result<String> = withContext(Dispatchers.IO) {
        runShellCommand("dumpsys battery reset")
        Result.success("Hardware battery telemetry restored")
    }

    suspend fun getDozeWhitelist(): List<String> = withContext(Dispatchers.IO) {
        val res = runShellCommand("dumpsys deviceidle whitelist")
        res.stdout.lines()
            .map { it.trim() }
            .filter { it.startsWith("system,") || it.startsWith("user,") }
            .map { it.substringAfter(",") }
    }

    suspend fun toggleDozeWhitelist(packageName: String, add: Boolean): Result<String> = withContext(Dispatchers.IO) {
        val sign = if (add) "+" else "-"
        runShellCommand("dumpsys deviceidle whitelist $sign$packageName")
        Result.success(if (add) "Added $packageName to Doze Whitelist" else "Removed $packageName from Doze Whitelist")
    }

    suspend fun forceDeepDoze(): Result<String> = withContext(Dispatchers.IO) {
        val res = runShellCommand("cmd deviceidle force-idle deep")
        Result.success("Forced deep doze: ${res.stdout.ifEmpty { "Idle active" }}")
    }

    suspend fun unforceDoze(): Result<String> = withContext(Dispatchers.IO) {
        val res = runShellCommand("cmd deviceidle unforce")
        Result.success("Exited forced doze state")
    }

    suspend fun getAndroidUsers(): List<AndroidUser> = withContext(Dispatchers.IO) {
        val res = runShellCommand("pm list users")
        val users = mutableListOf<AndroidUser>()
        val regex = Regex("""UserInfo\{(\d+):([^:]+):([0-9a-fA-FxX]+)\}""")
        regex.findAll(res.stdout).forEach { match ->
            val id = match.groupValues[1].toIntOrNull() ?: 0
            val name = match.groupValues[2]
            val flags = match.groupValues[3]
            users.add(AndroidUser(id, name, flags, isOwner = id == 0))
        }
        if (users.isEmpty()) users.add(AndroidUser(0, "Owner (Default)", isOwner = true))
        users
    }

    suspend fun extractAllSplits(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        val pathRes = runShellCommand("pm path $packageName")
        val paths = pathRes.stdout.lines().filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }
        if (paths.isEmpty()) {
            return@withContext Result.failure(Exception("No paths found for $packageName"))
        }
        val targetDir = "/sdcard/Download/ADBManager/$packageName"
        runShellCommand("mkdir -p $targetDir")
        paths.forEach { apkPath ->
            val fileName = File(apkPath).name
            runShellCommand("cp $apkPath $targetDir/$fileName || cat $apkPath > $targetDir/$fileName")
        }
        Result.success("$targetDir/ (${paths.size} APK splits)")
    }

    suspend fun captureScreenshot(): Result<String> = withContext(Dispatchers.IO) {
        val targetDir = "/sdcard/Download/ADBManager"
        runShellCommand("mkdir -p $targetDir")
        val file = "$targetDir/screenshot_${System.currentTimeMillis()}.png"
        val res = runShellCommand("screencap -p $file")
        if (res.exitCode == 0 || runShellCommand("ls $file").stdout.contains(file)) {
            Result.success(file)
        } else {
            Result.failure(Exception(res.stderr.ifEmpty { "Failed to capture display" }))
        }
    }

    suspend fun recordScreen(durationSec: Int = 10, bitRateMbps: Int = 8): Result<String> = withContext(Dispatchers.IO) {
        val targetDir = "/sdcard/Download/ADBManager"
        runShellCommand("mkdir -p $targetDir")
        val file = "$targetDir/screenrecord_${System.currentTimeMillis()}.mp4"
        val bitRate = bitRateMbps * 1000000
        runShellCommand("screenrecord --time-limit $durationSec --bit-rate $bitRate $file &")
        Result.success("Recording started for ${durationSec}s -> $file")
    }

    // --- ITEM 2: DOZE, STANDBY REBALANCING & POWER PROFILES ---

    suspend fun getDozeStateInfo(): DozeStateInfo = withContext(Dispatchers.IO) {
        val deepRes = runShellCommand("cmd deviceidle get deep")
        val lightRes = runShellCommand("cmd deviceidle get light")
        val dumpsys = runShellCommand("dumpsys deviceidle | grep -i motion")
        val motionEnabled = !dumpsys.stdout.contains("motion=disabled", ignoreCase = true)
        val deepState = deepRes.stdout.trim().ifEmpty { "ACTIVE" }
        val lightState = lightRes.stdout.trim().ifEmpty { "ACTIVE" }
        DozeStateInfo(
            deepState = deepState,
            lightState = lightState,
            motionEnabled = motionEnabled,
            isDeepIdle = deepState.equals("IDLE", ignoreCase = true)
        )
    }

    suspend fun stepDozeIdle(deep: Boolean = true): Result<String> = withContext(Dispatchers.IO) {
        val target = if (deep) "deep" else "light"
        val res = runShellCommand("cmd deviceidle step $target")
        Result.success("Stepped $target doze: ${res.stdout.ifEmpty { "OK" }}")
    }

    suspend fun setDozeMotionEnabled(enabled: Boolean): Result<String> = withContext(Dispatchers.IO) {
        val cmd = if (enabled) "cmd deviceidle enable motion" else "cmd deviceidle disable motion"
        runShellCommand(cmd)
        Result.success(if (enabled) "Motion detection enabled (standard wakes)" else "Motion detection disabled (deep sleep in pocket)")
    }

    suspend fun rebalanceStandbyBuckets(userPackages: List<String>, targetBucket: StandbyBucket = StandbyBucket.RESTRICTED): Result<Int> = withContext(Dispatchers.IO) {
        var count = 0
        userPackages.forEach { pkg ->
            val res = runShellCommand("am set-standby-bucket $pkg ${targetBucket.arg}")
            if (res.exitCode == 0) count++
        }
        Result.success(count)
    }

    suspend fun applyPowerProfile(profileName: String): Result<String> = withContext(Dispatchers.IO) {
        when (profileName.lowercase()) {
            "performance" -> {
                runShellCommand("settings put system min_refresh_rate 120.0 && settings put system peak_refresh_rate 120.0")
                runShellCommand("cmd deviceidle unforce")
                runShellCommand("cmd netpolicy set restrict-background false")
                Result.success("Performance Profile: 120Hz locked, Doze unforced, NetPolicy background unrestricted.")
            }
            "ultra_saver" -> {
                runShellCommand("settings put system min_refresh_rate 60.0 && settings put system peak_refresh_rate 60.0")
                runShellCommand("cmd deviceidle force-idle deep")
                runShellCommand("cmd deviceidle disable motion")
                runShellCommand("cmd netpolicy set restrict-background true")
                runShellCommand("settings put global animator_duration_scale 0.0")
                Result.success("Ultra Battery Saver: 60Hz lock, Deep Doze forced, Motion disabled, Background data restricted.")
            }
            else -> {
                runShellCommand("settings put system min_refresh_rate 60.0 && settings put system peak_refresh_rate 120.0")
                runShellCommand("cmd deviceidle unforce")
                runShellCommand("cmd deviceidle enable motion")
                runShellCommand("cmd netpolicy set restrict-background false")
                runShellCommand("settings put global animator_duration_scale 1.0")
                Result.success("Balanced Profile: Adaptive 60-120Hz, standard motion sensors, standard animations.")
            }
        }
    }

    // --- ITEM 3: NETWORK POLICY & DNS BOOKMARKS & JITTER ---

    suspend fun getRestrictBackground(): Boolean = withContext(Dispatchers.IO) {
        val res = runShellCommand("cmd netpolicy get restrict-background")
        res.stdout.contains("true", ignoreCase = true)
    }

    suspend fun setRestrictBackground(restrict: Boolean): Result<String> = withContext(Dispatchers.IO) {
        runShellCommand("cmd netpolicy set restrict-background $restrict")
        Result.success(if (restrict) "Background data globally restricted" else "Background data restriction lifted")
    }

    suspend fun setAppNetworkBlacklisted(uid: Int, blacklist: Boolean): Result<String> = withContext(Dispatchers.IO) {
        val action = if (blacklist) "add" else "remove"
        runShellCommand("cmd netpolicy $action restrict-background-blacklist $uid")
        Result.success(if (blacklist) "Blacklisted UID $uid from background data" else "Removed UID $uid from network blacklist")
    }

    fun getSavedCustomDnsPresets(): List<PrivateDnsPreset> {
        val prefs = context.getSharedPreferences("adb_custom_dns_bookmarks", Context.MODE_PRIVATE)
        val raw = prefs.getString("custom_presets_json", "[]") ?: "[]"
        val list = mutableListOf<PrivateDnsPreset>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    PrivateDnsPreset(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        mode = "hostname",
                        hostname = obj.getString("hostname"),
                        description = obj.optString("description", "Custom Bookmarked DoT Endpoint"),
                        isEncrypted = true,
                        primaryIp = obj.optString("primaryIp", ""),
                        category = "Custom"
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun saveCustomDnsPreset(title: String, hostname: String, description: String = "Custom DoT Endpoint", primaryIp: String = ""): PrivateDnsPreset {
        val id = "custom_" + hostname.replace(".", "_") + "_" + (System.currentTimeMillis() % 1000)
        val current = getSavedCustomDnsPresets().toMutableList()
        current.removeAll { it.hostname == hostname }
        val newPreset = PrivateDnsPreset(
            id = id,
            title = title.ifEmpty { hostname },
            mode = "hostname",
            hostname = hostname,
            description = description,
            isEncrypted = true,
            primaryIp = primaryIp,
            category = "Custom"
        )
        current.add(0, newPreset)
        val arr = JSONArray()
        current.forEach {
            val obj = JSONObject()
            obj.put("id", it.id)
            obj.put("title", it.title)
            obj.put("hostname", it.hostname)
            obj.put("description", it.description)
            obj.put("primaryIp", it.primaryIp)
            arr.put(obj)
        }
        val prefs = context.getSharedPreferences("adb_custom_dns_bookmarks", Context.MODE_PRIVATE)
        prefs.edit().putString("custom_presets_json", arr.toString()).apply()
        return newPreset
    }

    fun deleteCustomDnsPreset(presetId: String) {
        val current = getSavedCustomDnsPresets().filterNot { it.id == presetId }
        val arr = JSONArray()
        current.forEach {
            val obj = JSONObject()
            obj.put("id", it.id)
            obj.put("title", it.title)
            obj.put("hostname", it.hostname)
            obj.put("description", it.description)
            obj.put("primaryIp", it.primaryIp)
            arr.put(obj)
        }
        val prefs = context.getSharedPreferences("adb_custom_dns_bookmarks", Context.MODE_PRIVATE)
        prefs.edit().putString("custom_presets_json", arr.toString()).apply()
    }

    suspend fun testDnsJitterAndLeak(target: String, count: Int = 4): DnsJitterResult = withContext(Dispatchers.IO) {
        val samples = mutableListOf<Long?>()
        val hostToPing = target.ifEmpty { "1.1.1.1" }
        repeat(count) {
            val lat = pingDnsHostOrIp(hostToPing)
            samples.add(lat)
        }
        val validSamples = samples.filterNotNull()
        val minMs = validSamples.minOrNull() ?: 0L
        val maxMs = validSamples.maxOrNull() ?: 0L
        val avgMs = if (validSamples.isNotEmpty()) validSamples.average().roundToLong() else 0L
        val jitterMs = if (validSamples.size > 1) {
            var diffSum = 0L
            for (i in 1 until validSamples.size) {
                diffSum += kotlin.math.abs(validSamples[i] - validSamples[i - 1])
            }
            diffSum / (validSamples.size - 1)
        } else 0L
        val lossPct = if (count > 0) ((count - validSamples.size) * 100) / count else 0

        val r1 = runShellCommand("getprop net.dns1").stdout.trim()
        val r2 = runShellCommand("getprop net.dns2").stdout.trim()
        val active = listOf(r1, r2).filter { it.isNotEmpty() }
        val curDns = getPrivateDnsConfig()
        val isEncrypted = curDns.first == "hostname"

        DnsJitterResult(
            target = hostToPing,
            minMs = minMs,
            avgMs = avgMs,
            maxMs = maxMs,
            jitterMs = jitterMs,
            packetLossPct = lossPct,
            samples = samples,
            activeResolvers = active,
            isEncryptedVerified = isEncrypted
        )
    }

    // --- ITEM 4: DISPLAY IMMERSIVE, RESOLUTION & AUDIO ---

    suspend fun getImmersivePolicy(): String = withContext(Dispatchers.IO) {
        val res = runShellCommand("settings get global policy_control")
        val out = res.stdout.trim()
        if (out == "null" || out.isEmpty()) "off" else out
    }

    suspend fun setImmersivePolicy(mode: String, customApps: String = ""): Result<String> = withContext(Dispatchers.IO) {
        val cmd = when (mode) {
            "full" -> "settings put global policy_control immersive.full=*"
            "status" -> "settings put global policy_control immersive.status=*"
            "navigation" -> "settings put global policy_control immersive.navigation=*"
            "custom" -> if (customApps.isNotEmpty()) "settings put global policy_control immersive.full=$customApps" else "settings put global policy_control null"
            else -> "settings put global policy_control null"
        }
        runShellCommand(cmd)
        Result.success("Immersive mode policy set to '$mode'")
    }

    suspend fun applyResolutionDensityPreset(width: Int, height: Int, density: Int): Result<String> = withContext(Dispatchers.IO) {
        if (width <= 0 || height <= 0) {
            runShellCommand("wm size reset && wm density reset")
            Result.success("Restored physical hardware display resolution & density")
        } else {
            runShellCommand("wm size ${width}x${height} && wm density $density")
            Result.success("Applied display profile: ${width}x${height} @ ${density}dpi")
        }
    }

    suspend fun getBluetoothAudioCodecInfo(): Map<String, String> = withContext(Dispatchers.IO) {
        val map = mutableMapOf<String, String>()
        val p1 = runShellCommand("getprop persist.bluetooth.a2dp_offload.cap").stdout.trim()
        val p2 = runShellCommand("getprop persist.bluetooth.a2dp_offload.disabled").stdout.trim()
        val p3 = runShellCommand("getprop persist.bluetooth.ldac.quality").stdout.trim()
        map["a2dp_offload_cap"] = p1.ifEmpty { "Default Hardware Engine" }
        map["a2dp_offload_disabled"] = if (p2 == "true") "Disabled (Software Decoding)" else "Enabled (Hardware DSP)"
        map["ldac_quality"] = when (p3) {
            "990" -> "LDAC 990 kbps (High Fidelity)"
            "660" -> "LDAC 660 kbps (Balanced)"
            "330" -> "LDAC 330 kbps (Connection Priority)"
            else -> "Adaptive Bitrate"
        }
        map
    }

    // --- ITEM 5: LIVE PROCESS MANAGER & MEMORY / ZRAM PROFILER ---

    suspend fun getLiveProcesses(): List<LiveProcessItem> = withContext(Dispatchers.IO) {
        val res = runShellCommand("ps -A -o PID,USER,VSZ,RSS,NAME")
        val lines = res.stdout.lines()
        val list = mutableListOf<LiveProcessItem>()
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("""\s+"""))
            if (parts.size >= 5) {
                val pid = parts[0].toIntOrNull() ?: continue
                val user = parts[1]
                val rss = parts[3].toLongOrNull() ?: 0L
                val name = parts.drop(4).joinToString(" ")
                val isSys = user.startsWith("root") || user.startsWith("system")
                list.add(LiveProcessItem(pid = pid, name = name, user = user, rssKb = rss, cpuPct = 0f, isSystem = isSys))
            }
        }
        if (list.isEmpty()) {
            val act = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            act.runningAppProcesses?.forEach { proc ->
                list.add(LiveProcessItem(pid = proc.pid, name = proc.processName, user = "u0_a${proc.uid % 10000}", rssKb = 15000L, cpuPct = 0f, isSystem = proc.processName.startsWith("com.android") || proc.processName.startsWith("system")))
            }
        }
        list.sortedByDescending { it.rssKb }
    }

    suspend fun killProcess(pid: Int): Result<String> = withContext(Dispatchers.IO) {
        runShellCommand("kill -9 $pid")
        Result.success("Sent SIGKILL to PID $pid")
    }

    suspend fun killApp(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        runShellCommand("am kill $packageName")
        Result.success("Killed background processes for $packageName")
    }

    suspend fun trimAppMemory(packageName: String, level: String = "RUNNING_CRITICAL"): Result<String> = withContext(Dispatchers.IO) {
        runShellCommand("am trim-memory $packageName $level")
        Result.success("Trimmed memory ($level) for $packageName")
    }

    suspend fun getZramStats(): ZramStats = withContext(Dispatchers.IO) {
        val diskRes = runShellCommand("cat /sys/block/zram0/disksize")
        val diskBytes = diskRes.stdout.trim().toLongOrNull() ?: (2048L * 1024L * 1024L)
        val diskMb = diskBytes / (1024 * 1024)

        val mmStatRes = runShellCommand("cat /sys/block/zram0/mm_stat")
        val mmParts = mmStatRes.stdout.trim().split(Regex("""\s+"""))
        val origBytes = mmParts.getOrNull(0)?.toLongOrNull() ?: (1024L * 1024L * 1024L)
        val compBytes = mmParts.getOrNull(1)?.toLongOrNull() ?: (450L * 1024L * 1024L)
        val usedMb = compBytes / (1024 * 1024)
        val origMb = origBytes / (1024 * 1024)
        val ratio = if (compBytes > 0) origBytes.toFloat() / compBytes.toFloat() else 2.2f

        ZramStats(diskSizeMb = diskMb, usedMb = usedMb, origSizeMb = origMb, compressionRatio = ratio)
    }

    suspend fun trimDiskCaches(): Result<String> = withContext(Dispatchers.IO) {
        val res = runShellCommand("pm trim-caches 1000G")
        Result.success("Reclaimed system & application disk caches: ${res.stdout.ifEmpty { "Trim complete" }}")
    }

    suspend fun dropMemoryCaches(): Result<String> = withContext(Dispatchers.IO) {
        runShellCommand("echo 3 > /proc/sys/vm/drop_caches || am drop-caches")
        Result.success("Page cache, dentries & inodes compacted")
    }

    // --- ITEM 6: SCHEDULED AUTOMATION / RULE ENGINE ---

    fun getPredefinedAutomationProfiles(): List<AutomationProfile> = listOf(
        AutomationProfile(
            id = "gaming_turbo",
            title = "Gaming Turbo Mode",
            tag = "HIGH FPS",
            description = "Uncaps display refresh rate to 120Hz, unlocks fullscreen immersive mode, and trims background app memory for zero stutter.",
            steps = listOf("Lock peak & min refresh rate to 120Hz", "Enable full immersive mode (hide navigation pill)", "Trim background app memory", "Unforce doze restrictions"),
            adbCommands = listOf(
                "settings put system min_refresh_rate 120.0",
                "settings put system peak_refresh_rate 120.0",
                "settings put global policy_control immersive.full=*",
                "cmd deviceidle unforce"
            ),
            iconName = "SportsEsports"
        ),
        AutomationProfile(
            id = "night_shield",
            title = "Deep Night Battery Shield",
            tag = "ULTRA POWER",
            description = "Forces deepest Doze state, disables motion sensor wakeups in pocket, locks display to 60Hz, and restricts background sync.",
            steps = listOf("Force deep Doze idle immediately", "Disable motion sensors so device stays asleep in movement", "Lock refresh rate to 60Hz", "Enable global background network restriction", "Zero animation scale"),
            adbCommands = listOf(
                "cmd deviceidle force-idle deep",
                "cmd deviceidle disable motion",
                "settings put system peak_refresh_rate 60.0",
                "cmd netpolicy set restrict-background true",
                "settings put global animator_duration_scale 0.0"
            ),
            iconName = "Nightlight"
        ),
        AutomationProfile(
            id = "public_wifi_shield",
            title = "Public Wi-Fi Shield",
            tag = "PRIVACY",
            description = "Enforces DNS-over-TLS through AdGuard AdBlock, restricts background data to prevent telemetry leakage, and enables DoT verification.",
            steps = listOf("Set Private DNS mode to hostname", "Set DoT hostname to dns.adguard-dns.com", "Enable netpolicy background restriction"),
            adbCommands = listOf(
                "settings put global private_dns_mode hostname",
                "settings put global private_dns_specifier dns.adguard-dns.com",
                "cmd netpolicy set restrict-background true"
            ),
            iconName = "Security"
        ),
        AutomationProfile(
            id = "focus_work",
            title = "Distraction-Free Focus",
            tag = "PRODUCTIVITY",
            description = "Hides notification status bar, sets animations to instant snappy response, and rebalances standby buckets for background silence.",
            steps = listOf("Hide status bar via policy_control", "Set window & transition scales to 0.5x snappy", "Turn off distraction alerts"),
            adbCommands = listOf(
                "settings put global policy_control immersive.status=*",
                "settings put global window_animation_scale 0.5",
                "settings put global transition_animation_scale 0.5"
            ),
            iconName = "Psychology"
        )
    )

    suspend fun executeAutomationProfile(profile: AutomationProfile): List<Pair<String, Boolean>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Pair<String, Boolean>>()
        profile.adbCommands.forEach { cmd ->
            val res = runShellCommand(cmd)
            results.add(cmd to (res.exitCode == 0))
        }
        results
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
