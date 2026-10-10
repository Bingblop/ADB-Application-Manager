/*
 * The launcher of the Morphe engine for ADB Application Manager Pro (the Morphe Patcher tab).
 *
 * It runs in its own process (app_process on the phone, a plain JVM when the engine is tested on a computer) and talks to the app over stdout:
 *   LOG <LEVEL> <text>        one line of the patcher's log
 *   STEP <name> <state>       Loading / Unpacking / Patching / Rebuilding / Signing with RUNNING, OK or FAIL
 *   APP <package> <versionName> <versionCode>
 *   PATCH <OK|FAIL> <name>    a patch finished
 *   RESULT <json>             the final answer (exactly one)
 * The pipeline (load patches, merge a split bundle, run the patches, rebuild, sign) follows the one of morphe-cli (GPL-3.0, MorpheApp);
 * the patcher library itself is morphe-patcher (GPL-3.0, MorpheApp), compiled unchanged next to this file.
 */
package com.bloatware.bingblop.morphe

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkMerger
import app.morphe.patcher.apk.ApkUtils
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.logging.toMorpheLogger
import app.morphe.patcher.patch.ColorOption
import app.morphe.patcher.patch.FilePathOption
import app.morphe.patcher.patch.FilesOption
import app.morphe.patcher.patch.FloatSliderOption
import app.morphe.patcher.patch.FolderOption
import app.morphe.patcher.patch.ImageOption
import app.morphe.patcher.patch.IntSliderOption
import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.loadPatchesFromDex
import app.morphe.patcher.patch.loadPatchesFromJar
import app.morphe.patcher.resource.CpuArchitecture
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.PrintStream
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.reflect.KType
import kotlin.system.exitProcess

object EngineMain {
    const val ENGINE_PROTOCOL = 1

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Where the protocol lines go: the real stdout of the process (main), or a file the app tails (the service of the app, runCommand).
    // When it is stdout, System.out is pointed at stderr so that nothing a patch prints can be mistaken for a protocol line.
    @Volatile private var out: PrintStream = System.out

    private fun emit(line: String) {
        synchronized(out) { out.println(line); out.flush() }
    }

    private fun log(level: String, text: String) = text.split('\n').forEach { emit("LOG $level $it") }

    @JvmStatic
    fun main(args: Array<String>) {
        out = System.out
        System.setOut(System.err)
        val code = runCommand(args.getOrNull(0) ?: "help", args.getOrNull(1), out)
        System.err.flush()
        exitProcess(code)
    }

    /**
     * Runs one command (info, list or patch) and writes the protocol lines to [sink]. The app calls this from its patcher service (a process of
     * its own with the big heap), main() calls it for a plain JVM. Never throws; the return value is 0 when the command succeeded.
     */
    @JvmStatic
    fun runCommand(command: String, jobPath: String?, sink: PrintStream): Int {
        out = sink
        return try {
            when (command) {
                "info" -> info()
                "list" -> list(readJob(jobPath))
                "patch" -> patch(readJob(jobPath))
                else -> { emit("LOG ERROR usage: info | list <job.json> | patch <job.json>"); 2 }
            }
        } catch (t: Throwable) {
            log("ERROR", "The engine stopped: " + t.stackTraceToString())
            emit("RESULT " + buildJsonObject { put("success", false); put("error", t.toString()); put("trace", t.stackTraceToString()) })
            1
        }
    }

    private fun readJob(path: String?): JsonObject {
        if (path == null) error("no job file")
        return json.parseToJsonElement(File(path).readText()).jsonObject
    }

    private fun isAndroid() = runCatching { Class.forName("dalvik.system.DexClassLoader") }.isSuccess

    private fun sdkInt(): Int = if (!isAndroid()) 0 else runCatching {
        Class.forName("android.os.Build\$VERSION").getField("SDK_INT").getInt(null)
    }.getOrDefault(0)

    private fun info(): Int {
        emit("RESULT " + buildJsonObject {
            put("success", true)
            put("protocol", ENGINE_PROTOCOL)
            put("patcher", patcherVersion())
            put("android", isAndroid())
            put("maxMemoryMb", Runtime.getRuntime().maxMemory() / (1024 * 1024))
            put("abi", System.getProperty("os.arch") ?: "")
        })
        return 0
    }

