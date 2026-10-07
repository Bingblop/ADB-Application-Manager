<div align="center">

# ⚡ ADB Application Manager Pro

### Take full control of your Android phone — from the phone itself.

Debloat, freeze, uninstall, update, install, back up and inspect **every app** · edit Android's hidden settings ·
recolor the whole system · run a real terminal with AI coding agents — **no computer needed**.

[![Build APK](https://github.com/Bingblop/ADB-Application-Manager/actions/workflows/build.yml/badge.svg)](https://github.com/Bingblop/ADB-Application-Manager/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/Bingblop/ADB-Application-Manager?label=release)](https://github.com/Bingblop/ADB-Application-Manager/releases/latest)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![No ads, no account, no tracking](https://img.shields.io/badge/no%20ads%20%C2%B7%20no%20account%20%C2%B7%20no%20tracking-444)

## [⬇️ Get the latest APK](https://github.com/Bingblop/ADB-Application-Manager/releases/latest)

`v7.10.3-Pro` · signed · installs over any earlier version · works with **ADB, Wireless Debugging, Shizuku or Root**

<table>
  <tr>
    <td><img src="docs/screenshots/apps-dark.png" width="210" alt="Application list"></td>
    <td><img src="docs/screenshots/app-menu.png" width="210" alt="App menu"></td>
    <td><img src="docs/screenshots/debloat-flow.gif" width="210" alt="Debloating in seven steps"></td>
  </tr>
  <tr>
    <td align="center"><sub>Every app, sorted and filtered</sub></td>
    <td align="center"><sub>One tap to freeze, back up, inspect</sub></td>
    <td align="center"><sub>Debloat in seven steps, with Undo</sub></td>
  </tr>
</table>

</div>

---

## Why you'll want it

- 🧹 **Debloat in minutes.** The 5,000-package UAD-NG list, a review step before anything runs, and **one-tap Undo** for every change.
- ⚡ **Fast and never frozen.** Batch actions, Dex optimization and profiles run in the background with a live progress bar and a Stop button.
- 🖥️ **A real terminal in your pocket.** Termux-style shells, plus **AI coding agents** (Gemini, Claude, ChatGPT, Grok, Deepseek, Ollama on the phone…) that run commands and edit files **only with your OK**.
- 🎨 **Recolor Android itself.** Pick any Material You color, flip system overlays, and tune the app with **Material 3 Expressive animations**.
- 🛠️ **Edit hidden settings.** Android's Global, Secure and System tables, with plain-English descriptions, read-back proof and Undo.
- 🔒 **Private by design.** No ads, no account, no analytics, no server of ours. Your ADB key is generated on your phone.

## Everything in one app

| | |
|---|---|
| **Apps** | Search, sort and filter every package · freeze, suspend, uninstall (even system apps), clear data, reinstall · batch actions · saved lists · profiles you can share to another phone · CSV export |
| **Installer** | `.apk`, `.apks`, `.apkm`, `.xapk` (with OBB) · every `pm install` flag · splits that fit your phone pre-ticked · optional VirusTotal check |
| **Updates & stores** | Update the app itself, Galaxy Store apps, and open-source apps from GitHub, Codeberg, F-Droid and IzzyOnDroid · ShizuStore, GitHub, F-Droid and Orion catalogs |
| **Inspector** | Ten tabs per app: permissions, App Ops, activities, components (services, receivers, providers; enable or disable any) · decoded manifest · features, configurations (screens, Android versions), signatures (v1-v3.1, certificates, SHA-256) and libraries (native .so by CPU) · launch unexported activities |
| **Files** | Privileged file manager · open APKs and archives (zip, 7z, rar, tar) without extracting · edit, sign, compare · add an SD card or USB drive |
| **Command-Line Interface** | Terminal with three shells (sandbox, working mode, **your own Termux**) and AI agents · classic ADB Console with cheat sheet and Rish shell |
| **Hidden Settings & Overlays** | Edit Android's settings tables, each explained with what it does and its values · Material You color and style · switch overlays on and off |
| **Connected Devices** | Manage a **Wear OS watch** (or another Android device) from your phone: pair over Wi-Fi or link over Bluetooth · apps (enable, disable, uninstall, reinstall) · send APK, APKS, APKM and XAPK · console, logcat, files, hidden settings, screen density |
| **Morphe Patcher** | Patch apps with [Morphe](https://github.com/MorpheApp) on the phone: patch sources and the community finder · **Installed** apps marked · Morphe Helper downloads the right APK version (ten sources, optional VirusTotal) · Simple and Advanced patching · install when finished · live log · Patched APKs |
| **SD Maid SE** | SystemCleaner, AppCleaner, CorpseFinder and Deduplicator, ported from [SD Maid SE](https://github.com/d4rken-org/sdmaid-se) · scan, review and untick, delete · optional 1-tap scan and delete · exclusion manager · history · accessibility cache clearing |
| **Help Guide** | A complete guide for beginners inside the app (About tab): table of contents, search, every tab explained, recipes, safety, troubleshooting |
| **Monitoring** | Task Manager (processes, CPU, RAM, GPU, battery, network) · color-coded Logcat for one app · Device Specs |
| **Quick actions** | Quick Settings tiles and a home-screen widget |
| **Free up space** | One button in Settings runs `pm trim-caches 128G` to clear every app's cache, with a plain explanation of what is and is not touched |
| **Make it yours** | Material 3 and Material You themes · light, dark, schedule, pure black · **Expressive Animations** · 14 languages · custom font · reorder or hide tabs |

## Install in 30 seconds

1. Download the APK from the [**Releases page**](https://github.com/Bingblop/ADB-Application-Manager/releases/latest): `-arm64-v8a` for almost every phone made since 2016, `-armeabi-v7a` for an older 32-bit phone, or `-universal` if you are not sure (it holds both). Check it against `SHA256SUMS.txt` if you like.
2. Open it and allow installs from your browser or file manager.
3. Pick a [working mode](docs/FULL-GUIDE.md#working-modes). Easiest: **ADB over TCP** (run `adb tcpip 5555` once) or **Wireless Debugging** on Android 11+ with the in-app pairing flow.

Updating? Just install the new APK over the old one, or let the app tell you when a release is out.

## See more

<table>
  <tr>
    <td><img src="docs/screenshots/installer.png" width="190" alt="Installer"></td>
    <td><img src="docs/screenshots/files.png" width="190" alt="File manager"></td>
    <td><img src="docs/screenshots/overlays.png" width="190" alt="Overlays and Material You"></td>
    <td><img src="docs/screenshots/settings.png" width="190" alt="Hidden Settings"></td>
  </tr>
  <tr>
    <td align="center"><sub>Installer</sub></td>
    <td align="center"><sub>File manager</sub></td>
    <td align="center"><sub>Material You</sub></td>
    <td align="center"><sub>Hidden Settings</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/themes.png" width="190" alt="Themes"></td>
    <td><img src="docs/screenshots/profiles.png" width="190" alt="Profiles"></td>
    <td><img src="docs/screenshots/logcat.png" width="190" alt="Logcat"></td>
    <td><img src="docs/screenshots/updates.png" width="190" alt="Updates"></td>
  </tr>
  <tr>
    <td align="center"><sub>Themes</sub></td>
    <td align="center"><sub>Profiles</sub></td>
    <td align="center"><sub>Logcat</sub></td>
    <td align="center"><sub>Updates</sub></td>
  </tr>
</table>

More screenshots, including the light theme, are in the [full guide](docs/FULL-GUIDE.md#screenshots).

## Deep dives

The complete reference lives in the **[Full Guide](docs/FULL-GUIDE.md)**:
[Terminal & coding agents](docs/FULL-GUIDE.md#terminal-and-coding-agents) ·
[Working modes](docs/FULL-GUIDE.md#working-modes) ·
[Debloater](docs/FULL-GUIDE.md#debloater) ·
[Hidden settings](docs/FULL-GUIDE.md#hidden-settings) ·
[Overlays & Material You](docs/FULL-GUIDE.md#overlays-and-material-you) ·
[Backup & restore](docs/FULL-GUIDE.md#backup-and-restore) ·
[Permissions](docs/FULL-GUIDE.md#permissions) ·
[Privacy & security](docs/FULL-GUIDE.md#privacy--security) ·
[Building](docs/FULL-GUIDE.md#building) ·
[Testing](docs/FULL-GUIDE.md#testing).
What changed in each version: [CHANGELOG.md](CHANGELOG.md).

## What's new in v7.9

- **Added storage** (SD card, USB drive) now copies, moves, previews, shares and searches too.
- **App icons** in the list (cached, press and hold to save one), with an **Icon pack** setting to draw them from an installed icon pack.
- **Expressive Animations**: Material 3 Expressive's springier motion across the app (Settings → Motion, on by default).
- **Sync with Termux**: the in-app Termux shell matches your real one (aliases and functions included) and gets storage access in one tap.
- **Add storage** in the file manager (SD card, USB drive, shared folders) and **Device Specs** in About.
- **No more freezes**: batch actions, Dex optimization and profiles run in the background with progress and Stop.
- **Command-Line Interface** with Grok, Muse, Deepseek, Effort control, a Shift key and per-shell cheat sheets.
- Systemless uninstall for stubborn system apps, honest exit-status results, a cleaner Apps list.

## Support & credits

Found a bug? [Open an issue](https://github.com/Bingblop/ADB-Application-Manager/issues). Like it? ☕ **Buy me a coffee** is in the About tab.

Debloat data from [UAD-NG](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation) (GPL-3.0, downloaded at runtime) ·
shell access via [Shizuku](https://github.com/RikkaApps/Shizuku-API) ·
update sources: Galaxy Store, GitHub, Codeberg, [F-Droid](https://f-droid.org), [IzzyOnDroid](https://apt.izzysoft.de/fdroid) and the [Obtainium](https://github.com/ImranR98/Obtainium) catalog ·
the Task Manager tab is modeled on [RohitKushvaha01/TaskManager](https://github.com/RohitKushvaha01/TaskManager) ·
the Morphe Patcher tab is built on the [Morphe](https://github.com/MorpheApp) project: the engine is [morphe-patcher](https://github.com/MorpheApp/morphe-patcher), the workflow follows [Morphe Manager](https://github.com/MorpheApp/morphe-manager) and [morphe-cli](https://github.com/MorpheApp/morphe-cli), the APK downloads and VirusTotal check follow [Helper for Morphe](https://github.com/rushiranpise/helper-for-morphe) (all GPL-3.0); patches belong to their authors. ·
the SD Maid SE tab is a port of the four tools of [SD Maid SE](https://github.com/d4rken-org/sdmaid-se) by darken (GPL-3.0; also on [Google Play](https://play.google.com/store/apps/details?id=eu.darken.sdmse)) ·
the descriptions of Android's own settings in Hidden Settings are taken in part from the [Android Open Source Project](https://source.android.com/) documentation (Apache-2.0)
