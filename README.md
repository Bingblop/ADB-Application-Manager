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

`v7.12.8-Pro` · signed · installs over any earlier version · works with **ADB, Wireless Debugging, Shizuku or Root**

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
| **Updates & stores** | Update the app itself, Galaxy Store apps, and open-source apps from GitHub, Codeberg, F-Droid and IzzyOnDroid · ShizuStore, GitHub, F-Droid and Orion catalogs · **a + tab adds a store of your own** (any F-Droid style repository, or a GitHub or Codeberg user, organization or project) · **every store opens the full description and screenshots of an app** |
| **Inspector** | Ten tabs per app: permissions, App Ops, activities, components (services, receivers, providers; enable or disable any) · decoded manifest · features, configurations (screens, Android versions), signatures (v1-v3.1, certificates, SHA-256) and libraries (native .so by CPU) · launch unexported activities |
| **Files** | Privileged file manager · open APKs and archives (zip, 7z, rar, tar) without extracting · edit, sign, compare · add an SD card or USB drive |
| **Command-Line Interface** | Terminal with three shells (sandbox, working mode, **your own Termux**, with a **one-tap setup** of vim, nano, git and python and a starter .bashrc) and AI agents · **full-screen real terminal** (vim, htop, ssh: a genuine pty) · classic ADB Console with cheat sheet and Rish shell · **grey command suggestions as you type (Right arrow to accept)** in the Terminal and the ADB Console |
| **Hidden Settings & Overlays** | Edit Android's settings tables, each explained with what it does and its values · Material You color and style · switch overlays on and off: **enabled first, then disabled, then not changeable, each with a one-line description of what it is for** |
| **Connected Devices** | Manage a **Wear OS watch** (or another Android device) from your phone: pair over Wi-Fi or link over Bluetooth · apps (enable, disable, uninstall, reinstall) · send APK, APKS, APKM and XAPK · console, logcat, files, hidden settings, screen density · **Reconnect** (or **Reconnect all**) devices that dropped, with timings you set · **saved devices** you can reconnect or delete |
| **Morphe Patcher** | Patch apps with [Morphe](https://github.com/MorpheApp) on the phone: patch sources and the community finder · **Installed** apps marked · Morphe Helper downloads the right APK version (ten sources, an **in-app browser** that passes APKMirror's check and saves bundles straight in with a pause and resume download list, optional VirusTotal) · Simple and Advanced patching · install when finished · live log · Patched APKs |
| **SD Maid** | SystemCleaner, AppCleaner, CorpseFinder and Deduplicator, ported from [SD Maid SE](https://github.com/d4rken-org/sdmaid-se) · scan, review and untick, delete · optional 1-tap scan and delete · exclusion manager · history · accessibility cache clearing |
| **Help Guide** | A complete guide for beginners inside the app (About tab): table of contents, search, every tab explained, recipes, safety, troubleshooting |
| **Monitoring** | Task Manager (processes with **Apps first**, CPU with °C/°F, RAM, GPU with the renderer up top, battery, network) · color-coded Logcat for one app, **tap an entry for a window with Copy, Web search and AI explanation** · Device Specs |
| **Quick actions** | Quick Settings tiles and a home-screen widget |
| **Free up space** | **Trim Caches in All Applications** (`pm trim-caches 128G`, in the **SD Maid** tab) clears every app's cache, with a plain explanation of what is and is not touched |
| **Make it yours** | Material 3 and Material You themes · **nine palette styles** (Tonal Spot to Monotone) from any source color · **save your own themes** · light, dark, schedule, pure black · **Expressive Animations** · 14 languages · custom font · reorder or hide tabs · **haptic feedback** on every tap, with a switch in Settings |

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

## What's new in v7.10

- **Contact the developer and Issues** at the top of the About tab: Issues opens the GitHub issue page; Contact opens your email app with the subject "ADB App Manager", addressed to a forwarding alias (bingblop.coral666@simplelogin.fr) so the developer's own address stays private.
- **Honest results**: after an uninstall, reinstall, freeze, unfreeze, suspend or unsuspend (one app or a batch, on this phone or on a connected device), and after Clear data in Root mode, the app asks what became of each app, says *Checking the phone…* (or *the device…*) while it does, and shows *Uninstalled / Still installed*, *Data cleared* and so on instead of trusting the command's exit status. **Settings → Progress messages** turns the "Working on it…" / "Checking…" messages off.
- **Read-back everywhere**: Force stop (is the process gone?) and Clear data (files counted, also in ADB/Shizuku mode, with a check for files the app writes again); a **spinner on the row** while an app is changed and checked; **Removal Levels** in the Debloater list; Morphe Helper sends you to the right APKMirror bundle page when the browser check stands in the way.
- **SD Maid → Clear Data from Uninstalled Apps** clears the leftover data of apps removed with the keep-data flag. **Batch menu**: a pull handle, outlined Select All / Clear All, Show Applications, Keep selecting always on. **One VirusTotal key** in Settings for the Installer and Morphe Helper.
- **v7.10.18**: outlined Select All / Clear All on the Selected apps list and the leftover-data sheet; a **bin button on a row of the Uninstalled filter** clears that one app's leftover data; the **VirusTotal card shows how many lookups are left today**.
- **v7.12.8**: the App Updater moved into the **Third Party Stores/Updater** tab (two boxes at the top: Application Stores, Application Updater); History (with pins) in the log-entry and Hidden Settings Ask agent windows; a Test button in every agent's Connect sheet; Disable (blue) and Uninstall (red) in Connected Devices.
- **v7.12.7**: Settings can test the default agent with one sample question; the Ask agent window keeps a short history of answers; the log-entry and Hidden Settings windows have the progress bar and Stop too; Enable / Reinstall are green in Connected Devices.
- **v7.12.6**: Ask agent buttons answer with no agent and no API key (a built-in web lookup: only the search words leave the phone).
- **v7.12.5**: the first box is **Installed** (installed apps only); Installed + Bloatware (Uninstalled) together show every app on the phone. Whole rows are tinted: green running, blue frozen, red uninstalled.

- **v7.12.3**: the Apps tab boxes: **Frozen** blue, **Enabled** a more vivid green, **System** purple (also on the row badges).
- **v7.12.2**: **Clear Data** and **Remove updates** in the app menu run at once too, with no question (like Uninstall since v7.12.1).
- **v7.12.1**: **Uninstall** in the app menu uninstalls at once, without a question (Clear Data and Remove updates still ask).
- **v7.12.0**: Logcat keeps its lines (no more view that empties after two seconds), **Record to file** with a still-picture viewer, a **Default agent** in Settings and **Ask agent** buttons on every hidden setting and the other places that need an explanation, and SD Maid's two data cards moved after its four tools.
- **v7.11.2**: an **Ask the agent** chip in the app menu for apps the UAD-NG list does not know (is it safe to disable?), and all 13 languages are up to date.
- **v7.11.1**: **Hidden Settings back up and restore** (a file of the settings you changed, put back with one confirmation, each one in the history) and **Update the list** for the tracker database.
- **v7.11.0**: a **Trackers** filter and a TRACKERS chip in the app menu (the Exodus Privacy list, read from the apps' code on the phone, nothing sent anywhere); a move done through the shell is no longer reported as failed; the on-device checklist covers v7.1 on.
- **UAD-NG tag**: tap it for the app's description; the meaning of Recommended / Advanced / Expert / Unsafe is one button away (**Removal Levels**).
- **Overlays in order**: enabled, then disabled, then not changeable, each with a one-line description.
- **Command suggestions** in the Terminal, the ADB Console and the Connected Devices console: a grey completion of what you type from your history, the phone's packages and common commands; Right arrow (or a tap) accepts it.
- **Haptic feedback** on every tap, on by default, with a switch in Settings.
- **App Stores**: a **+** tab for stores of your own, and the details and screenshots of an app in every store.
- **Task Manager**: Apps first in Processes, a °C/°F choice for the CPU temperature, the GPU renderer shown first. **Logcat**: tap an entry for a window with Copy, Web search and an AI explanation.
- **Connected Devices**: saved devices you can reconnect or delete. **Hidden Settings**: more researched descriptions, Web search and AI explain. **Apps**: clearer Enabled/Frozen and User/System boxes; "Uninstalled" clears the other boxes.
- **SD Maid** (the tab's new short name) now holds Trim Caches, and AppCleaner can clear the remaining caches through the accessibility service.
- **Palette styles**, **saved themes**, **Saved lists backup**, **Morphe Helper** downloads in the background, **Reconnect all** for devices, screenshots in full screen. Details in [CHANGELOG.md](CHANGELOG.md).

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

Found a bug? [Open an issue](https://github.com/Bingblop/ADB-Application-Manager/issues), or use **About → Contact the developer** in the app. Like it? ☕ **Buy me a coffee** is in the About tab.

Debloat data from [UAD-NG](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation) (GPL-3.0, downloaded at runtime) ·
tracker list from [Exodus Privacy](https://exodus-privacy.eu.org) (ODbL 1.0) ·
shell access via [Shizuku](https://github.com/RikkaApps/Shizuku-API) ·
update sources: Galaxy Store, GitHub, Codeberg, [F-Droid](https://f-droid.org), [IzzyOnDroid](https://apt.izzysoft.de/fdroid) and the [Obtainium](https://github.com/ImranR98/Obtainium) catalog ·
the Task Manager tab is modeled on [RohitKushvaha01/TaskManager](https://github.com/RohitKushvaha01/TaskManager) ·
the Morphe Patcher tab is built on the [Morphe](https://github.com/MorpheApp) project: the engine is [morphe-patcher](https://github.com/MorpheApp/morphe-patcher), the workflow follows [Morphe Manager](https://github.com/MorpheApp/morphe-manager) and [morphe-cli](https://github.com/MorpheApp/morphe-cli), the APK downloads and VirusTotal check follow [Helper for Morphe](https://github.com/rushiranpise/helper-for-morphe) (all GPL-3.0); patches belong to their authors. ·
the SD Maid tab is a port of the four tools of [SD Maid SE](https://github.com/d4rken-org/sdmaid-se) by darken (GPL-3.0; also on [Google Play](https://play.google.com/store/apps/details?id=eu.darken.sdmse)) ·
the descriptions of Android's own settings in Hidden Settings are taken in part from the [Android Open Source Project](https://source.android.com/) documentation (Apache-2.0)