    private fun patcherVersion(): String = runCatching {
        Patcher::class.java.getResourceAsStream("/morphe-patcher.properties")?.bufferedReader()?.use { it.readText().trim() }
    }.getOrNull().orEmpty().ifBlank { "1.15.1" }

    // ---------------------------------------------------------------------------------------------------- loading

    private fun load(file: File): Set<Patch<*>> {
        val files = setOf(file)
        val loader = if (isAndroid()) {
            // A dex file that can be written to is refused from Android 14 on
            file.setReadOnly()
            // Android 8.0 wants any existing directory here; later versions ignore it
            loadPatchesFromDex(files, if (sdkInt() > 26) null else file.parentFile)
        } else loadPatchesFromJar(files)
        return loader.byPatchesFile[file] ?: loader.byPatchesFile.values.firstOrNull().orEmpty()
    }

    // ---------------------------------------------------------------------------------------------------- list

    private fun list(job: JsonObject): Int {
        val outFile = File(job["out"]!!.jsonPrimitive.content)
        val result = buildJsonObject {
            putJsonArray("bundles") {
                for (b in job["bundles"]!!.jsonArray) {
                    val file = File(b.jsonPrimitive.content)
                    add(buildJsonObject {
                        put("file", file.path)
                        try {
                            val patches = load(file)
                            put("ok", true)
                            put("patches", buildJsonArray { patches.filter { it.name != null }.sortedBy { it.name }.forEach { add(patchJson(it)) } })
                        } catch (t: Throwable) {
                            put("ok", false)
                            put("error", (t.cause ?: t).toString())
                            put("trace", t.stackTraceToString())
                        }
                    })
                }
            }
        }
        outFile.writeText(result.toString())
        emit("RESULT " + buildJsonObject { put("success", true) })
        return 0
    }

    @Suppress("DEPRECATION")
    private fun patchJson(p: Patch<*>): JsonObject = buildJsonObject {
        put("name", p.name ?: "")
        put("description", p.description ?: "")
        put("default", p.default)
        putJsonArray("compat") {
            val compat = p.compatibility
            if (!compat.isNullOrEmpty()) {
                compat.forEach { c ->
                    add(buildJsonObject {
                        put("package", c.packageName ?: "")
                        put("name", c.name ?: "")
                        put("apkType", c.apkFileType?.name ?: "")
                        putJsonArray("versions") { c.targets.filter { !it.isExperimental }.mapNotNull { it.version }.forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("experimental") { c.targets.filter { it.isExperimental }.mapNotNull { it.version }.forEach { add(JsonPrimitive(it)) } }
                    })
                }
            } else {
                p.compatiblePackages?.forEach { (pkg, versions) ->
                    add(buildJsonObject {
                        put("package", pkg)
                        put("name", "")
                        put("apkType", "")
                        putJsonArray("versions") { versions?.forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("experimental") { }
                    })
                }
            }
        }
        putJsonArray("options") { p.options.values.forEach { add(optionJson(it)) } }
    }

    private fun typeName(t: KType): String {
        val c = t.classifier
        return when (c) {
            String::class -> "string"
            Boolean::class -> "boolean"
            Int::class -> "int"
            Long::class -> "long"
            Float::class -> "float"
            Double::class -> "double"
            List::class -> "list:" + (t.arguments.firstOrNull()?.type?.let(::typeName) ?: "string")
            else -> t.toString()
        }
    }

    private fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map(::toJson))
        else -> JsonPrimitive(v.toString())
    }

