<div align="center">

<img src="assets/app_icon.png" width="112" height="112" alt="App icon: the Android robot in glasses and a tie, in a blue toolbox with a wrench">

# ADB/Root Toolbox

### Take full control of your Android phone — from the phone itself.

Debloat, freeze, uninstall, update, install, back up and inspect **every app** · edit Android's hidden settings ·
recolor the whole system · run a real terminal with AI coding agents — **no computer needed**.

[![Build APK](https://github.com/Bingblop/ADB-Application-Manager/actions/workflows/build.yml/badge.svg)](https://github.com/Bingblop/ADB-Application-Manager/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/Bingblop/ADB-Application-Manager?label=release)](https://github.com/Bingblop/ADB-Application-Manager/releases/latest)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![No ads, no account, no tracking](https://img.shields.io/badge/no%20ads%20%C2%B7%20no%20account%20%C2%B7%20no%20tracking-444)

## [⬇️ Get the latest APK](https://github.com/Bingblop/ADB-Application-Manager/releases/latest)

`v7.13.4-Pro` · signed · installs over any earlier version · works with **ADB, Wireless Debugging, Shizuku or Root**

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
- 🎨 **Recolor Android itself.** Pick any Material You color, flip system overlays, and tune the app with **Material 3 Expressive animations**. With root, write **pure black backgrounds, more styles and your own accents** into Android's theme.
- 🛠️ **Edit hidden settings.** Android's Global, Secure and System tables, with plain-English descriptions, read-back proof and Undo.
- 🔒 **Private by design.** No ads, no account, no analytics, no server of ours. Your ADB key is generated on your phone.

## Everything in one app

| | |
|---|---|
| **Apps** | Search, sort and filter every package · freeze, suspend, uninstall (even system apps), clear data, reinstall · batch actions · saved lists · profiles you can share to another phone · CSV export |
| **Installer/Updater** | Two boxes in one tab. **Application Installer**: `.apk`, `.apks`, `.apkm`, `.xapk` (with OBB) · every `pm install` flag · splits that fit your phone pre-ticked · optional VirusTotal check. **Application Updater**: **find and download an app by name or package** (APKMirror, Uptodown, Aptoide, F-Droid, IzzyOnDroid; Google Play opens its page) · update the app itself, Galaxy Store apps and open-source apps · a **KeyStore** card to export, import or renew the signing key |
| **App Stores** | ShizuStore, GitHub, F-Droid and Orion catalogs · **a + tab adds a store of your own** (any F-Droid style repository, or a GitHub or Codeberg user, organization or project) · **every store opens the full description and screenshots of an app** |
| **Inspector** | Ten tabs per app: permissions, App Ops, activities, components (services, receivers, providers; enable or disable any, with Root) · decoded manifest · features, configurations (screens, Android versions), signatures (v1-v3.1, certificates, SHA-256) and libraries (native .so by CPU) · launch unexported activities |
| **Files** | Privileged file manager · open APKs and archives (zip, 7z, rar, tar) without extracting · edit, sign, compare · add an SD card or USB drive |
| **Console** | **ADB Shell** with a history of cards (command, outcome, output; Copy and Run again), cheat sheet and Rish shell · **Live Logcat** next to it (filter, levels, one app, record to file) · **grey command suggestions as you type (Right arrow to accept)** |
| **Terminal** | Terminal with three shells (sandbox, working mode, **your own Termux**, with a **one-tap setup** of vim, nano, git and python and a starter .bashrc) and AI agents · **full-screen real terminal** (vim, htop, ssh: a genuine pty) · the same grey command suggestions |
| **Hidden Settings & Overlays** | Edit Android's settings tables, each explained with what it does and its values · Material You color and style · switch overlays on and off: **enabled first, then disabled, then not changeable, each with a one-line description of what it is for** · a **root theme card** (with Super User): **pure black backgrounds**, **Color spec** 2021, 2025 or 2026, **more styles** (Fidelity, Content, Monochrome, CMF), **saturation and lightness sliders**, **your own secondary and tertiary accents** and **Accurate shades**, worked out the way each release builds its theme on **Android 14, 15, 16 and 17 and their quarterly updates** (Pure black only on 12 and 13; other versions and One UI locked) |
| **Root Features** | For a phone that is already rooted: the state of root for this app · **Request Super User** and **Open root manager** to grant it · **Super User for apps** opens the manager to choose which other apps get root · **App components** (enable or disable single parts of any app, system apps included) · locked, never hidden, without root; the app roots nothing |
| **Custom Fonts** | Make your own font for a Samsung phone out of a `.ttf` or `.otf` file: a real **Bold** (a second file, or another weight of the same variable font) and **color emoji inside the font** · a variable font is fixed at the weight you pick, PostScript outlines are converted, and the result is read back glyph by glyph before it is kept · it becomes a signed Samsung font package (no program code) to **Install**, **Export**, or **Apply** straight to the phone's font folder with Root or Shizuku as the system user (UID 1000), with **Reset to Default** back · **Search Google Fonts** without leaving the tab: every family drawn in its own font, a type tester, **Download** a family and **Use in Make a font** · **five emoji fonts to download** (Noto Color Emoji, Twemoji, Blobmoji, OpenMoji, iOS emoji), each checked against its size and SHA-256, with sixteen sample emoji under each to see it first (the iOS ones on request, read from the iOS emoji file and never shipped) · with Super User, a downloaded one as the phone's own **System emoji** until the next restart, with **Undo** and **Soft reboot** |
| **Connected Devices** | Manage a **Wear OS watch** (or another Android device) from your phone: pair over Wi-Fi or link over Bluetooth · apps (enable, disable, uninstall, reinstall) · send APK, APKS, APKM and XAPK · console, logcat, files, hidden settings, screen density · **Reconnect** (or **Reconnect all**) devices that dropped, with timings you set · **saved devices** you can reconnect or delete |
| **Morphe Patcher** | Patch apps with [Morphe](https://github.com/MorpheApp) on the phone: patch sources and the community finder · **Installed** apps marked · Morphe Helper downloads the right APK version (ten sources, an **in-app browser** that passes APKMirror's check and saves bundles straight in with a pause and resume download list, optional VirusTotal) · Simple and Advanced patching · install when finished · live log · Patched APKs |
| **SD Maid** | SystemCleaner, AppCleaner, CorpseFinder and Deduplicator, ported from [SD Maid SE](https://github.com/d4rken-org/sdmaid-se) · scan, review and untick, delete · optional 1-tap scan and delete · exclusion manager · history · accessibility cache clearing |
| **Help Guide** | A complete guide for beginners inside the app (About tab): table of contents, search, every tab explained, recipes, safety, troubleshooting |
| **Monitoring** | Task Manager (processes with **Apps first**, CPU with °C/°F, RAM, GPU with the renderer up top, battery, network) · color-coded Logcat for one app, **tap an entry for a window with Copy, Web search and AI explanation** · Device Specs |
| **Quick actions** | Quick Settings tiles (**Working mode**, lit while a mode is ready, and **Kill Apps**; a long press opens the app) and a home-screen widget |
| **Free up space** | **Trim Caches in All Applications** (`pm trim-caches 128G`, in the **SD Maid** tab) clears every app's cache, with a plain explanation of what is and is not touched |
| **Make it yours** | Material 3 and Material You themes · **ten palette styles** (Tonal Spot to CMF) from any source color · **save your own themes** · light, dark, schedule, pure black · **Expressive Animations** · 14 languages · custom font · reorder or hide tabs · **haptic feedback** on every tap, with a switch in Settings |

## Install in 30 seconds

1. Download the APK from the [**Releases page**](https://github.com/Bingblop/ADB-Application-Manager/releases/latest): `-arm64-v8a` for almost every phone made since 2016, `-armeabi-v7a` for an older 32-bit phone, or `-universal` if you are not sure (it holds both). Check it against `SHA256SUMS.txt` if you like.
2. Open it and allow installs from your browser or file manager.
3. Pick a [working mode](docs/FULL-GUIDE.md#working-modes). Easiest: **ADB over TCP** (run `adb tcpip 5555` once) or **Wireless Debugging** on Android 11+ with the in-app pairing flow.

Updating? Just install the new APK over the old one, or let the app tell you when a release is out.

### Get it with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) installs the app straight from this repository's Releases page and tells you when a new version is out. Tap the badge on your phone (it opens Obtainium, or offers to install it first); the configuration it adds picks the `-arm64-v8a` file of each release.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.bloatware.bingblop%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FBingblop%2FADB-Application-Manager%22%2C%22author%22%3A%22Bingblop%22%2C%22name%22%3A%22ADB%20Application%20Manager%20Pro%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22arm64-v8a%5C%22%7D%22%7D"><img src="docs/screenshots/badge_obtainium.png" height="56" alt="Get it on Obtainium"></a>

Adding it by hand instead: in Obtainium tap **+**, paste `https://github.com/Bingblop/ADB-Application-Manager`, and under *Filter APKs by regular expression* enter `arm64-v8a` (or `universal` for the file that holds both CPU types).

Verification info, for Obtainium's and other installers' signature checks:

| | |
| --- | --- |
| Package ID | `com.bloatware.bingblop` |
| Signing certificate SHA-256 | `94:61:87:0F:7C:D2:40:64:06:A1:19:F4:D3:75:33:C2:3B:B7:D1:ED:85:05:06:BA:D7:EA:3E:6B:4B:D2:6D:17` |

### Signing certificate

Every release APK is signed with the same certificate. Check it against these values before you trust a copy that did not come from the Releases page (it is the same for the `-arm64-v8a`, `-armeabi-v7a` and `-universal` files):

| | |
| --- | --- |
| Owner | `CN=bingblop, OU=adb, O=appmanager, L=local, ST=android, C=US` |
| SHA-1 | `33:6E:8B:D2:71:A2:25:4D:78:5E:E2:46:63:F2:F4:68:3C:DB:93:C7` (`336e8bd271a2254d785ee24663f2f4683cdb93c7`) |
| SHA-256 | `94:61:87:0F:7C:D2:40:64:06:A1:19:F4:D3:75:33:C2:3B:B7:D1:ED:85:05:06:BA:D7:EA:3E:6B:4B:D2:6D:17` (`9461870f7cd2406406a119f4d37533c23bb7d1ed850506bad7ea3e6b4bd26d17`) |

To read it from an APK: `apksigner verify --verbose --print-certs <file>.apk` (Android SDK build-tools). A build signed with another key (a local `./build.sh` without your own `release.keystore`, which makes a new one, or a pull-request build without the repository's signing secret) shows a different value and will not install over the app. The release workflow stops, before publishing, if an APK is not signed with this certificate (`tools/check-signing-cert.sh`).

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

## What's new

- **Contact the developer and Issues** at the top of the About tab: Issues opens the GitHub issue page; Contact opens your email app with the subject "ADB App Manager", addressed to a forwarding alias (bingblop.coral666@simplelogin.fr) so the developer's own address stays private.
- **Honest results**: after an uninstall, reinstall, freeze, unfreeze, suspend or unsuspend (one app or a batch, on this phone or on a connected device), and after Clear data in Root mode, the app asks what became of each app, says *Checking the phone…* (or *the device…*) while it does, and shows *Uninstalled / Still installed*, *Data cleared* and so on instead of trusting the command's exit status. **Settings → Progress messages** turns the "Working on it…" / "Checking…" messages off.
- **Read-back everywhere**: Force stop (is the process gone?) and Clear data (files counted, also in ADB/Shizuku mode, with a check for files the app writes again); a **spinner on the row** while an app is changed and checked; **Removal Levels** in the Debloater list; Morphe Helper sends you to the right APKMirror bundle page when the browser check stands in the way.
- **SD Maid → Clear Data from Uninstalled Apps** clears the leftover data of apps removed with the keep-data flag. **Batch menu**: a pull handle, outlined Select All / Clear All, Show Applications, Keep selecting always on. **One VirusTotal key** in Settings for the Installer and Morphe Helper.
- **v7.19.5**: **Smart Tabs** now puts the tabs you open most on the **bottom bar** itself (Application Manager and the three tabs you open most, pinned or not), not only first under More, and updates the order every time the app comes back to the front.
- **v7.19.4**: **Smart Tabs** (Settings → Navigation, off by default) puts the tabs you open most right after Application Manager on both tab bars; **Ask agent** on every setting in **Connected Devices → Hidden Settings**; **Agent API keys** with a **Test key** button in Settings → Default Ask Agent, and a **green check mark** next to every agent whose key was tested and works, there and in the Terminal's Coding Agents list.
- **v7.19.3**: a **new app icon**, the Android robot in glasses and a tie in a blue toolbox with a wrench, with **no background** behind it on the home screen (the launcher's circle or squircle never cuts it); the About tab shows it too. In Connected Devices the **ADB Stub opens on a watch as soon as it connects** and shows the watch's **IP address and port**, and every app of a connected device has the Application Manager's **Permissions, App Ops, Activities, Components and Manifest** tabs: grant and revoke, set app ops, launch an activity on the watch, with the same maximize button.
- **v7.19.2**: the Custom Fonts tab's **Phone font** card comes **before Emoji fonts**; on App Stores the **?** help button has a line of its own **above the mobile data switch**, outside it, and the switch (and App Updater's) is **thinner**; **Hidden Settings remember what Ask agent found**: every answer leaves one short AI-written line with that setting, shown on its row (marked AI) and in its sheet, searchable, kept for good.
- **v7.19.1**: the app is renamed **ADB/Root Toolbox** (same package, installs over every earlier version). The Custom Fonts tab's **Phone font** card is a drop-down of **every font installed on the phone** and every font made there: choose one to use it, press and hold one to uninstall it (asked first; Default, the phone's own fonts and the font in use stay). A font **not signed by Samsung or Monotype** needs **Root, or Shizuku as the system user (UID 1000) or as root (UID 0)**; without them the app says so and changes nothing. The mobile data switches of App Stores and App Updater sit **below the ? help button** instead of over it.
- **v7.19.0**: **Automatic** now tries **Super User first**, then Shizuku as UID 0 or 1000, ADB over TCP, Wireless Debugging and Shizuku as UID 2000, and a chosen Super User that goes away switches back to Automatic by itself; Connected Devices lists **paired devices found on your Wi-Fi** with their current IP and port (one tap to connect, and a new pairing connects by itself), and keeps **one saved entry per IP**; **Kill Apps** runs to the end with its live notification (System UI and the tools it needs are stopped last); app-op permissions such as Display over other apps get a working toggle and app-op changes are read back; DNS **Sort by speed** is on by default and remembered; slim mobile-data switches at the top of App Stores and the App Updater.

## License

Open source under the **GNU General Public License v3.0**: the full text is in [LICENSE](LICENSE). The app builds in GPL-3.0 work (morphe-patcher, and a port of the four SD Maid SE tools), which is why the whole app is GPL-3.0. Third-party notices are in [NOTICE](NOTICE).

## Support & credits

Found a bug? [Open an issue](https://github.com/Bingblop/ADB-Application-Manager/issues), or use **About → Contact the developer** in the app. Like it? ☕ **Buy me a coffee** is in the About tab.

Debloat data from [UAD-NG](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation) (GPL-3.0, downloaded at runtime) ·
tracker list from [Exodus Privacy](https://exodus-privacy.eu.org) (ODbL 1.0) ·
shell access via [Shizuku](https://github.com/RikkaApps/Shizuku-API) ·
update sources: Galaxy Store, GitHub, Codeberg, [F-Droid](https://f-droid.org), [IzzyOnDroid](https://apt.izzysoft.de/fdroid) and the [Obtainium](https://github.com/ImranR98/Obtainium) catalog ·
the Task Manager tab is modeled on [RohitKushvaha01/TaskManager](https://github.com/RohitKushvaha01/TaskManager) ·
the Morphe Patcher tab is built on the [Morphe](https://github.com/MorpheApp) project: the engine is [morphe-patcher](https://github.com/MorpheApp/morphe-patcher), the workflow follows [Morphe Manager](https://github.com/MorpheApp/morphe-manager) and [morphe-cli](https://github.com/MorpheApp/morphe-cli), the APK downloads and VirusTotal check follow [Helper for Morphe](https://github.com/rushiranpise/helper-for-morphe) (all GPL-3.0); patches belong to their authors. ·
the SD Maid tab is a port of the four tools of [SD Maid SE](https://github.com/d4rken-org/sdmaid-se) by darken (GPL-3.0; also on [Google Play](https://play.google.com/store/apps/details?id=eu.darken.sdmse)) ·
the descriptions of Android's own settings in Hidden Settings are taken in part from the [Android Open Source Project](https://source.android.com/) documentation (Apache-2.0) ·
the colors of More styles (Fidelity, Content, Monochrome) are computed by `assets/monet.js`: on Android 14 by a port of Android 14's own color engine from the [Android Open Source Project](https://source.android.com/) and Google's [Material Color Utilities](https://github.com/material-foundation/material-color-utilities) 0.4.0, on Android 15, 16 and 17 (and the 2025 and 2026 color specs and CMF everywhere) by a port of Material Color Utilities on GitHub (main, commit bd811e4, 2026), and on Android 14 QPR1 to QPR3, 15.0, 16 QPR1, 16 QPR2 and 17 following the changes in Android's own copy of that library (libmonet, Android Open Source Project); the names and order they are written in follow each Android release's own theme code (all Apache-2.0)
