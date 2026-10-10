// The Morphe engine of the Morphe Patcher tab: the unmodified source of morphe-patcher (GPL-3.0, https://github.com/MorpheApp/morphe-patcher)
// plus a small launcher (src/main/kotlin) that lists patches and runs the patching pipeline for this app. build-engine.sh clones the pinned
// upstream tag, builds this project and converts the result into a dex jar (engine/dist/morphe-engine.jar) that the app runs with app_process.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
}

val patcherVersion = providers.gradleProperty("patcherVersion").orElse("1.15.1").get()
val patcherSrc = providers.gradleProperty("patcherSrc").orElse("${rootDir}/.upstream/morphe-patcher").get()

group = "com.bloatware.bingblop"
version = patcherVersion

repositories {
    // The Google mirror of Maven Central first (it is not rate limited), the real one as the fallback
    maven("https://maven-central.storage-download.googleapis.com/maven2")
    mavenCentral()
    google()
    // baksmali/smali and ARSCLib come from forks of MorpheApp that only JitPack serves
    maven("https://jitpack.io") {
        content {
            includeGroup("com.github.MorpheApp.smali")
            includeGroup("com.github.MorpheApp")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        // the upstream code is compiled as part of this project, the same flags as its own build use
        freeCompilerArgs.add("-Xskip-prerelease-check")
        // The module name is part of the compiled name of every Kotlin `internal` member (BytecodePatchContext.getPatchClasses$morphe_patcher).
        // Patch bundles reach some of them by reflection under the name morphe-patcher's own build gives them (the Gboard bundle does), so this
        // project has to call its module the same, not after itself, or every patch that depends on such a call fails with NoSuchMethodException.
        moduleName.set("morphe-patcher")
    }
    sourceSets.main {
        kotlin.srcDir("$patcherSrc/src/main/kotlin")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

sourceSets.main {
    resources.srcDir("$patcherSrc/src/main/resources")
}

tasks.processResources {
    expand("projectVersion" to patcherVersion)
}

dependencies {
    compileOnly("com.google.android:android:4.1.1.4") {
        // otherwise the org.w3c.dom API breaks
        exclude(group = "xerces", module = "xmlParserAPIs")
    }
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")
    implementation("com.android.tools.build:apksig:9.1.1")
    implementation("com.android.tools.build:apkzlib:9.1.1")
    implementation("com.github.MorpheApp:ARSCLib:9b742c412d")
    implementation("com.google.guava:guava:33.5.0-jre")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.10")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("com.github.MorpheApp.smali:smali:d856bad65f")
}

// Everything the engine needs at run time, in one folder, for build-engine.sh
tasks.register<Copy>("engineLibs") {
    dependsOn(tasks.jar)
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("engine-libs"))
}