    private fun optionJson(o: Option<*>): JsonObject = buildJsonObject {
        put("key", o.name)
        put("title", o.title ?: o.name)
        put("description", o.description ?: "")
        put("required", o.required)
        put("type", typeName(o.type))
        put("default", toJson(o.default))
        put("value", toJson(o.value))
        val kind = when (o) {
            is FolderOption -> "folder"
            is FilePathOption -> "file"
            is FilesOption -> "files"
            is ImageOption -> "image"
            is ColorOption -> "color"
            is IntSliderOption -> "slider"
            is FloatSliderOption -> "slider"
            else -> ""
        }
        put("kind", kind)
        if (o is IntSliderOption) { put("min", o.min); put("max", o.max); put("step", o.step) }
        if (o is FloatSliderOption) { put("min", o.min); put("max", o.max); o.step?.let { put("step", it) } }
        val values = o.values
        if (!values.isNullOrEmpty()) put("choices", buildJsonArray {
            values.forEach { (label, v) -> add(buildJsonObject { put("label", label); put("value", toJson(v)) }) }
        })
    }

    // ---------------------------------------------------------------------------------------------------- values of options

    private fun coerce(type: KType, v: JsonElement): Any? {
        if (v is JsonNull) return null
        if (type.classifier == List::class) {
            val el = type.arguments.firstOrNull()?.type ?: return null
            val items: List<JsonElement> = when (v) {
                is JsonArray -> v
                is JsonPrimitive -> v.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { JsonPrimitive(it) }
                else -> return null
            }
            return items.map { coerce(el, it) ?: return null }
        }
        val p = v as? JsonPrimitive ?: return null
        return when (type.classifier) {
            String::class -> p.content
            Boolean::class -> p.booleanOrNull ?: p.content.trim().lowercase().toBooleanStrictOrNull()
            Int::class -> p.doubleOrNull?.takeIf { it == it.toLong().toDouble() }?.toInt() ?: p.content.trim().toIntOrNull()
            Long::class -> p.doubleOrNull?.takeIf { it == it.toLong().toDouble() }?.toLong() ?: p.content.trim().toLongOrNull()
            Float::class -> p.content.trim().toFloatOrNull()
            Double::class -> p.content.trim().toDoubleOrNull()
            else -> p.content
        }
    }

    // ---------------------------------------------------------------------------------------------------- patch

    private fun installLogHandler() {
        val root = Logger.getLogger("")
        root.handlers.forEach { runCatching { it.close() }; root.removeHandler(it) }
        root.level = Level.INFO
        root.addHandler(object : Handler() {
            override fun publish(r: LogRecord) {
                val level = when {
                    r.level.intValue() >= Level.SEVERE.intValue() -> "ERROR"
                    r.level.intValue() >= Level.WARNING.intValue() -> "WARN"
                    r.level.intValue() >= Level.INFO.intValue() -> "INFO"
                    else -> "TRACE"
                }
                if (level == "TRACE") return
                val text = (r.message ?: "") + (r.thrown?.let { "\n" + it.stackTraceToString() } ?: "")
                log(level, text)
            }
            override fun flush() {}
            override fun close() {}
        })
    }

    private fun supportedVersions(p: Patch<*>, pkg: String): List<String>? {
        val compat = p.compatibility
        if (!compat.isNullOrEmpty()) {
            val matching = compat.filter { it.packageName == pkg }
            if (matching.isEmpty()) return if (compat.any { it.packageName == null }) null else emptyList()
            return matching.flatMap { m -> m.targets.mapNotNull { it.version } }.ifEmpty { null }
        }
        @Suppress("DEPRECATION")
        val legacy = p.compatiblePackages ?: return null
        val match = legacy.singleOrNull { it.first == pkg } ?: return emptyList()
        return match.second?.toList()?.ifEmpty { null }
    }

    private fun step(name: String, state: String) = emit("STEP $name $state")

