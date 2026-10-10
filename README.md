# ⚡ ADB Application Manager Pro (Kotlin & Jetpack Compose)

A modern, high-performance Android system & ADB management application rewritten natively in **Kotlin and Jetpack Compose** using Material 3 Expressive architecture.

## Overview

ADB Application Manager Pro allows taking full control of an Android device directly on-device — no PC required. It provides comprehensive package management, debloating, privileged installation, hidden settings editing, terminal shell execution, and storage hygiene.

## Core Features

- 🧹 **App & Package Management**:
  - Live inspection of installed packages (User apps, System apps, Frozen/Disabled, Bloatware).
  - Privileged actions: Freeze / Disable (`pm disable-user --user 0`), Unfreeze (`pm enable`), Uninstall, OEM Reinstall (`cmd package install-existing`), Clear Data & Cache (`pm clear`), and Force Stop.
  - Universal Android Debloater (UAD) integration: categorizes OEM bloatware (Recommended, Advanced, Expert) with multi-selection batch freeze and uninstall.
- 📦 **Privileged Package Installer & Inspector**:
  - Validates `.apk`, `.apks`, `.apkm`, `.xapk` bundles.
  - Configurable `pm install` execution flags: Auto-grant runtime permissions (`-g`), allow test packages (`-t`), allow version downgrade (`-d`), keep existing app data (`-r`), and bypass Android 14+ minimum target SDK blocks.
- 🛠️ **Hidden System Settings Editor**:
  - Real-time viewer and editor for Android's `Global`, `Secure`, and `System` settings namespaces.
  - Curated catalogue of developer and performance options (Animation Scales, USB Debugging, Private DNS, Screen timeout, Stay on while plugged in).
  - Built-in Undo history stack to safely revert setting modifications.
- 💻 **Interactive Terminal & ADB Console**:
  - Integrated shell runner supporting Local Shell and Shizuku/Privileged modes.
  - Monospace console output with exit code badges and execution timestamps.
  - Quick-action presets (`pm list packages`, `dumpsys battery`, `getprop`, `df -h`, `top`, `uname -a`).
  - Output selection, copying, and history log.
- 🧼 **SD Maid Storage Hygiene**:
  - Ported system cleaner, CorpseFinder, and clutter scanner.
  - Identifies reclaimable space across App Caches, JIT code cache, log files, thumbnails, and orphaned directories.
  - Checkbox category selection with one-tap cleanup and reclaim counter.
- 📊 **Real-Time Telemetry & Hardware Monitor**:
  - RAM usage gauge (used vs total) via `ActivityManager`.
  - Storage space gauge via `StatFs`.
  - Battery metrics (level, health, temperature, charging status).
  - Hardware specifications table (Model, Manufacturer, Board, SoC, Android OS & API level, Security Patch, Kernel version).

## Architecture

- **Language:** Kotlin
- **UI Framework:** Jetpack Compose (Material 3)
- **Design System:** Cyber-tech dark palette with glowing accents (#00E5FF neon cyan, #7C4DFF secondary purple, deep dark surfaces)
- **Build System:** Gradle Kotlin DSL (`build.gradle.kts`) with Android Gradle Plugin 9.1.1 and Kotlin Compose Compiler
