<div align="center">

<img src="assets/app_icon.png" width="112" height="112" alt="App icon: the Android robot in glasses and a tie, in a blue toolbox with a wrench">

# ADB Application Manager Pro

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
- **v7.10.18**: outlined Select All / Clear All on the Selected apps list and the leftover-data sheet; a **bin button on a row of the Uninstalled filter** clears that one app's leftover data; the **VirusTotal card shows how many lookups are left today**.
- **v7.18.3**: as root, a file's **Permissions…** also **restores the default SELinux label** (restorecon) of a file or of everything inside a folder: the fix for a file copied into another app's data that the app cannot use; a saved **VirusTotal API key** shows as dots in Settings instead of a blank field; Live Logcat has a **Row tints** button that opens the tint slider and Gradient switch as a sheet.
- **v7.19.3**: a **new app icon**, the Android robot in glasses and a tie in a blue toolbox with a wrench, with **no background** behind it on the home screen (the launcher's circle or squircle never cuts it); the About tab shows it too.
- **v7.19.2**: the Custom Fonts tab's **Phone font** card comes **before Emoji fonts**; on App Stores the **?** help button has a line of its own **above the mobile data switch**, outside it, and the switch (and App Updater's) is **thinner**; **Hidden Settings remember what Ask agent found**: every answer leaves one short AI-written line with that setting, shown on its row (marked AI) and in its sheet, searchable, kept for good.
- **v7.19.1**: the app is renamed **ADB/Root Toolbox** (same package, installs over every earlier version). The Custom Fonts tab's **Phone font** card is a drop-down of **every font installed on the phone** and every font made there: choose one to use it, press and hold one to uninstall it (asked first; Default, the phone's own fonts and the font in use stay). A font **not signed by Samsung or Monotype** needs **Root, or Shizuku as the system user (UID 1000) or as root (UID 0)**; without them the app says so and changes nothing. The mobile data switches of App Stores and App Updater sit **below the ? help button** instead of over it.
- **v7.19.0**: **Automatic** now tries **Super User first**, then Shizuku as UID 0 or 1000, ADB over TCP, Wireless Debugging and Shizuku as UID 2000, and a chosen Super User that goes away switches back to Automatic by itself; Connected Devices lists **paired devices found on your Wi-Fi** with their current IP and port (one tap to connect, and a new pairing connects by itself), and keeps **one saved entry per IP**; **Kill Apps** runs to the end with its live notification (System UI and the tools it needs are stopped last); app-op permissions such as Display over other apps get a working toggle and app-op changes are read back; DNS **Sort by speed** is on by default and remembered; slim mobile-data switches at the top of App Stores and the App Updater.
- **v7.18.2**: **Root File Management** in Root Features opens the File Manager, and as root a file's **Permissions…** shows and changes its mode (read, write, run for owner, group and others), owner and group, also for everything inside a folder, with guarded system locations and a Help Guide section on the risks and the undo.
- **v7.18.1**: the **Row tints** slider and a **Gradient** switch right in the Apps search menu (**Gradient now off by default**; a saved choice is kept), **back up and restore** a custom theme in the RRO/Monet Customization tab, **Update stores on mobile data** and **Check for updates on mobile data** switches (both off: Wi-Fi only), and a Root Features row **Additional Monet Customization** that opens the Monet root controls.
- **v7.18.0**: a **Search Google Fonts** page that pulls up over the Custom Fonts tab: search, filter and sort the Google Fonts families, each card drawn in its own font, a family's page with a type tester, sliders for a variable family's axes, its styles and its licence, **Download**, **Use in Make a font** and **Save to phone** (a `.zip`); a Reserved Font Name, or the name an Ubuntu Font Licence family keeps, in the Name of the font you make is warned about. The Emoji fonts card shows the same sixteen emoji in each of its five emoji fonts: four from small sample fonts in the app, the iOS ones read from the iOS emoji file on request (about 0.3 MB) and never shipped. On the Custom Fonts tab, only the Google Fonts page, the emoji downloads and the iOS sample (unless the iOS emoji are downloaded or it was read before) need the internet, and none of them a working mode. Nothing has been run on a phone: `docs/PHONE-SCRIPT-v7.18.0.md` is the phone script.
- **v7.17.0**: a **Custom Fonts** tab: make a Samsung font package from a `.ttf` / `.otf` of yours (a Regular, a Bold from a second file or the same variable font at another weight, colour emoji inside), download five emoji fonts (Noto Color Emoji, Twemoji, Blobmoji, OpenMoji and the iOS emoji), install, export and make it again, and with Root, or Shizuku as the system user (UID 1000) or root (UID 0), put the font on the whole phone with the **Font changer**; with root, a downloaded emoji font can be the phone's emoji until the next restart (**System emoji**). On One UI 8.5, and on 8.0 to 8.4 with the March 2026 security patch or a newer one, Samsung's Font style applies only fonts signed by Samsung or Monotype, so there Apply is the way. Nothing has been run on a phone: `docs/PHONE-SCRIPT-v7.17.0.md` is the phone script.
- **v7.16.2**: **switch an app's trackers off and back on** with Super User: the TRACKERS chip's box has **Disable all trackers** / **Enable all trackers** and a switch per tracker, the batch sheet has **Disable Trackers** / **Enable Trackers**, History's **Undo** switches back exactly what its step switched off, and Root Features has a **Trackers in apps** row; it works in Root mode with su or with Shizuku as the system user (UID 1000) or root (UID 0), and stays locked for the ADB shell (UID 2000). **Live Logcat** wears one colour per level across the whole row and adds only new lines instead of redrawing, so a busy log stays smooth. **Saved lists** are edited by ticking this phone's apps (search, All / In this list / System / User, a count of what was added and removed, NOT INSTALLED rows kept), with the package names still under Edit as text. Also: a **Connection Check** card in Working Modes (with **Turn on Wireless Debugging**), a Wireless Debugging port that changed found again over mDNS (on the saved address, while Wireless Debugging is the working mode), and **Screenshot** / **Record screen** in the Console. Nothing has been run on a phone: `docs/PHONE-SCRIPT-v7.16.2.md`
- **v7.16.1**: the **Root Features** tab and the per-app **component Enable / Disable** now unlock under **Shizuku** running as the system user (**UID 1000**) or root (**UID 0**), not only in Root mode with su — Android lets those two change a single component, but still refuses the ADB shell (UID 2000), which stays locked. The root theme card (a fabricated colour overlay) still needs UID 0, so Shizuku as UID 1000 keeps it locked. The working-mode badge names the Shizuku server's real UID. UID 1000 is the system user, not root. Nothing has been run on a phone: `docs/PHONE-SCRIPT-v7.16.1.md` is the phone script.
- **v7.16.0**: a **Root Features** tab for a phone that is already rooted (Request Super User, **Open root manager** to grant it, **Super User for apps**, and **App components**, now Root only and for system apps too) and a **root theme card** on the RRO/Monet Customization tab: **pure black backgrounds**, **Color spec** 2021, 2025 or 2026, **more styles** (Fidelity, Content, Monochrome, CMF), **Accent saturation, Background saturation and Background lightness** sliders, **your own secondary and tertiary accents** and **Accurate shades**, worked out the way each release builds its theme on **Android 14 (and QPR1 to QPR3), 15.0, 15 QPR1 and QPR2, 16.0, 16 QPR1 and QPR2, and 17**, One UI included (served by its Android version), locked on other versions. Also a tenth palette style, **CMF**, in Settings; **Start Shizuku (ADB)** from the app, and an offer at launch; **Allow all** restarts the app by itself; the **Kill Apps** tile (renamed from Stop apps) and a **Working mode** tile lit only while a mode is ready; saving a list under a name in use asks Overwrite or Create new; and the fixes of two test passes. Nothing has been run on a phone: `docs/PHONE-SCRIPT-v7.16.0.md` is the phone script.
- **v7.15.0**: a **top-to-bottom UI pass** and a new name, **ADB/Root Full System Toolbox** (same package, installs over every earlier version). The header is the name in one colour with only the Android and One UI versions under it; every tab is calmer (tonal buttons, segmented switches, rows of chips that fade at the edge, folding cards, two-line descriptions that open with a tap); and the things used most are closer: **Running, Frozen and Uninstalled** on a hold of the Apps tab, **Search files, Storage and Downloads** on a hold of Files, the Console's **History** as a choosable shortcut, **Select shown** next to Clear filters, the Debloater's **Update List and History** beside its search, and the open folder's path as **tappable crumbs** in Files. Nothing the app does changed. Nothing has been run on a phone: `docs/PHONE-SCRIPT-v7.15.0.md` is the phone script.
- **v7.14.1**: **Stop apps shows its progress**: the Quick Settings tile and the widget button start a notification with a progress bar and a **Cancel** button (a Live Update on Android 16 and newer), a tap on it opens the quick list's sheet in Saved Applications, and this app, the WebView app and Media Picker are never stopped; a new **Live updates** row in the permissions sheet. Also a **Speed test** section in Extra Settings, a round **Refresh** button in the File Manager, **Installed | Uninstalled** as one choice (also in Connected Devices), a **Gradient** switch and a shortcut for **Row tints**, and no more Updated 7d filter (sort by Last updated). Plus **57 fixes** from a full review. Among them: **Replace** onto an SD card or USB drive merges folders instead of deleting the one that was there, the **full-screen terminal in Shizuku mode** no longer closes the app, SD Maid keeps what an exclusion protects (AppCleaner, a work profile), the Deduplicator no longer deletes the only copy of a file found under two spellings (DCIM and dcim), a tar holding an APK opens as the tar, a failed install keeps its files for another try, a 32-bit app's data backup restores again, and the **speed test's ping** no longer counts Cloudflare's server time and stops when the network changes. Nothing has been run on a phone: `docs/PHONE-SCRIPT-v7.14.1.md` is the phone script.
- **v7.14.0**: a feature release; the app is now called **ADB System Manager** (same package, installs over every earlier version). A **speed test** in Task Manager > Network (Cloudflare, a gauge and live graphs, a warning on mobile data), an **ADB Stub for Wear OS watches** in Connected Devices (keeps the watch screen on and its Wi-Fi connected while connected), a **Self-test** in About, a **history of the last 100 changes** in Hidden Settings, a permissions sheet that **grants by command** and lists every permission, **ANGLE** in the GPU renderer list (Root), **Live Logcat** as its own tab with remembered choices and level colours, **Connected Devices app lists and Logcat that work like the Apps tab and Live Logcat**, the Apps filters in a new order (**3rd Party is now User**), filled gradient buttons, five-step Row tints, folding Settings cards, app-styled dialogs, a faster Task Manager, Undo bars of 3 seconds and a Back to top button; plus the 42 fixes of the v7.12 to v7.14 code review (the ADB Stub lets the watch go 5 minutes after the phone is gone, SD Maid, archives, Morphe, updates). Nothing has been run on a phone: `docs/PHONE-SCRIPT-v7.14.0.md` is the short version.
- **v7.13.4**: **Backup moved to the batch menu.** The app's menu no longer has a Backup button; select one app or several, open the batch menu and tap **Backup** (it took the place of Share List) to save each selected app in turn, with one choice about app data. Restore, Share, Delete and Choose backup file are unchanged. Nothing has been run on a phone: `docs/PHONE-SCRIPT-v7.13.4.md` is the short version.
- **v7.13.3**: a screen-layout release, nothing new to do. The **Apps tab list starts near the top** and **an app's icon is its checkbox** (slim gear and ⋯ buttons), the **File Manager's list starts near the top** with the rarely used options under **⋯ More**, small text and status badges are **readable in every theme** (badges at 4.5:1 contrast; on dark themes the app menu's Freeze and Uninstall buttons are now pastel blue and red), empty lists share one look and give the right advice, buttons have **44 px tap areas**, every sheet closes with the same ✕, long intros show two lines, and the date line in an app's menu is fully translated. Nothing has been run on a phone: `docs/PHONE-SCRIPT-v7.13.3.md` is the one-page version.
- **v7.13.2**: a fix release, nothing new. The **SD Maid AppCleaner starts its accessibility service on its own** (when you have given the consent and a working mode exists) and **never force-stops this app or Android System WebView**; the serious findings of a second review pass: **Choose package files** and Files **Send a file here** deliver again, Files **Delete, Rename and New folder** act on the device you asked about, a **multi-file push and the APK pull stop when the device changes**, a folder pull with skipped files no longer says Saved, restoring an app removed with **keep data** installs it, keys typed while the **real terminal starts** are no longer lost, **Install when finished** no longer deletes the patched APK when the system installer was only opened, a **patch is still shown after the app is reopened**, and the update check's **N of M count only goes forward**. It also carries a full code review of the whole app (wrong device, mixed-up downloads, unpatched apps installed, silent "deleted" reports, encrypted RAR and 7z, and about forty more), a small spacing polish, and GitHub Actions pinned to commit SHAs. Section 29 of the device checklist is one phone step per fix; `docs/PHONE-SCRIPT-v7.13.2.md` is the one-page version.
- **v7.13.1**: a fix release, nothing new. The fifty-four findings of the code review of 2026-10-13: the SD Maid **Deduplicator keeps the copy marked Kept**, a **Connected Devices batch stays on the device it started on**, a **failed privileged command is never counted as success** (a refused `su` is reported), the **terminal closes without freezing the app**, File Manager **rename, copy and extract never overwrite silently** (and an added storage honours Skip and Keep both), a **Hidden Settings restore keeps the real old value**, the Morphe tab loads once, a second Update All keeps the queue, beta versions compare right, Hidden Settings and Overlays act on the running user, and forty more leaks, stale states and hardening items. See the [changelog](CHANGELOG.md); section 28 of the [device checklist](docs/DEVICE-TEST-CHECKLIST.md) is one phone step per fix.
- **v7.13.0**: a feature release. A new **Extra Settings** tab: **Private DNS** with fifteen services and a **speed test** (the card moved here from the gear Settings page), **animation speed**, **brightness bias**, **device telemetry**, **power & reboot**, **Doze & power governor** and **zram & memory**, each reading the phone back before it says anything. A **bottom navigation bar** with four pinned tabs, a More sheet and **long-press shortcuts** (Settings > Navigation chooses bottom bar, top bar or both). A **Console** tab (ADB Shell and Live Logcat) with the **Terminal** as its own tab. The **Application Updater check is rebuilt** (every source at once, cancel, retry, a host that is down named once, saved results). An **Install button on every ShizuStore row**, a **DUMP** row and **Copy grant commands** in the permission sheet, an SD Maid fix for One UI's App info page, and Morphe sources labelled Official and Custom. See the [changelog](CHANGELOG.md).
- **v7.12.11**: a feature and fix release. **Settings has a Private DNS card** (AdGuard DNS, Cloudflare, Quad9, Automatic, Off or your own host name; it reads the phone back to show what it really holds); the **ADB Console ends each command with a result line** ("Finished" or "Looks like a failure" with the reason; Rish mode keeps its own `[exit N]` note instead); **installs through ADB, Shizuku or Root from the Installer, the Updater (and Update All), a restore and the Morphe Patcher now ask first** (updating the app itself does not), and **Read-Only mode no longer installs through a still-connected ADB**; the text on the App Stores install buttons and letter tiles is black or white, whichever reads better. See the [changelog](CHANGELOG.md).
- **v7.12.10**: a patch release. In adb, Shizuku and root mode the app now **asks before it installs a downloaded app**, in a dialog of its own that names the host the file came from; an archive can no longer fill the phone's storage (an entry may only use what is free less a 64 MB reserve) and **tar hard links** extract as copies; a signing key imported into the Morphe Patcher is checked before it replaces the current one; the Morphe Patcher no longer starts two patches at once, loses a finished patch when Downloads is not writable, or lets two source reads cut each other's files, **asks before a custom patch bundle is first loaded**, and **shares its signing key through the Android share sheet** instead of copying it to Downloads; opening a big compressed tar in the File Manager runs in the background and **can be cancelled**, and an archive with more than 500,000 entries is refused in words; the Apps tab gets a **Refresh button** at the end of the search bar. See the [changelog](CHANGELOG.md).
- **v7.12.9**: a security and stability release. The bundled adb prefers a private socket (it falls back to loopback port 5042 on a phone that refuses one); the updater, store, UAD-list, Morphe patch/bundle and Morphe Helper in-app browser downloads follow redirects safely, and installs fail closed when a checksum the source publishes does not match or cannot be verified (a source that publishes none is installed after the package checks) and, for app updates and the app's own update, when a signer differs or cannot be read; the GitHub token and VirusTotal key are sealed in the Keystore; install options, app-op names and dex compile modes are checked against allow-lists (other page-to-app calls are not covered yet); the File Manager's routes and the file search refuse the app's own private data; the full-screen terminal ends a program that ignores the hang-up. Two small additions: **space** and **reset** in Dex optimization, and a **Standby** bucket button in the app menu. See the [changelog](CHANGELOG.md).
- **v7.12.8**: the APK Installer tab is now **APK Installer/Updater** with two boxes (Application Installer, the default, and Application Updater); the App Stores tab keeps its name; **Find and download an app** (search by name or package; APKMirror, Uptodown, Aptoide) and a **KeyStore** card in the Updater box; the **Default Ask Agent** (renamed from Default agent: a research agent, Perplexity by default, **Exa** added, no coding agents in the list); an **Authorization Manager** in About (the app's own authorization code, with copy and refresh buttons; nothing accepts it yet); History (with pins) in the log-entry and Hidden Settings Ask agent windows; a Test button in every agent's Connect sheet; Disable (blue) and Uninstall (red) in Connected Devices.
- **v7.12.7**: Settings can test the default agent with one sample question; the Ask agent window keeps a short history of answers; the log-entry and Hidden Settings windows have the progress bar and Stop too; Enable / Reinstall are green in Connected Devices.
- **v7.12.6**: Ask agent buttons answer with no agent and no API key (a built-in web lookup: only the search words leave the phone).
- **v7.12.5**: the first box is **Installed** (installed apps only); Installed + Bloatware (Uninstalled) together show every app on the phone. Whole rows are tinted: green running, blue frozen, red uninstalled.

- **v7.12.3**: the Apps tab boxes: **Frozen** blue, **Enabled** a more vivid green, **System** purple (also on the row badges).
- **v7.12.2**: **Clear Data** and **Remove updates** in the app menu run at once too, with no question (like Uninstall since v7.12.1).
- **v7.12.1**: **Uninstall** in the app menu uninstalls at once, without a question (Clear Data and Remove updates still ask).
- **v7.12.0**: Logcat keeps its lines (no more view that empties after two seconds), **Record to file** with a still-picture viewer, a **Default agent** (since v7.12.8 the **Default Ask Agent**) in Settings and **Ask agent** buttons on every hidden setting and the other places that need an explanation, and SD Maid's two data cards moved after its four tools.
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