    private fun patch(job: JsonObject): Int {
        installLogHandler()
        val input = File(job["input"]!!.jsonPrimitive.content)
        val output = File(job["output"]!!.jsonPrimitive.content)
        val temp = File(job["tempDir"]!!.jsonPrimitive.content).also { it.mkdirs() }
        val force = job["forceCompatibility"]?.jsonPrimitive?.boolean ?: false
        val failOnError = job["failOnError"]?.jsonPrimitive?.boolean ?: true
        val unsigned = job["unsigned"]?.jsonPrimitive?.boolean ?: false
        val signer = job["signerName"]?.jsonPrimitive?.contentOrNull ?: "Morphe"
        val keep = job["keepArchitectures"]?.jsonArray?.mapNotNull { a -> CpuArchitecture.values().firstOrNull { it.name.equals(a.jsonPrimitive.content, true) || it.arch.equals(a.jsonPrimitive.content, true) } }?.toSet().orEmpty()
        val keystore = job["keystore"]?.jsonObject

        log("INFO", "Engine: morphe-patcher ${patcherVersion()} on ${if (isAndroid()) "Android SDK ${sdkInt()}" else "JVM " + System.getProperty("java.version")}")
        log("INFO", "Heap limit: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB")

        val applied = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        val steps = mutableListOf<Triple<String, Boolean, String?>>()
        var pkg = ""; var versionName = ""; var versionCode = ""
        var merged: File? = null

        fun finish(success: Boolean, error: String? = null): Int {
            emit("RESULT " + buildJsonObject {
                put("success", success)
                put("output", output.path)
                put("package", pkg)
                put("versionName", versionName)
                put("versionCode", versionCode)
                putJsonArray("applied") { applied.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("failed") { failed.forEach { (n, e) -> add(buildJsonObject { put("name", n); put("error", e) }) } }
                putJsonArray("steps") { steps.forEach { (n, ok, e) -> add(buildJsonObject { put("name", n); put("ok", ok); if (e != null) put("error", e) }) } }
                if (error != null) put("error", error)
            })
            return if (success) 0 else 1
        }

        try {
            step("Loading", "RUNNING")
            // The patches, bundle by bundle, with the names asked for
            val selected = mutableListOf<Patch<*>>()
            val optionsByBundle = job["bundles"]!!.jsonArray
            val loaded = optionsByBundle.map { b ->
                val bo = b.jsonObject
                val file = File(bo["file"]!!.jsonPrimitive.content)
                log("INFO", "Loading patches from ${file.name}")
                bo to load(file)
            }
            step("Loading", "OK")

            step("Unpacking", "RUNNING")
            val ext = input.extension.lowercase()
            val actualInput = if (ext in setOf("apkm", "xapk", "apks")) {
                val m = File(temp, input.nameWithoutExtension + "-merged.apk")
                log("INFO", "Merging the split APKs of ${input.name}")
                ApkMerger(Logger.getLogger("app.morphe.patcher.ApkMerger").toMorpheLogger()).merge(input, m, false)
                merged = m
                m
            } else input

            val patcherTemp = File(temp, "patcher").also { it.mkdirs() }
            val patcher = Patcher(PatcherConfig(
                actualInput,
                patcherTemp,
                patcherTemp.absolutePath,
                useArsclib = true,
                keepArchitectures = keep,
                fileWorkspacePath = File(temp, "workspace").also { it.mkdirs() },
            ))
            patcher.use {
                val md = patcher.context.packageMetadata
                pkg = md.packageName; versionName = md.versionName; versionCode = md.versionCode
                emit("APP $pkg $versionName $versionCode")
                step("Unpacking", "OK")

                for ((bo, patches) in loaded) {
                    val names = bo["patches"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
                    val opts = bo["options"]?.jsonObject
                    for (p in patches) {
                        val n = p.name ?: continue
                        if (n !in names) continue
                        val sv = supportedVersions(p, pkg)
                        if (sv != null && sv.isEmpty()) { log("WARN", "Skipping \"$n\": it does not patch $pkg"); continue }
                        if (sv != null && !force && versionName !in sv) { log("WARN", "Skipping \"$n\": incompatible with $pkg $versionName (it patches ${sv.take(6).joinToString(", ")}${if (sv.size > 6) ", ..." else ""})"); continue }
                        opts?.get(n)?.jsonObject?.forEach { (key, v) ->
                            if (key !in p.options) { log("WARN", "\"$n\" has no option \"$key\""); return@forEach }
                            val o = p.options[key]
                            val c = coerce(o.type, v)
                            if (c == null && v !is JsonNull) { log("WARN", "Option \"$key\" of \"$n\" does not accept $v as ${typeName(o.type)}"); return@forEach }
                            if (c != null) try { p.options[key] = c } catch (e: Exception) { log("WARN", "Option \"$key\" of \"$n\": ${e.message}") }
                        }
                        selected += p
                    }
                }
                if (selected.isEmpty()) { step("Patching", "FAIL"); steps += Triple("Patching", false, "No patch is left to apply"); return finish(false, "No patch is left to apply to $pkg $versionName") }
                patcher += selected.toSet()

                step("Patching", "RUNNING")
                log("INFO", "Applying ${selected.size} patches")
                var abort = false
                runBlocking {
                    try {
                        patcher().collect { r ->
                            val n = r.patch.name ?: "Unknown"
                            val ex = r.exception
                            if (ex == null) { applied += n; emit("PATCH OK $n"); log("INFO", "$n succeeded") }
                            else {
                                val trace = ex.stackTraceToString()
                                failed += n to trace; emit("PATCH FAIL $n"); log("ERROR", "$n failed:\n$trace")
                                if (failOnError) { abort = true; throw StopRun() }
                            }
                        }
                    } catch (_: StopRun) { }
                }
                if (abort) { step("Patching", "FAIL"); steps += Triple("Patching", false, failed.lastOrNull()?.first?.let { "\"$it\" failed" }); return finish(false, "A patch failed") }
                steps += Triple("Patching", failed.isEmpty(), null); step("Patching", if (failed.isEmpty()) "OK" else "FAIL")

                step("Rebuilding", "RUNNING")
                val rebuilt = File(temp, "rebuilt.apk")
                try {
                    val result = patcher.get()
                    actualInput.copyTo(rebuilt, overwrite = true)
                    result.applyTo(rebuilt)
                    steps += Triple("Rebuilding", true, null); step("Rebuilding", "OK")
                } catch (e: Throwable) {
                    log("ERROR", "Rebuilding failed:\n" + e.stackTraceToString())
                    steps += Triple("Rebuilding", false, e.toString()); step("Rebuilding", "FAIL")
                    return finish(false, "Rebuilding the APK failed: $e")
                }

                val tempOut = File(temp, output.name)
                if (!unsigned) {
                    step("Signing", "RUNNING")
                    val ksFile = File(keystore?.get("path")?.jsonPrimitive?.contentOrNull ?: File(temp, "morphe.keystore").path)
                    val alias = keystore?.get("alias")?.jsonPrimitive?.contentOrNull ?: "Morphe"
                    val pw = keystore?.get("password")?.jsonPrimitive?.contentOrNull ?: "Morphe"
                    val ksPw = keystore?.get("storePassword")?.jsonPrimitive?.contentOrNull
                    try {
                        try {
                            ApkUtils.signApk(rebuilt, tempOut, signer, ApkUtils.KeyStoreDetails(ksFile, ksPw, alias, pw))
                        } catch (e: Exception) {
                            // A keystore made by an older Morphe uses another alias
                            if (keystore != null || !ksFile.exists()) throw e
                            log("INFO", "The default credentials failed (${e.message}); trying the legacy ones")
                            ApkUtils.signApk(rebuilt, tempOut, signer, ApkUtils.KeyStoreDetails(ksFile, ksPw, "Morphe Key", ""))
                        }
                        steps += Triple("Signing", true, null); step("Signing", "OK")
                    } catch (e: Throwable) {
                        log("ERROR", "Signing failed:\n" + e.stackTraceToString())
                        steps += Triple("Signing", false, e.toString()); step("Signing", "FAIL")
                        return finish(false, "Signing the APK failed: $e")
                    }
                } else rebuilt.copyTo(tempOut, overwrite = true)

                output.parentFile?.mkdirs()
                tempOut.copyTo(output, overwrite = true)
                log("INFO", "Patched APK saved to ${output.path} (${output.length() / 1024} KB)")
            }
            return finish(!failOnError || failed.isEmpty())
        } finally {
            merged?.delete()
            runCatching { File(temp, "patcher").deleteRecursively(); File(temp, "workspace").deleteRecursively(); File(temp, "rebuilt.apk").delete() }
        }
    }

    private class StopRun : RuntimeException()
}
