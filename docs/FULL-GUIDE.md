> The complete reference. The short version is the [README](../README.md).

<div align="center">

# ⚡ ADB Application Manager Pro

**Manage, debloat, update and inspect every app on your Android phone — straight from the phone.**

Freeze · Suspend · Uninstall · Debloat · Update · Install · Back up &amp; restore · Inspect manifests · Launch hidden
activities · Edit Android's hidden settings · Recolor Android and switch its overlays through **ADB over TCP, Wireless
Debugging, Shizuku or Root**.

[![Build APK](https://github.com/Bingblop/ADB-Application-Manager/actions/workflows/build.yml/badge.svg)](https://github.com/Bingblop/ADB-Application-Manager/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/Bingblop/ADB-Application-Manager?label=release)](https://github.com/Bingblop/ADB-Application-Manager/releases/latest)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)

### [⬇️ Download the latest APK](https://github.com/Bingblop/ADB-Application-Manager/releases/latest)

`com.bloatware.bingblop` · v7.15.0-Pro · signed APK, installs over every earlier version without uninstalling

<table>
  <tr>
    <td><img src="screenshots/apps-dark.png" width="210" alt="Application list"></td>
    <td><img src="screenshots/app-menu.png" width="210" alt="App menu"></td>
    <td><img src="screenshots/debloat-flow.gif" width="210" alt="Debloating in seven steps"></td>
  </tr>
  <tr>
    <td align="center"><sub>App list: versions, update hints, sorting</sub></td>
    <td align="center"><sub>App menu: sizes, dates, copy, share</sub></td>
    <td align="center"><sub>Debloat, review, log and undo</sub></td>
  </tr>
</table>

</div>

---

## Contents

[Download](#download--install) · [Features](#features) · [The tabs](#the-tabs) · [Screenshots](#screenshots) ·
[Terminal &amp; coding agents](#terminal-and-coding-agents) · [Working modes](#working-modes) · [Debloater](#debloater) · [Updates](#updates) · [Hidden settings](#hidden-settings) ·
[Overlays &amp; Material You](#overlays-and-material-you) · [App menu](#app-menu) · [Profiles](#app-profiles) · [Backup &amp; restore](#backup-and-restore) ·
[Quick tiles &amp; widget](#quick-settings-tiles-and-widget) · [Copy, share &amp; search](#copy-share-and-search) ·
[Permissions](#permissions) · [Themes](#themes) · [How it works](#how-it-works) · [Building](#building) · [Project layout](#project-layout) ·
[Testing](#testing) · [Privacy &amp; security](#privacy--security)

## Download &amp; install

1. Open the [**Releases** page](https://github.com/Bingblop/ADB-Application-Manager/releases/latest) and download
   the signed APK there (`-arm64-v8a` for 64-bit phones, `-armeabi-v7a` for 32-bit phones, `-universal` for both), together with that release's `SHA256SUMS.txt`. (Builds up to v5.1 are also kept in
   [`release/`](../release/); newer ones live only on the Releases page.)
2. Allow installing from your browser or file manager when Android asks, then open the APK.
3. Pick a [working mode](#working-modes). The easiest is **ADB over TCP**: run `adb tcpip 5555` once from a
   computer, or use Wireless Debugging (Android 11+) with the in-app pairing flow.

Check the download (put the APK and the `SHA256SUMS.txt` from the same release in one folder):

```bash
sha256sum -c SHA256SUMS.txt --ignore-missing
```

Updating: install the new APK over the old one. All releases are signed with the same key. The app can also
tell you when a new version is out (see [Updates](#updates)).

## Features

| | |
|---|---|
| **Apps** | Browse every package, including ones uninstalled for your user · search · sort by name, update date, install date, size or "updates first" · filters for running, 3rd party, system, frozen, suspended, uninstalled and **🧩 patched** (ReVanced, Morphe, Xposed / LSPosed modules, LSPatch / NPatch, debug-signed repackages) · **the big counters at the top light up for the filter you are looking at** · a clean row layout (name, package and badges flush left; checkbox, letter icon, gear and ⋯ together on the right) · versions and update hints in the list (versions are off by default; the Versions filter turns them on) · select many and run batch actions · save selections as named lists · export everything to CSV |
| **Actions** | **App menu (one app):** Launch · Force Stop · Freeze / Enable · **Suspend / Unsuspend** · Clear Data · Uninstall for user 0 (a system app that needs root falls back automatically to a direct Binder call, the same workaround App Manager and Canta use - see below) · Reinstall removed system apps · Remove Updates · App Info · **Extract APK**. **Batch (selected apps):** Freeze · Enable · Force Stop · Suspend · Unsuspend · Clear Data · Uninstall · Reinstall · Save to List · Copy Packages · **Backup** (each selected app in turn) · **Keep selection after running** (optional switch: run another batch command on the same apps without reselecting them) · batch actions, Dex optimization and applying a profile all run **off the page's thread with a live progress bar (app name and package, fill bar) and a Stop button**, so the page never freezes while they work |
| **Debloater** | The [UAD-NG](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation) community list (5,000+ packages) with descriptions and dependency warnings · filter by removal level, vendor list, state and **phone brand** · review step before anything runs · **history log with one-tap Undo** |
| **Installer** | All-in-one installer for `.apk`, `.apks` (bundletool), `.apkm` (APKMirror) and `.xapk` (with OBB / game data) · reads the package name, version, SDK range, size and signing certificate first · pick the authorizer (ADB / Shizuku / Root / none; in Read-Only mode only none, the system installer, is offered) and every `pm install` flag (grant all permissions, downgrade, test, all users, update ownership …) · an install through ADB / Shizuku / Root asks first in a dialog of the app · signature-mismatch and unknown-signature gates · optional dex optimization and auto-delete · **storage search** with a progress bar that lists every package file on the phone, where **press and hold deletes a file with Undo** · **the splits that fit the phone are ticked** (CPU, screen density, language) · **▾ lists of common installers and requesters** for the `-i` and `--originating-uri` boxes · **Launch Application / Application Settings** buttons once an install worked · optional **VirusTotal** check with your own API key (a SHA-256 lookup; nothing is uploaded unless you say so) · the app can be Android's handler for APK files |
| **Updates** | **Update the app itself** from its own GitHub releases (dedicated card, signed-key check, seamless with a privileged mode or the system installer without one) · **Galaxy Store** (Samsung system apps) · **GitHub, Codeberg, F-Droid, IzzyOnDroid and the Obtainium catalog** for sideloaded open-source apps · import your **Obtainium** export · Update one (the app asks after the download) or **Update All** (the app asks once for the whole list) · signing-key check before installing · Google Play apps hand off to Aurora Store or the Play Store page |
| **Inspector** | Permissions and App Ops as separate lists · **activities, services, broadcast receivers and content providers**, each with its exported / enabled / permission state, and any component can be **enabled or disabled** · unexported activities are **launchable through ADB / Shizuku / Root** · decoded **AndroidManifest.xml** viewer · version, install and update dates · APK, data and cache sizes |
| **Profiles** | Save which apps are disabled, suspended or uninstalled; re-apply on this phone or **share to another phone** · **👁️ Watch** a profile to be told when its apps come back after a system update |
| **Backup &amp; restore** | **💾 Backup** an app's APK (with splits), permissions and app ops in any privileged mode, plus its **data with Root** · restore through ADB / Shizuku / Root · share backups or pick one from another phone |
| **Quick actions** | **Quick Settings tiles** and a **home-screen widget** to switch the working mode and force-stop a list of apps without opening the app |
| **What's new** | The changelog is inside the app: it opens once after an update, and from About |
| **Productivity** | Select and copy any text · copy buttons for package, version and name · **share sheet** for package lists, CSV, manifest, terminal output and backups · **search with highlight and next/previous** in the manifest viewer and terminal · remembered filters and sort |
| **Terminal** | A **Termux-style terminal** with three persistent shells (this app's sandbox, the working mode, or **your own Termux** with bash and pkg packages), Termux's extra keys, colours, history and TAB completion · **Coding Agents**: Gemini, Claude, ChatGPT, Cursor, Copilot, Perplexity, Grok, Muse and Deepseek (API key, or your subscription through the providers' own CLIs in Termux) and free options (Jan.ai, AnythingLLM, Ollama on the phone, OpenCode) that **run commands and edit files with your OK at each step**, with a Model and an Effort to pick from · a **Shift** key for the symbols a phone keyboard buries, a larger input box and a **Cheat Sheet** that changes with the shell (bash for Termux, file and text basics for the sandbox, the ADB reference for the working mode) · **Sync with Termux** makes the in-app Termux shell match your real one (aliases and functions from `~/.bashrc` included) and grants Termux storage access in one tap · keys encrypted and sent only to their own provider · the ADB Shell of the Console tab can ask the same agent for syntax help too — see [Terminal and coding agents](#terminal-and-coding-agents) |
| **Console** (tab): **ADB Shell** | In the look of the Kotlin console (a two-tab row, one outlined command box, the accent Run button, a history of cards, newest first): run shell commands through the active mode (off the page's thread), with output search, copy and share · a **result line** under each command's output (not in Rish mode) · a **Copy** and a **Run again** button and a fold for long output on every card · **🕘 history, ⭐ saved scripts and pinned chips** · **🐚 Rish mode**: a persistent Shizuku shell where `cd` and `export` stick, with a STOP that ends a command and its children · a searchable **📋 ADB cheat sheet** of ~90 commands you tap to drop into the input |
| **Files** | Privileged file manager · **Add storage**: an SD card, USB drive or shared folder through Android's own document picker (no working mode or root needed) · **select many** and copy / move / delete together · open **.apk / .zip / .xapk / .jar … without extracting**, preview text, images and decoded Android XML, extract, rename, delete, add files and edit text in place · **install from inside an archive**, **open nested archives**, **compare two archives** · **sign an edited APK** on the device |
| **Logcat** | Readable, **color-coded** log (one row per entry, tappable level key) · **limit it to one app** · save or share the filtered log as a bug-report text file |
| **Hidden Settings** | Read and edit the phone's **Global**, **Secure** and **System** settings, one sub-tab per table · search names, values and descriptions · tap to edit, **press and hold to flip** a switch (1 / 0, true / false), **＋ to create** a setting · plain-English descriptions and ⚠️ warnings for the ones that bite · every change is read back to prove it, with **Undo** and a log of changes with **Revert** — see [Hidden settings](#hidden-settings) |
| **Extra Settings** | Where **Private DNS** is now: the **gear icon is not the place** (it only has a pointer). 15 services as tiles (Google, Cloudflare, Quad9, AdGuard, OpenDNS, CleanBrowsing, NextDNS, DNS.SB) · **Test speed** shows connect, TLS and answer times and marks the fastest · see [Extra Settings: Private DNS](#extra-settings-private-dns) · **Animation speed** (the three Developer-options scales with a live preview dot each), **Brightness bias** (adaptive brightness a little darker or brighter, `screen_auto_brightness_adj`) and **Device telemetry** (RAM, storage and battery rings, model, Android / One UI, kernel, uptime, temperature, swap, a Copy report) · see [Extra Settings: Animation speed, Brightness bias and Device telemetry](#extra-settings-animation-speed-brightness-bias-and-device-telemetry) |
| **Overlays** | **New in v6.0:** change Android's **Material You** theme from the phone — the **wallpaper** or **any color** (hex, sliders or 657 named presets) with one of six **styles** (Tonal Spot … Spritz), and the palette Android is really using shown afterwards · list every **overlay** (`cmd overlay list`) grouped by the app it restyles, search and filter it, **switch one on or off** with its switch or by **pressing and holding** its row · every change is read back to prove it, with **Undo** — see [Overlays and Material You](#overlays-and-material-you) |
| **App Stores** | **ShizuStore**, **GitHub** (up to 5,000 apps, live search), **F-Droid** (any known repository, streamed) and **Orion** as sub-tabs, each with a category drop-down · every install comes from the app's own upstream (nothing is rehosted), through your active mode (after the app's own question) or the system installer · a successful install offers **Launch Application** and **Application Settings**, the same as the APK Installer/Updater |
| **Modes** | ADB over TCP · Wireless Debugging (pairing, mDNS port detection, and **🔔 pairing from a notification** so the code can't expire while you switch apps) · Shizuku · Root · Automatic · Read-Only |
| **Themes** | **Material 3** (default) · **Material You** (follows your wallpaper) · six more palettes · **ten palette styles** (Tonal Spot, Vibrant, Fidelity, Content, Neutral, Expressive, Fruit Salad, Rainbow, Monotone, CMF) built from a source color you pick · **My Themes** (save a look under a name, switch with a tap) · Light / Dark / System / Schedule · pure-black AMOLED option · per-mode color tuning · **Expressive Animations** (Material 3 Expressive's springier motion across the whole app, on by default, with its own switch under Settings → Motion) |
| **Security** | A **private ADB key is generated on each install** (nothing is bundled) · fingerprint shown in the app · signing-certificate comparison before every update |
| **Navigation** | A Back button that closes the open sheet, clears a selection, steps up a folder or out of an archive, returns to the previous tab, and only then asks for a deliberate second press to leave (it also asks first while an install, update, file job or command is running) |
| **Permissions** | A first-launch sheet offers **All files access**, **Usage access** and **Display over other apps** (each optional, also under About); with a working mode it also offers **Read/Write External Storage**, **Write Secure Settings** and **Access Restricted Settings**, granted straight through the shell. An action that fails for want of file access asks for it on the spot and carries on once it is allowed — see [Permissions](#permissions) |
| **About** | The developer and the GitHub repo, this build's version, device and **signing certificate** (✅ for the official release key), a **Self-test** card (27 read-only checks in the current working mode, with a copyable report and a pre-filled GitHub issue), a **Device Specs** card (hardware, software, system, battery, network, camera and sensors, refreshed when the tab opens), copyable debug info for bug reports, the **🔐 Permissions** sheet, and an optional **☕ Buy me a coffee** (PayPal, $1 or any amount) |

## The tabs

Left to right, with the **settings gear** (it opens Settings: language, appearance, colors, font and the Feature List) in the header:

| Tab | For |
|---|---|
| **Application Manager** | Every package on the phone: search (with a menu for names, package names and regex), sort, filter, batch actions, profiles, backups, CSV export; **⋯** opens an app's menu, the gear next to it the app's Android settings |
| **Saved Applications** | Named groups of apps to freeze, enable, stop or share together, and the **quick list** behind the tile and widget |
| **UAD-NG Debloater** | The UAD-NG list for your phone, a review step before anything runs, history with Undo |
| **APK Installer/Updater** | Tab-bar label *Installer/ Updater*. Two boxes at the top: **Application Installer** (the one it opens on: install `.apk` / `.apks` / `.apkm` / `.xapk` with full control of the options, plus the optional VirusTotal check) and **Application Updater** (find and download an app by name or package, this app's own update, Galaxy Store apps and sideloaded open-source apps from GitHub, Codeberg, F-Droid, IzzyOnDroid and Obtainium, the Play Store card, and the KeyStore card) |
| **File Manager** | A privileged file manager that also opens packages and archives without extracting them, and can sign an edited APK |
| **Console** | Two parts, **ADB Shell** (a shell through the active mode, with a history of cards, saved scripts, a cheat sheet and a persistent Rish shell) and **Live Logcat** (the device log, color-coded, live, limited to one app, recorded to a file, saved or shared). It remembers the part you used last, and the log is read only while Live Logcat is on screen |
| **Terminal** | A Termux-style terminal with coding agents ([details](#terminal-and-coding-agents)), and a **Real terminal** button for a full-screen terminal on a genuine pty (vim, nano, top, htop, ssh). Termux setup has a one-tap **Set up Termux now** (vim, nano, git, python and more, with a starter .bashrc). This is the tab that was called Command-Line Interface until v7.12.12 |
| **Hidden Settings** | Read, flip, edit and create Android's own Global, Secure and System settings ([details](#hidden-settings)) |
| **Extra Settings** | Ready-made controls Android hides, right after Hidden Settings: **Private DNS** with a **speed test** of every service ([details](#extra-settings-private-dns)), **Animation speed**, **Brightness bias** and **Device telemetry** ([details](#extra-settings-animation-speed-brightness-bias-and-device-telemetry)) |
| **RRO/Monet Customization** | Recolor Android (Material You: wallpaper or any color, six styles) and switch system overlays on or off ([details](#overlays-and-material-you)) |
| **App Stores** | The catalogs only: ShizuStore, GitHub, F-Droid and Orion, and a **+** tab for stores of your own |
| **Task Manager** | Processes, CPU, RAM, GPU, battery and network, live, each with a graph ([details](#new-in-v75)) |
| **Connected Devices** | Another Android device (a Wear OS watch first) over adb: add by pairing code, address, network scan or Bluetooth link; its apps (enable, freeze, uninstall, reinstall, pull), send APK / APKS / APKM / XAPK, console, logcat, files, hidden settings, screen density and size |
| **Morphe Patcher** | Morphe patching on the phone: patch sources and the community finder, apps marked **Installed**, Morphe Helper (ten APK download sources, an in-app browser for sites with a browser check, optional VirusTotal), Simple and Advanced patching, install when finished, live log, Patched APKs |
| **SD Maid** | SystemCleaner, AppCleaner, CorpseFinder and Deduplicator ported from SD Maid SE: scan, review and untick, delete, optional 1-tap scan and delete, exclusions, history, accessibility cache clearing |
| **Root Features** | The features that need Super User (root), right before About: the state of root for this app (ACTIVE, AVAILABLE, DENIED, NOT FOUND), **Request Super User** and **Open root manager** on a rooted phone (KernelSU, KernelSU Next, APatch or Magisk; the grant is approved in the manager), **Super User for apps** (its **Open manager** opens the installed manager the way its home-screen icon does, not on its list of apps with root: you go to that list in the manager) and **App components** (enable or disable single parts of an app, which Android allows to root and refuses to the shell user). Locked but never hidden without root; this app roots nothing |
| **About** | Who made it, which build and key you have, debug info, the Permissions sheet, and the coffee button |

Every tab except Application Manager and About can be switched off or moved in **Settings → Feature List**.

## New in v7.9

**v7.15.0 is the latest build** (a top-to-bottom UI pass and a new name, ADB/Root Full System Toolbox: the header is the name in one colour with only the Android and One UI versions under it, every tab is calmer, and the things used most are closer: Running, Frozen and Uninstalled on a hold of the Apps tab, Search files, Storage and Downloads on a hold of Files, Select shown next to Clear filters, the Debloater's Update List and History beside its search, the path in Files as tappable crumbs; nothing the app does changed; `docs/PHONE-SCRIPT-v7.15.0.md` is the phone script; before it, v7.14.1: Stop apps shows its progress in a notification with a Cancel button, a Live Update on Android 16 and newer, and never stops this app, the WebView app or Media Picker; a Live updates row in the permissions sheet; a Speed test section in Extra Settings; a Refresh button in the File Manager; Installed | Uninstalled as one choice; a Gradient switch and a shortcut for Row tints; the Updated 7d filter is gone, sort by Last updated instead; and 57 fixes from a full review: Replace onto an SD card or USB drive merges folders, the full-screen terminal in Shizuku mode no longer closes the app, SD Maid keeps what its exclusions protect, a failed install keeps its files; `docs/PHONE-SCRIPT-v7.14.1.md` is the phone script; before it, v7.14.0 was a feature release: the app is now called ADB System Manager; a speed test in Task Manager, the ADB Stub for Wear OS watches, a Self-test in About, a history of the last 100 Hidden Settings changes, permissions granted by command, ANGLE, Live Logcat as its own tab, Connected Devices lists like the Apps tab, filled gradient buttons, five-step Row tints, folding Settings cards; `docs/PHONE-SCRIPT-v7.14.0.md` is the phone script; before it, v7.13.4 moved Backup from the app menu to the batch menu, where it backs up the selected apps one after another; `docs/PHONE-SCRIPT-v7.13.4.md` is the phone script; before it, v7.13.3 was a screen-layout release: the Apps tab list starts near the top and the app icon is its checkbox, the File Manager options moved under ⋯ More, readable small text and badge contrast, one look for empty lists, 44 px tap areas, nothing else changes; `docs/PHONE-SCRIPT-v7.13.3.md` is the one-page phone script; before it, v7.13.2 was a fix release: the SD Maid accessibility cleaner starts on its own and never force-stops this app or Android System WebView, and the serious findings of a second review pass: file pickers, the device a Connected Devices action runs on, the real terminal while it starts, Morphe patches across an app reopen, the update count; section 29 of the device checklist is one phone step per fix; before it, v7.13.1 had fifty-four fixes from the code review of 2026-10-13 and nothing new: section 28 of the device checklist is one phone step per fix; before it, v7.13.0 added the Extra Settings tab: Private DNS with a speed test, animation speed, brightness bias, device telemetry, power & reboot, Doze & power governor, zram & memory; the bottom navigation bar with long-press shortcuts; the Console tab with the Terminal as its own tab; the rebuilt Updater check; an Install button on every ShizuStore row; the DUMP row; before it, v7.12.11 added a Private DNS card in Settings, a result line in the ADB Console, the app's own question before an install from the Installer, the Updater (and Update All), a restore or the Morphe Patcher (not before updating the app itself), and no privileged install in Read-Only mode, readable text on the App Stores buttons; before it, v7.12.10 asked before installing a downloaded app and fixed archive and Morphe Patcher problems, and v7.12.9 was a security and stability release and v7.12.8 added the APK Installer/Updater; also Connected Devices, 32-bit and universal APKs, the Help Guide: see [CHANGELOG.md](../CHANGELOG.md)). Everything from v7.9 through v7.9.15 is below; the full, dated list is in
[CHANGELOG.md](../CHANGELOG.md) (and inside the app, under About).

**Newest (v7.9.12 to v7.9.15)**

- **Expressive Animations** (v7.9.15). A new **Motion** card in Settings, right below the theme cards, with one switch that is **on by
  default**: Material 3 Expressive's springier, overshooting motion for the app's own transitions (sheets, modals, buttons,
  switches) instead of a plain fade or slide. The width-based progress bars keep a plain ease so they never visibly pass 100%, and
  the phone's own reduced-motion setting always wins over the switch.
- **Sync with Termux** (v7.9.14). The Terminal's Termux shell can now match your real Termux: a **Match my Termux environment**
  switch (on by default) also sources `~/.bashrc`, so your aliases, functions and PATH additions work here too, and a **Sync with
  Termux** button (Terminal settings, and the Termux setup checklist) turns that on, restarts a running Termux session so it applies
  at once, and opens Termux to run `termux-setup-storage` - storage access in one tap instead of a typed command.
- **Saved Applications** (v7.9.13). A saved list's name and app count get the whole header; the "Saved" tag sits above its own
  Recall / Quick list / Edit / Copy / Delete row.
- **A cleaner Apps row** (v7.9.12). Name, package and badges start flush left; the checkbox, letter icon, gear and ⋯ buttons sit
  together on the right.

**Earlier in v7.9.x**

- **Coding Agents can be allowed to change files** (v7.9.11): the write / edit card gets the same **Always allow in this chat** button
  the run card had, independent of the run and read-outside-the-folder approvals.
- **No "Shell Command Result" popup** after every Terminal command (v7.9.10): it always claimed success and repeated the output
  printed right above it.
- **No more freezes** (v7.9.5 to v7.9.9): batch actions, **Dex optimization** and **applying a Profile** run off the page's thread with
  a live progress bar and a **Stop** button; a routine, successful single-app action shows a toast instead of a toast *and* a dialog
  (a failure still gets the dialog); more result dialogs read the real exit status instead of scanning for failure words.
- **File Manager: Add storage** (v7.9.8): an SD card, USB drive or a folder another app shares, through Android's document-tree picker.
  Browse, create, rename, delete and edit text in place work; copy, move, compress, Open with and Share are not there yet for this kind
  of storage and say so.
- **About: Device Specs** (v7.9.7): hardware, software, system, battery, network, camera and sensors in one card, no working mode needed.
- **Terminal and Logcat** (v7.9.6): a larger input box, a **Shift** key row and a **Cheat Sheet** per shell; Logcat starts playing by
  itself; version numbers are off by default in the app list.
- **Keep selection after running** (v7.9.3), **systemless uninstall** for system apps through a direct Binder call (v7.9.2, fixed in
  v7.9.4), real exit statuses for freeze / suspend / stop / clear / uninstall results (v7.9.2) and **Launch Application** after a store
  install (v7.9.1).

**v7.9**

- **The tab is now "Command-Line Interface"** (same Terminal-first switch, same ADB Console next to it as v7.8 - only the
  tab's own name changed).
- **Grok (SpaceX), Muse (Meta) and Deepseek** join the API-key agents; **Kilo Code** joins the free/open-source list,
  explained rather than wired up to chat (no confirmed phone-terminal CLI for it, the same treatment as DroidMind/Leon.ai).
- **Effort**, next to Model: Low / Balanced / High, defaulting to Balanced. Actually changes the request for Claude, ChatGPT,
  Gemini and Grok (their own reasoning/thinking knob); kept but inert for the others.
- **The ADB Console can ask an agent too**: a **$ / AI** button next to its input asks the same agent/model/effort picked in
  the Terminal for syntax/code help, with at most one proposed command per answer, Run / Skip through the console's own
  execution path. Details: [Terminal and coding agents](#terminal-and-coding-agents).

## New in v7.8

- **Terminal / ADB Console.** The ADB Console tab now opens on a **Termux-style Terminal** (a big switch at the top goes back to
  the classic ADB Console; the tab remembers your choice). Three persistent shells, each on its own screen: **this app's
  sandbox**, the **working mode** (shell user or root) and **your own Termux** (bash and every pkg package).
- **Coding Agents** in the Terminal: **None**, then Gemini, Claude, ChatGPT, Cursor, Copilot and Perplexity (API key, or your
  subscription through each provider's official command-line tool in Termux, where one exists), and free / open-source options (Jan.ai, AnythingLLM,
  **Ollama on the phone**, OpenCode, plus DroidMind and Leon.ai explained). Keys are **tested before use**, kept **encrypted**,
  and only ever sent to their own provider. The agent runs commands, reads, writes and edits files **with your OK at each
  step**; switch agent or model mid-chat and the conversation carries on. Details: [Terminal and coding agents](#terminal-and-coding-agents).

## New in v7.5

- **A Task Manager tab**, after Logcat Viewer: **Processes** (sortable by CPU or memory, **✕** force-stops with no confirmation),
  **CPU**, **RAM**, **GPU**, **Battery** and **Network**, each with a live graph. CPU, RAM, Network and Battery work with **no
  working mode at all** (world-readable `/proc` files and `BatteryManager`); Processes and GPU need one. **Auto-refresh** (how
  often a fresh reading is fetched) and **Graph speed** (how smoothly it is redrawn) are independent settings, remembered
  between visits, along with the chosen units and sort.

## New in v7.4

- **7z, rar and the tar family** (`.tar`, `.tar.gz/bz2/xz/zst/lz4`, and a bare `.gz/bz2/xz/zst/lz4`) open, browse and extract like a zip
  everywhere the file manager already does it (the archive browser, **Extract…**, search's `archive:`, Find APKs' duplicate check); a
  solid or compressed one says so and extracts in a single pass. 7z and the tar family can be edited in place too; rar is read only.
- **Passwords:** a dialog asks when an archive or an entry needs one (a wrong one says so and asks again), kept only in memory, only for
  that open archive. Zip has ZipCrypto and WinZip AES-128/192/256; 7z has its own AES-256 (names included); rar's passwords are for
  reading only.
- **A Compress dialog:** make a zip, 7z, tar format, or a single compressed file, from a file's sheet or a selection — a name, a packing
  level, where to save, and what to do if that name is taken. **A password is optional.**
- **Code and markup in colour** by default in the editor, the plain viewer and an archive's text preview (the usual languages and
  markup formats); **word wrap is on by default**; both are switches next to each other, remembered for next time.

## New in v7.3

- **Search in the file manager:** a bar with a **where to search** drop-down (this folder, storage, Downloads, photos, an SD card, the whole phone)
  and two boxes, **Include subfolders** and **Search inside archives**. It understands names with wildcards, `ext:`, `type:`, `size:`, `date:`,
  `content:` (inside text files) and `archive:` (the names of entries inside zip-family archives). Progress, Stop, and a result list you can open
  from or show in the folder.
- **An extract dialog:** into this folder, a new folder named after the archive, or another folder; **Replace / Skip / Keep both** for a name that is
  already there; and **Delete the archive afterwards** (only when every file was written).
- **Long jobs keep going:** copy, move, delete and extract show percent, speed and time left, run in a foreground service (they continue when you leave
  the app, with a notification and **Cancel**), and say so when nothing has moved for 20 seconds.
- **Find APKs:** files that are a second copy of another, or an older version of a package that is there in a newer one, are flagged; **Select**, then
  **Delete selected** with **one Undo** for the whole batch; and after a successful install the result sheet offers to delete the installer file.
- **Tests on every pull request** (`.github/workflows/tests.yml`). 7z / rar / tar formats and passwords follow in the next release.

## New in v7.2

- **File manager:** a **".." row** at the top of every list (go up one folder), **＋ File**, **Show hidden files**, cached
  **thumbnails** of images and videos, a **Replace / Skip / Keep both** sheet when a name is taken, **Cancel** for a running job,
  **Open with…** and **Share** on any file, a **text editor** (**Edit**), and viewers for **pictures, PDF pages and fonts**. Copy, move,
  delete, rename and create work with All-files access alone, with no working mode. Archives beyond zip, search and the extract
  dialog follow in the next releases.

## New in v7.1

- **A language setting.** **Settings → Language**, at the very top: English (the default) and thirteen more, each named in its own
  letters (Español, Français, Deutsch, Português (Brasil), Italiano, Русский, 简体中文, 日本語, 한국어, العربية, हिन्दी, Türkçe, Bahasa
  Indonesia). It changes the text at once, with no restart, and is kept between launches. Names of apps, files, packages and
  settings, paths and command or log output are never translated; dates and numbers follow the language; Arabic runs right to
  left. Notifications, the quick tiles and the widget are still English. For translators: [`tests/i18n`](../tests/i18n/README.md).
- **A font for the app.** **Settings → Font**: the system font, or a `.ttf` / `.otf` of your own, for the text of this app only.
  *Find fonts on this phone* searches storage with a progress bar and lists the fonts by their real names; *Choose a file…* needs no
  storage access. A sheet shows the font before you use it.
- **Saved App Lists is now Saved Applications**, and the app icon is the lightning bolt of the header.

## New in v7.0

- **New tab names and order, on two lines.** Application Manager · Saved Applications · UAD-NG Debloater · APK Installer · File
  Manager · ADB Console · Hidden Settings · RRO/Monet Customization · App Updater · App Stores · Logcat Viewer · About. See
  [The tabs](#the-tabs).
- **A settings gear** replaces the colors button. It opens **Settings**: Appearance, the theme palettes and color pickers, and the
  new **Feature List**.
- **Feature List**: a switch and two arrows for every tab except Application Manager and About. Switch a tab off and it leaves
  the tab bar; move it up or down and the bar follows; **Reset to Default** puts everything back. Kept between launches.
- **No emoji.** Only the two settings gears are left (the header's and the one on every app). The text in tabs and filters is a
  little larger, and buttons that were only a picture now have a word (Copy, Share, History, Saved, Run, Pin, Edit, Delete,
  Refresh, Install).
- **A search bar under the filters, with a menu.** Three dots at its right end open **Include application names**, **Include
  package names** and **Use regex matching** (off means exact matches only). All on at the start, kept between launches,
  and one of names and packages always stays on. A pattern that could freeze the list is searched as plain text.
- **The Aurora sub-tab of App Stores is gone** (the Play Store card on the Updates tab still opens Aurora Store).

## New in v6.1

- **🔐 Permissions on first launch, and when an action needs one.** A sheet offers **All files access**, **Usage access** and
  **Display over other apps** the first time the app opens; each one is optional and they are also under **About → 🔐
  Permissions**. When something fails for want of file access, a sheet asks for it on the spot and the action carries on by
  itself once it is allowed. See [Permissions](#permissions).
- **🚀 Launch Application / ⚙️ Application Settings** after an install: two buttons above **Done** in the Installer's result
  dialog, which is taller to make room (the output box keeps its height).
- **⬇️ The Installer scrolls to the package** after you pick one, also from the list of found files.
- **📊 A progress bar for Find APKs**, and **🗑️ press and hold a found file to delete it from the phone**, with an **Undo**
  bar and a tip that says so.
- **🧩 The splits that fit the phone are ticked** (base, CPU, screen density, language); the rest stay off with a note each.
  **Select all splits by default** is off unless you turn it on.
- **▾ Common installers and requesters** for the installer source (`-i`) and requester (`--originating-uri`) boxes: Google
  Play, F-Droid, Aurora Store, Amazon Appstore, Samsung Galaxy Store, Huawei AppGallery, APKMirror, Obtainium, itch.io and more.
- **🎨 / ✨ / 💡** A bigger theme button, big counters that light up for the filter in use, and a tip under Export and Share
  CSV that says the filters scroll sideways.

## New in v6.0.4

- **🚀 A launch that works shows no dialog.** In an app's Components tab, **Launch** just opens the activity (a toast says it went
  through); the sheet with Android's answer opens only when the launch fails.

## New in v6.0.3

- **⬆️ A taller app menu.** The sheet that opens from **⋯** on an app now stands 93% of the screen high instead of 85%, so the
  lists under its buttons (Permissions, App Ops, Components, Manifest) get more room: 64 px more on a 360 × 800 screen. The
  other sheets keep their height, and the strip above it still closes it when you tap it.

## New in v6.0.2

- **📤 No more Share APK in the app menu.** The button is gone from the ⋯ menu of an app. **Extract APK** stays and still saves
  the app's `.apk` (an `.apks` bundle for split apps) to Downloads and tells you where; nothing else in the menu changed.

## New in v6.0.1

- **🛠️ "Settings" is now "Hidden Settings".** The tab that reads and edits Android's own settings tables (Global, Secure
  and System) was called *Settings*, which sounds like this app's own settings. It now says what it is, with a 🛠️ instead
  of the gear. Nothing else about it changed, and what you had saved (the log of changes, the table and filter you last
  used) is still there. See [Hidden settings](#hidden-settings).

## New in v6.0

- **🌈 An Overlays tab** — right after Hidden Settings, with two halves. **Theme** changes the source color and style Android's
  Material You engine builds every system color from: the wallpaper (Android's default) or **any color** you pick with
  a hex box, hue / saturation / lightness sliders or **657 named presets**, in one of six styles (Tonal Spot, Vibrant,
  Expressive, Fruit Salad, Rainbow, Spritz). **Overlays** lists every overlay `cmd overlay list` reports, grouped by the app it
  restyles, with a switch on each row: **tap the switch or press and hold the row** to switch it on or off, or tap the row
  for its sheet. See [Overlays and Material You](#overlays-and-material-you).
- **🎨 Colors in use** — the five tonal palettes Android is using right now (Accent 1–3, Neutral 1–2) are read from the system
  and drawn as swatches you can tap to copy; they redraw by themselves a moment after a change, once Android has repainted.
- **↩️ Undo, everywhere** — a theme or overlay change reads itself back, offers **Undo**, and a theme change is also listed in
  Hidden Settings → ⋯ → Changes with a **Revert**. Android restarts apps when the palette changes; the app picks up where it left
  off and the Undo bar is still there.
- **🔍 Checked before it shipped** — three independent review passes over the new tab (the native side, the page, and a second look at the logic the first fixes added: 26 findings, 25 fixed and one left on purpose, see Testing).

## New in v5.9

- **🛠️ A Hidden Settings tab** (called *Settings* when it arrived) — read and edit Android's hidden **Global**, **Secure** and **System** settings: a sub-tab per table, search and filters, **tap to edit**, **press and hold to flip** a 1 / 0 or true / false switch, and **＋ to create** your own. Every change is read back to prove it worked and can be undone. See [Hidden settings](#hidden-settings).
- **🔍 Checked, then hardened** — two independent reviews of the new tab led to a stricter verdict on every change (Android's
  own refusal wins over a read-back that merely says `null`), lists that are only accepted when they arrive complete, and
  a clear "may still be applied" when the link drops.

## New in v5.8

- **↩️ A smarter Back button** — Back closes the open sheet, clears a selection, steps up a folder or out of an archive, goes to the previous tab, and only then warns. Leaving takes a deliberate second press (a double tap or a late one just warns again) and asks first while an install, update, file job or command is running.
- **📁 Installs from Files always run privileged** — a file row, an APK inside an archive or a freshly signed copy installs through ADB, Wireless Debugging, Shizuku or Root, never the system installer, with this app's own signature gates off. Android still checks signatures itself: an APK it refuses is signed with this app's key and installed, and an app signed by someone else is replaced only after you agree to uninstall it.
- **ℹ️ About tab** — the developer (Bingblop), the GitHub repo, this build's version / device / signing certificate (✅ for the official release key), copyable debug info, tips, privacy, credits, and **☕ Buy me a coffee**: $1 or any amount through PayPal.
- **✍️ Sign the APKs you edit** — edit an APK in the archive browser, tap **Sign**, and it is signed on the device (APK Signature Scheme v2) with a key kept in the Android Keystore. Sign in place or save a `-signed` copy, see who signed what and whether it can update the installed app, and install it right away.
- **📦 Archive browser: install, nest, compare** — install an APK from inside an archive, open archives inside archives, and **compare** two archives (or an archive and an installed app) with a line-by-line diff of changed text and compiled XML. Many archive-engine fixes: AES zips survive edits, ≥4 GB entries are refused instead of truncated, extraction is checksum-verified, CRX / self-extractors open, odd names are listed.
- **🕘 Terminal history, saved scripts and pinned chips** — Up/Down and a history list, named commands and multi-line scripts, up to six pinned as one-tap buttons; works in normal and Rish mode. The terminal no longer freezes the page while a command runs.
- **🐚 A sturdier Rish shell** — mistakes that make a shell quit are survived (it restarts in the same folder), **STOP** is Ctrl-C first and reaches the whole process tree, closing leaves nothing behind, and a runaway background job can't flood the screen.
- **🎯 Logcat for one app + save / share** — pick an app (or tap **Logs** in its menu) to see only its lines, then save or share the filtered log as a bug-report file.
- **☑ File manager: select many** — press and hold (or ☑ Select), then copy, move (via **Paste here**) or delete many files at once; conflicts ask first, failures are listed, system folders are protected.
- **🛡️ Security hardening** — escaped app labels / file names / store fields, no `debuggable` release, backups off, a locked-down WebView.
- **⚡ Faster** — the app list is drawn a page at a time, big-list search waits for a pause, logcat draws the latest 800 entries and polls off the page's thread, and the mode probe is cached.

## New in v5.7

- **🐚 Rish mode in the terminal** (replaces the "adb devices" button) — one tap switches the working mode to **Shizuku** and opens a **persistent Rish shell**: `cd`/`export`/variables persist, output streams live, **STOP** ends a running command, and typing `exit` (or tapping **Exit Rish**) returns to the normal terminal.
- **📂 Look inside packages without extracting** — in the file manager, **View** on an `.apk`, `.zip`, `.xapk`, `.jar` … opens its contents as folders. Preview text, images, compiled Android XML (decoded back to XML) and binaries (hex); **extract** a file or folder, **rename / move**, **delete**, **add** files or folders, and **edit text in place**. Edits rewrite the archive safely (raw-copy of untouched entries, APK alignment kept, ZIP64 and path-traversal safe); installed and system packages are view-only, and an edited APK needs re-signing before it will install.
- **Installer: XAPK + storage search** — install `.xapk` bundles (with OBB / game-data copy); the *Set as default installer* button is replaced by an automatic **storage search** that lists every `.apk` / `.apks` / `.apkm` / `.xapk` on the device (the how-to-make-it-the-default text stays at the bottom).
- **Store: GitHub, full catalogs, categories** — Komi is now **🐙 GitHub**; GitHub (up to 5,000 apps + live search), **F-Droid** (every app in the chosen repo, streamed) and **Orion** (all 887 apps) now list their whole catalogs, and each store has a **category drop-down**.
- **Readable logcat** — one row per entry, **color-coded Verbose / Debug / Info / Warn / Error / Fatal** with a tappable color key.
- **Selection polish** — Debloater checkmarks fill like the Applications tab, **press-and-hold the ✓ button to clear all selections**, and the per-row Force Stop button is gone to give names more room (Force Stop stays in the ⋯ menu and batch actions).

## New in v5.6

- **The Store is five sources in sub-tabs** — the 🛍️ **Store** tab opens on **ShizuStore** and adds four sub-tabs:
  - **🐙 Komi** — a curated set of trusted open-source apps published on **GitHub Releases** (the kind of GitHub app store [komi-store](https://github.com/komi-store/komi-store) is built for); each is resolved live to the right build for your device.
  - **🤖 F-Droid** — the known third-party **F-Droid repositories** from the community [known-repositories](https://forum.f-droid.org/t/known-repositories/721) list; tap a repo to browse and install its apps, or copy its address & fingerprint to add it to your F-Droid client.
  - **🪐 Orion** — the public [Orion Store](https://github.com/RookieEnough/Orion-Store) catalog (including Morphe builds), resolved from GitHub, Codeberg and direct links.
  - **🌌 Aurora** — an honest hand-off to **Aurora Store** for Google Play apps (the private Play API can't be reimplemented in-app). Google Play and APKMirror sources are intentionally not bundled.

  Every install downloads from each app's own upstream — nothing is rehosted — through your active mode (after the app's own question, "Install this app?") or the system installer.
- **Sharper patched-app detection** — the 🧩 detector now flags **Morphe** (`app.morphe.*` and the Morphe installer), and the inspector's deep scan streams each `classes*.dex` to recognise **ReVanced** and **Morphe** even when the patch keeps the app's original package name.

## New in v5.5

- **Unexported activities launch reliably** — opening an activity another app doesn't export no longer fails with *"not exported from uid …"*. The app launches it the system's way (briefly sets it as the device assistant and injects `KEYCODE_ASSIST`, so the system starts it past the exported check, then restores your assistant), the same technique dedicated activity launchers use over Shizuku. Exported activities still start directly with `am start`.

## New in v5.4

- **Patched-app detection** — the app list flags apps modified by third-party tools with a 🧩 badge: **ReVanced**, **Xposed / LSPosed modules**, **LSPatch**, **NPatch**, and **debug-signed / repackaged** builds. A **🧩 Patched** filter shows only those. The inspector runs a deeper scan (signing certificate + APK markers like `assets/lspatch/`, `assets/xposed_init`, `META-INF/xposed/`) and names the tool and installer, so you can tell a genuine build from a modified one.

## New in v5.3

- **ShizuStore tab** — a 🛍️ **Store** tab browses [ShizuStore](https://github.com/timschneeb/ShizuStore)'s curated catalog of Shizuku apps. Search and sort (most starred / downloaded / recently updated / added), open an app for its description, screenshots, stars, permissions and source, then **Install** pulls the APK from the developer's own upstream (GitHub / GitLab / F-Droid) and installs it through your active mode or the system installer. APKs are served by each developer, not rehosted.
- **Optional VirusTotal scan** — the Installer tab can check a package against 70+ antivirus engines before you install, using your own VirusTotal API key. It's a SHA-256 lookup (nothing is uploaded); if the file is unknown you can opt to upload it. Shows malicious / suspicious / harmless counts, a verdict and a report link.
- **No icon background** — the adaptive launcher icon is now fully transparent behind the mark, taking your launcher's own shape.
- **Material You by default** — fresh installs start on the dynamic wallpaper-based theme (Material 3 baseline on Android 11 and older); existing themes are untouched.

## New in v5.2

- **Pair over Wi-Fi from a notification** — the Wireless Debugging card has a 🔔 **Pair via Notification** button. It posts a notification with an inline reply box, so you can type the 6-digit pairing code straight from the shade while Android's *Pair device with pairing code* dialog is open — no switching back to the app, so the code can't rotate out from under you. It finds the pairing port over mDNS automatically (or reply with `port code`, e.g. `37123 123456`), and pairs in the background without the app needing to be open.

## New in v5.1

- **ADB cheat sheet** — the ADB Console has a 📋 **Cheat Sheet** button: a searchable, categorized reference of ~90 commands (device info, packages, app control, permissions, intents, input & key events, screen, connectivity, battery testing, logs, files, reboot). Tap a command to drop it into the input (placeholders selected for quick editing). Commands are in on-device shell form — no `adb shell` prefix. The cheat sheet can also load the **full reference live from [Pulimet's gist](https://gist.github.com/Pulimet/5013acf2cd5b28e55036c82c91bd56d8)**. The terminal input also defaults to lower case (no auto-capitalize).
- **Cleaner launcher icon** — the adaptive app icon drops its black background for a clean white one.
- **First-launch permissions** — on first launch the app checks and requests the runtime permissions it uses (notifications, and legacy storage pre-Android 11); special-access grants stay contextual.

## New in v5.0

- **Self-update** — a dedicated **🚀 App update** card at the top of the Updates tab updates ADB Application Manager Pro itself from its GitHub releases: installed vs. latest version with release notes, and a one-tap update. The download is verified to be this app, a newer version and **signed with the same key** before installing — seamlessly through ADB / Shizuku / Root, or via the system installer with no privileged mode.
- **Reliable unexported-activity launch** — launching an activity through ADB / Shizuku / Root starts it in a new task (`FLAG_ACTIVITY_NEW_TASK`), with fall-backs to a plain start and `cmd activity`, so unexported activities actually surface instead of silently going nowhere.
- **Themes in the header** — a 🎨 **Colors & Themes** button in the top-right opens themes from anywhere; tabs are reordered (Applications → Saved Lists → Debloater → Installer → Files → Updates → ADB Console → Logcat).

## New in v4.9

- **All-in-one installer** — install `.apk`, `.apks` (bundletool) and `.apkm` (APKMirror) packages. Reads the package name, version, min/target SDK, size and signing certificate before installing; for split bundles you pick which splits to install. Full `pm install` control: grant all permissions, allow downgrade, allow test, all users, bypass low target-SDK block, request update ownership, install reason, package source, installer package. Pick the authorizer (ADB / Shizuku / Root / No-privilege), optional post-install dex optimization and auto-delete, and signature-mismatch / unknown-signature gates.
- **Default installer** — choose the app as the handler for APK files in Android's *Open with → Always* prompt (the Installer tab explains how); opening an APK then routes into the installer (privileged install when a mode is active, otherwise the normal system installer).
- **Components** — now lists activities, **receivers**, **services** and **providers**, each with its exported/enabled/permission detail, and any component can be **enabled or disabled**.
- **Dex optimization** — `pm compile` as a single-app and batch action, with a compile-mode picker.
- **Privileged file manager** — browse storage and any system path. With **All-files access** granted, storage (`/storage/emulated/0`) lists, views and installs directly — no privileged mode needed; ADB / Shizuku / Root reach system paths. View text, create, rename, copy, move, delete, and install APKs from anywhere.
- **Logcat reader** — read the device log with level, line-count and text filters, with **live Play / Pause** tailing, clear and copy.
- **Play Store (via Aurora)** — detects Aurora Store / Play Store and routes app updates to their store page.

## Screenshots

<div align="center">

<img src="screenshots/debloat-flow.gif" width="260" alt="Debloating in seven steps: open, filter, select, review, run, history, undo">

<sub><b>Debloat in seven steps</b>: open → filter → select → review → run → history → undo</sub>

</div>

<table>
  <tr>
    <td><img src="screenshots/search-menu.png" width="230" alt="Search bar under the filters, with its options menu"></td>
    <td><img src="screenshots/settings-features.png" width="230" alt="Settings: the Feature List"></td>
    <td><img src="screenshots/themes.png" width="230" alt="Settings: appearance and palettes"></td>
  </tr>
  <tr>
    <td align="center"><sub>New in v7.0: the search bar under the filters, with its options menu</sub></td>
    <td align="center"><sub>Settings → Feature List: switch tabs off, move them, Reset to Default</sub></td>
    <td align="center"><sub>Settings (the gear in the header): appearance and palettes</sub></td>
  </tr>
</table>

> The application list, the app menu and Settings are shown as they look in v7.0. The screenshots of the other tabs below date from
> v6.x and still show the previous tab names and icons; they are refreshed once the v7 series is done.

<table>
  <tr>
    <td><img src="screenshots/permissions.png" width="230" alt="First-launch permissions sheet"></td>
    <td><img src="screenshots/install-result.png" width="230" alt="Install result with Launch Application and Application Settings"></td>
    <td><img src="screenshots/installer-find.png" width="230" alt="Find APKs with a progress bar"></td>
  </tr>
  <tr>
    <td align="center"><sub>New in v6.1: the first-launch permissions sheet</sub></td>
    <td align="center"><sub>Launch Application / Application Settings after an install</sub></td>
    <td align="center"><sub>Find APKs on this device, with progress</sub></td>
  </tr>
  <tr>
    <td><img src="screenshots/installer-undo.png" width="230" alt="A found file deleted, with Undo"></td>
    <td><img src="screenshots/installer-splits.png" width="230" alt="Splits that fit this phone are ticked"></td>
    <td><img src="screenshots/installer-sources.png" width="230" alt="Common installer sources"></td>
  </tr>
  <tr>
    <td align="center"><sub>Press and hold a found file to delete it, with Undo</sub></td>
    <td align="center"><sub>The splits that fit this phone are ticked</sub></td>
    <td align="center"><sub>▾ Common installers for the -i box</sub></td>
  </tr>
</table>

<table>
  <tr>
    <td><img src="screenshots/installer.png" width="230" alt="All-in-one installer"></td>
    <td><img src="screenshots/files.png" width="230" alt="Privileged file manager"></td>
    <td><img src="screenshots/logcat.png" width="230" alt="Logcat reader"></td>
  </tr>
  <tr>
    <td align="center"><sub>All-in-one APK / APKS / APKM installer</sub></td>
    <td align="center"><sub>Privileged file manager</sub></td>
    <td align="center"><sub>Logcat reader</sub></td>
  </tr>
  <tr>
    <td><img src="screenshots/batch-select.png" width="230" alt="Batch actions"></td>
    <td><img src="screenshots/manifest-search.png" width="230" alt="Manifest viewer with search"></td>
    <td><img src="screenshots/updates.png" width="230" alt="Updates tab"></td>
  </tr>
  <tr>
    <td align="center"><sub>Batch actions on the selection</sub></td>
    <td align="center"><sub>Manifest viewer with highlighted matches and next/previous</sub></td>
    <td align="center"><sub>Updates: Galaxy Store, F-Droid, GitHub</sub></td>
  </tr>
  <tr>
    <td><img src="screenshots/profiles.png" width="230" alt="App profiles"></td>
    <td><img src="screenshots/terminal-search.png" width="230" alt="Terminal search"></td>
    <td><img src="screenshots/working-modes.png" width="230" alt="Working modes"></td>
  </tr>
  <tr>
    <td align="center"><sub>App profiles with a preview before applying</sub></td>
    <td align="center"><sub>Terminal with find, copy and share</sub></td>
    <td align="center"><sub>Working modes, ADB key fingerprint</sub></td>
  </tr>
  <tr>
    <td><img src="screenshots/backups.png" width="230" alt="Backups"></td>
    <td><img src="screenshots/drift-banner.png" width="230" alt="Watched profile banner"></td>
    <td><img src="screenshots/quick-list.png" width="230" alt="Quick list"></td>
  </tr>
  <tr>
    <td align="center"><sub>Backups: create, restore, share</sub></td>
    <td align="center"><sub>A watched profile noticed apps came back</sub></td>
    <td align="center"><sub>Choose the quick list for the tile and widget</sub></td>
  </tr>
  <tr>
    <td><img src="screenshots/whats-new.png" width="230" alt="What's new"></td>
    <td><img src="screenshots/themes.png" width="230" alt="Theme picker"></td>
    <td><img src="screenshots/pure-black.png" width="230" alt="Pure black"></td>
  </tr>
  <tr>
    <td align="center"><sub>What's new after an update</sub></td>
    <td align="center"><sub>Material 3, Material You and more</sub></td>
    <td align="center"><sub>Pure black for AMOLED</sub></td>
  </tr>
  <tr>
    <td><img src="screenshots/settings.png" width="230" alt="Hidden Settings tab"></td>
    <td><img src="screenshots/settings-edit.png" width="230" alt="Editing a setting"></td>
    <td><img src="screenshots/settings-undo.png" width="230" alt="Undo after a flip"></td>
  </tr>
  <tr>
    <td align="center"><sub>Hidden Settings: Global / Secure / System, with switches</sub></td>
    <td align="center"><sub>Edit a value; ⚠️ warns about the risky ones</sub></td>
    <td align="center"><sub>Press and hold flips a switch, with Undo</sub></td>
  </tr>
  <tr>
    <td><img src="screenshots/overlays.png" width="230" alt="Overlays tab: Material You theme"></td>
    <td><img src="screenshots/overlays-color.png" width="230" alt="Choosing a color and a style"></td>
    <td><img src="screenshots/overlays-undo.png" width="230" alt="Overlays list with Undo"></td>
  </tr>
  <tr>
    <td align="center"><sub>Overlays → Theme: the colors Android uses now</sub></td>
    <td align="center"><sub>Pick any color and one of six styles</sub></td>
    <td align="center"><sub>Overlays list: press and hold flips one, with Undo</sub></td>
  </tr>
  <tr>
    <td><img src="screenshots/about.png" width="230" alt="About tab"></td>
    <td><img src="screenshots/about-light.png" width="230" alt="About tab, light"></td>
    <td></td>
  </tr>
  <tr>
    <td align="center"><sub>About: developer, repo, Buy me a coffee</sub></td>
    <td align="center"><sub>About in the light theme</sub></td>
    <td></td>
  </tr>
</table>

### Light appearance

<table>
  <tr>
    <td><img src="screenshots/apps-light.png" width="230" alt="Light: app list"></td>
    <td><img src="screenshots/app-menu-light.png" width="230" alt="Light: app menu"></td>
    <td><img src="screenshots/debloater-light.png" width="230" alt="Light: Debloater"></td>
  </tr>
  <tr>
    <td align="center"><sub>App list</sub></td>
    <td align="center"><sub>App menu</sub></td>
    <td align="center"><sub>Debloater</sub></td>
  </tr>
  <tr>
    <td><img src="screenshots/profiles-light.png" width="230" alt="Light: profiles"></td>
    <td><img src="screenshots/backups-light.png" width="230" alt="Light: backups"></td>
    <td><img src="screenshots/settings-light.png" width="230" alt="Light: Hidden Settings"></td>
  </tr>
  <tr>
    <td align="center"><sub>Profiles</sub></td>
    <td align="center"><sub>Backups</sub></td>
    <td align="center"><sub>Hidden Settings</sub></td>
  </tr>
  <tr>
    <td><img src="screenshots/settings-edit-light.png" width="230" alt="Light: editing a setting"></td>
    <td><img src="screenshots/overlays-light.png" width="230" alt="Light: Overlays theme"></td>
    <td><img src="screenshots/overlays-list-light.png" width="230" alt="Light: Overlays list"></td>
  </tr>
  <tr>
    <td align="center"><sub>Hidden Settings editor</sub></td>
    <td align="center"><sub>Overlays: theme</sub></td>
    <td align="center"><sub>Overlays: list</sub></td>
  </tr>
</table>

> The screenshots and the animation are rendered from the app's real UI with sample data. The Quick Settings
> tiles, the widget and the notification are drawn by Android itself, so they are described below instead.

## Terminal and coding agents

The **Terminal** tab (called Command-Line Interface until v7.12.12) is a Termux-style terminal with AI coding agents built in. The
classic ADB Console is the **ADB Shell** of the separate **Console** tab now.

**Shells.** Pick one with **Shell** above the screen; each keeps its own screen and stays open while you use other tabs, so
`cd`, `export` and variables carry over:

| Shell | Runs as | Needs |
|---|---|---|
| This app (sandbox) | this app, in its private home folder, with Android's toolbox | nothing |
| Working mode | the shell user (ADB, Shizuku) or root (Root) | a [working mode](#working-modes) |
| Termux | Termux, with bash and everything installed with `pkg` | Termux from F-Droid or GitHub, plus the one-time setup below |

Termux setup (the checklist is in the Terminal's settings): install Termux and open it once, run
`mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings` in Termux,
allow this app to *Run commands in Termux environment*, and test the connection. The app then starts one Termux command
through Termux's official RUN_COMMAND intent that connects back to the app over a local connection only it can open (a one-time
token), and becomes a live bash. Full-screen programs (nano, vim, top, a Python prompt) open in a real Termux window instead.
**Automatic** picks Termux once it is set up (bash is more compatible with the coding agents' own CLI tools than the sandbox
or Working mode), otherwise Working mode, otherwise the sandbox. **Match my Termux environment** (Terminal settings, on by
default) also sources your `~/.bashrc`, so aliases and functions you keep there work in this shell too, and **Sync with Termux**
(settings, or the setup checklist) switches it on, restarts a running Termux session and opens Termux to run
`termux-setup-storage` for you; Android still shows its own storage permission prompt, which you tap once.

**Keys like Termux's**: ESC, TAB (completes file names), CTRL-C (stops), the arrows (history and cursor), HOME, END and | / - ~.
`clear` empties the screen, `exit` ends the shell (Enter starts a new one). Programs get no keyboard input while they run; in
Termux, `pkg` and `apt` answer yes by themselves.

**Coding Agents.** Choose one in **Coding Agents**, then a **Model** and an **Effort** (Low, Balanced or High: how hard the
agent is asked to think — Balanced the first time, the same as the Model list's own first, speed-and-cost-balanced entry).
Effort only changes anything for the agents whose own API has that knob (Claude, ChatGPT, Gemini, Grok); the others keep the
setting but it has no effect. The **$ / AI** button left of the input switches between shell commands and chat (`!command`
runs a command from the chat; `/help` lists the chat commands). The agent works in the current shell through four kinds of
step — run a command, read a file, write a file, edit part of a file — and **every command and every change is shown first**
(an edit as a line-by-line diff) with Run / Apply, Skip, or Always allow for the rest of the chat. Reading files in the
current folder needs no OK. `/undo` takes back the last change, STOP or CTRL-C ends everything, and switching agent, model
or effort keeps the conversation.

| Agent | Connects with |
|---|---|
| Gemini (Google), Claude (Anthropic), ChatGPT (OpenAI) | an API key from the provider (tested first), or your subscription through Gemini CLI, Claude Code or Codex in Termux |
| Cursor (SpaceX) | an API key for Cursor's Cloud Agents (they work in Cursor's cloud, optionally on a Git repository you name), or Cursor CLI in Termux |
| Copilot (GitHub) | GitHub's Copilot CLI in Termux, signed in with GitHub or with a fine-grained token (GitHub Models was retired in July 2026) |
| Perplexity, Grok (SpaceX), Muse (Meta), Deepseek | an API key from the provider's own website (tested first); no official sign-in tool, so no subscription option |
| Exa | an API key from dashboard.exa.ai/api-keys (new accounts get free credits); answers the Ask agent buttons only, not a chat; the key is kept encrypted and sent only to api.exa.ai |
| Jan.ai, AnythingLLM | the address of the computer that runs it (found by itself when it runs on the phone); AnythingLLM also needs its key |
| Ollama (on-device) | Ollama in Termux with a small coding model, installed from the agent's sheet: free and offline |
| OpenCode | the OpenCode CLI, installed in Termux |
| DroidMind, Kilo Code, Leon.ai, Browser Use, Crawl4AI | explained in their sheet: DroidMind is a tool other agents use to control Android (every agent here can already work with the phone through the Working mode shell); Kilo Code is an editor extension with no phone terminal of its own; Leon is a computer assistant |

**The classic ADB Console can ask an agent too.** Next to its input, a **$ / AI** button switches it to ask the same agent,
model and effort picked in the Terminal above — for ADB/shell syntax and code help, not full file access. The agent answers
inline in the console, and may propose one command at a time, shown with **Run** / **Skip** (or run on its own when "Ask
before running commands" is off) through the console's own execution path. Its memory of the conversation is its own,
separate from the Terminal's.

**The ADB Console says how each command looked.** Under the output of each command run in the console the console adds one line: "Finished. No error text in the
output." or "Looks like a failure:" and the reason (the output reports an error, the command timed out, or permission denied). It follows the
same failure signs as the Kotlin build's console and the app's own shell code: output that starts with `Error:` (any capitals, so adb's own
`error: device offline` counts), `adb: device ` (as in `adb: device offline`) or `Failure [`, or that holds `adb: error:`,
`SecurityException`, `Permission Denial`, `unauthorized` or the app's own time-out note. Only the very start of the output counts for
`Error:` and `adb: device `, and the other words are matched with their capitals.
The shell path hands the page one merged text and no exit code, so the line is a reading of the words, never an exit number and never the word
"success": a command that fails without printing one of them can still read "Finished", and a listing that merely contains one of them reads as a
failure. Copy and Share include the line. Rish mode (the persistent Shizuku shell) does not get it: it keeps its own `[exit N]` note.

**Subscriptions.** Anthropic and Google do not allow other apps to use a Claude or Google login, so a subscription is used
through the provider's own tool: the agent's sheet installs it into Termux (into a Debian container when it has no Android
build), opens its own website sign-in in a Termux window, and the Terminal then chats through it. These tools are not officially
supported on Android, so they may not run on every phone; when one fails, the Terminal shows its exact message.

**Keys.** Keys are sealed with an Android Keystore key (it never leaves the phone's secure hardware) and stored in the app's
private settings (backups are off). The app adds a key to a request itself, and only when the request goes to that provider's
own address (or the server address you saved for Jan, AnythingLLM or Ollama); the page only ever sees the last four characters.
Keys that appear in command output are masked before anything is sent to an agent.

**MCP button.** Next to Help, a sheet for adding a [Model Context Protocol](https://modelcontextprotocol.io) connector to the
CLI-based agents (Claude Code, Gemini CLI, Codex, Copilot CLI, Cursor CLI, OpenCode) — pick a preset (filesystem, fetch,
memory, GitHub and more) or fill in a custom one (name, a `stdio` command or a remote URL, extra environment variables or
headers), tick which agents should get it (more than one at once, so the same server reaches all of them), and apply. Each
agent's own config file is written in its own shape; a remote server's token goes into an environment variable for the agents
that expect it there, never into the file itself. Already-added servers are listed with which agents they went to, and
**Forget** removes one from all of them.

## Working modes

| Mode | Needs | Notes |
|---|---|---|
| **Automatic** (default) | — | Uses the first ready backend: ADB TCP → Wireless Debugging → Shizuku |
| **ADB over TCP** | `adb tcpip 5555` once | Connects to `127.0.0.1:5555` or any `IP:port` |
| **Wireless Debugging** | Android 11+ | Pair with code, then connect. Fields accept `IP:port` or just the port; **Auto-Detect Ports** uses mDNS |
| **Shizuku** | Shizuku running | Tap **Authorize &amp; Use Shizuku** and approve the prompt. Shizuku not running while ADB TCP or Wireless Debugging is connected: **Start Shizuku (ADB)** runs the start script the Shizuku app wrote into its own data folder (`/sdcard/Android/data/<Shizuku package>/start.sh`, the same command Shizuku shows for a computer) through that connection, then checks that Shizuku answers; if it does not, the script's own output is shown. Open the Shizuku app once first, or the script does not exist yet |
| **Root** | su (Magisk / KernelSU / APatch) | Requested on demand |
| **Read-Only** | — | Inspect only |

Every mode card has a **Use This Mode** button, so you can switch at any time, including while ADB TCP 5555
is enabled. Status checks never change the selected mode.

**ADB key:** each install generates its own private ADB key (nothing is bundled in the APK). Working Modes
shows its fingerprint, which matches the phone's "Allow USB debugging?" prompt, with Copy and Regenerate.

## Debloater

The **🧹 Debloater** tab shows the packages on your phone with
[UAD-NG](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/wiki)'s
description and removal level (Recommended, Advanced, Expert, Unsafe).

- Filter by removal level, vendor list (OEM, Google, AOSP, Carrier, Misc), state and **brand** (your phone's
  brand first).
- **Uninstall** (for your user), **Disable**, **Restore** or **Save to List**, each behind a review step that
  warns about Expert and Unsafe packages.
- Every action lands in **📜 History** with **Undo**. Filters are remembered between launches.
- The list is GPL-3.0, so it is downloaded from UAD-NG's GitHub at runtime and cached on the phone (refreshed
  weekly or on demand) rather than bundled in this repository.

## Updates

The **Application Updater** box (the right-hand box of the **APK Installer/Updater** tab) checks:

- **Samsung system apps and Galaxy Store apps** against the Galaxy Store
- **Sideloaded open-source apps** against your own sources, your imported **Obtainium** list, the Obtainium
  community catalog, **IzzyOnDroid** and **F-Droid**, with releases read from **GitHub** and **Codeberg**
- **This app** against its GitHub Releases

Update apps one by one or **Update All**. Each download is checked first: same package, newer version, **same
signing key** (a mismatch such as an F-Droid build over a developer build is stopped with an explanation),
then installed through ADB, Shizuku or Root. Apps only Obtainium can track open in Obtainium; untracked apps
get **＋ Set source**. Play Store apps keep updating through the Play Store, since Google gives other apps no
way to check them.

The first card of the box, **Find and download an app**, searches by app name or package name and downloads the app file from APKMirror (the default), Uptodown, Aptoide, F-Droid or IzzyOnDroid; Google Play only opens its page. The last card, **KeyStore**, exports, imports or renews the signing key shared with the Morphe Patcher (see the v7.12.8 note at the end).

## Hidden settings

The **🛠️ Hidden Settings** tab (after the Terminal tab, before Extra Settings) is a front end for Android's own `settings` command: the three
key-value tables behind things like USB debugging, animation speed, the screen timeout and the default keyboard.
It needs ADB, Wireless Debugging, Shizuku or Root; with none of them ready the tab says so and opens Working Modes.

| Table | What lives there | A few examples |
|---|---|---|
| **Global** | Settings for the whole phone | `adb_enabled`, `airplane_mode_on`, `development_settings_enabled`, `stay_on_while_plugged_in`, `animator_duration_scale` |
| **Secure** | Per-user settings that only the system (or ADB) may change | `default_input_method`, `enabled_accessibility_services`, `accessibility_display_daltonizer_enabled` |
| **System** | The classic per-user settings | `screen_off_timeout`, `screen_brightness`, `font_scale`, `user_rotation`, `volume_*` |

- **Tap** a setting to edit it: a value box (multi-line values and up to 20,000 characters), quick values
  (`0` `1` `true` `false` `null` `(empty)` `-1`), **Flip**, copy of the name, the value or the exact `settings put`
  command, **Reload** and **Delete**.
- **Press and hold** a setting whose value is a switch (`1`/`0`, `true`/`false`, `on`/`off`, `yes`/`no`) to flip it,
  without opening anything. The capitalisation you had is kept.
- **＋** creates a setting in any of the three tables (name, value, quick values), with the name checked before
  anything runs. **⋯** reloads, shows your **Changes**, and copies or shares the list you are looking at as text.
- **Search** matches names, values and descriptions; chips narrow the list to **Switches**, settings with a
  **Description** or the ones you **Edited**; sort by name or value. About 130 well-known settings carry a plain-English
  description, and the ones that can cut your ADB link, lock you out of the screen or break setup are marked ⚠️ and ask
  first.
- **Checked, then undoable.** After every change the setting is read back, so "saved" means the phone holds that
  value; if Android refused, its own answer is shown with advice (Xiaomi, Redmi and POCO phones usually need
  "USB debugging (Security settings)" on in Developer options). A bar offers **Undo**, and **⋯ → Changes** keeps the last 150
  changes on the phone with a **Revert** for each.

Names that can never work (empty, starting with `-`, containing `=`, spaces or control characters) are refused before a
command is built, and values reach the shell single-quoted, so `;`, `$(...)`, backticks and `>` are stored as text. The
change and its read-back go out as one line, which is how a write is told apart from a refusal
([`SettingsDb`](../src/com/bloatware/bingblop/SettingsDb.java)):

```java
static String writeScript(String op, String ns, String key, String value) {
    String read = getCommand(ns, key);                       // settings get <table> '<name>'
    String change = "put".equals(op) ? putCommand(ns, key, value) : deleteCommand(ns, key);
    return change + "; echo " + GET_MARK + "; " + read + "; echo " + END_MARK;
}
```

> Careful: these are the settings Android itself reads. Switching off `adb_enabled` over the very ADB connection the app
> uses ends that connection, and a wrong value in `secure` can lock you out of the keyboard or the screen. The ⚠️ rows ask
> before they change; everything else is on you, which is why every change is logged and undoable. Some phones (and some
> keys) refuse changes from a shell whatever you do.

## Extra Settings: Private DNS

**Where it is:** the **Extra Settings** tab, right after **Hidden Settings** on the tab bar (label *Extra Settings*, two lines), first section **Private DNS**. It is **not** in the **Hidden Settings** tab, the one that edits Android's own settings tables (that tab has a note with a button that opens Extra Settings), and it is no longer a card in the gear Settings page. A row of chips at the top of the tab shows what is set now (*Private DNS: Automatic*) and scrolls to the section when tapped.

Sets Android's Private DNS (DNS-over-TLS, Android 9 or newer) for the whole phone. Services are tiles: Google Public DNS (`dns.google`), Cloudflare (`one.one.one.one`), Cloudflare Security (`security.cloudflare-dns.com`) and Family (`family.cloudflare-dns.com`), Quad9 (`dns.quad9.net`) and Quad9 without filtering (`dns10.quad9.net`), AdGuard DNS (`dns.adguard-dns.com`), AdGuard Family (`family.adguard-dns.com`) and without filtering (`unfiltered.adguard-dns.com`), OpenDNS (`dns.opendns.com`; some networks and accounts get no answer, the speed test decides), CleanBrowsing Family, Adult and Security (`family-filter-dns.cleanbrowsing.org`, `adult-filter-dns.cleanbrowsing.org`, `security-filter-dns.cleanbrowsing.org`), NextDNS (`dns.nextdns.io`, the default profile; to filter you need your own NextDNS id as the host name) and DNS.SB (`dot.sb`); **Automatic** (mode `opportunistic`), **Off** (mode `off`, and the saved host is cleared) and a field for **another host name** with **Apply**. Applying needs a working mode (ADB, Wireless Debugging, Shizuku or Root) and writes `private_dns_specifier` then `private_dns_mode` (Global settings) through the same path as the Hidden Settings tab. Only a real host name is accepted (letters a-z, digits, hyphens and dots; at least one dot; parts up to 63 characters, the whole name up to 253; no leading or trailing hyphen; not an IP address; no spaces, quotes or other characters): it is checked before anything is sent. After a change the section reads the settings back and says what the phone really holds (*Private DNS is now: hostname dns.adguard-dns.com*); it never reports success from the write alone. A wrong host or a network that blocks Private DNS can cost the phone its internet: **Automatic** or **Off** (or Private DNS in Android's network settings) is the way back.

**Test speed** (no working mode needed): for every service, and the host name typed in the field, the app measures a TCP connect to port 853 (the closest thing to a ping), the TLS handshake and one real DNS-over-TLS lookup, three times, the median of each. Tiles fill in one by one, with *n of N* progress and a **Stop** button; the quickest working service gets a **Fastest** pill; **Sort by speed** (off by default) orders the tiles by speed. A service that did not answer says why (*Name not found*, *No answer on 853*, *Certificate error*, *No DNS answer*, *Timed out*) and with *Did not answer on your network. If you apply it, this phone may lose its internet; Automatic or Off is the way back.*; tapping it asks for a second tap before it is applied. A tile can also say *Couldn’t test now* (the phone had too many name lookups waiting): that says nothing against the service, test again. The numbers are a measurement from **this phone's network only**, at this moment: another Wi-Fi, mobile data or another hour can give another order. Your phone first looks up each service's name with the DNS it uses now (once); then each service may receive up to three short encrypted connections, each asking for `example.com`; ICMP ping is not available to apps, so the times are for the encrypted connection Private DNS really uses. Not run on a phone yet.

## Extra Settings: Animation speed, Brightness bias and Device telemetry

**Where they are:** the **Extra Settings** tab, the three sections under Private DNS, in this order. The chips at the top of the tab add *Animations: 1×* and *Battery: 49%*, each scrolling to its section when tapped. The two sections that change the phone follow the Private DNS rule: a change is written through the same path as the Hidden Settings tab (`settings put`, through the working mode), then **read back**, and what the section says is what the phone reports, never what was merely sent. Without a working mode they say *Viewing only*, still show what the phone holds (read with the app's own rights) and keep their controls off, with the reason in words and a **Set Up** button.

**Animation speed.** The three scales of Developer options, the Global settings `window_animation_scale`, `transition_animation_scale` and `animator_duration_scale` (windows, dialogs and menus; one screen to the next; everything else that moves inside apps). Each row has a slider 0–10 (step 0.05) tied to a number field, the chips **Off · 0.5× · 1× · 1.5× · 2×**, a **preview dot** that moves at the chosen speed so you see the effect before applying (it jumps at Off and stays still under reduced motion), and *On the phone: 1×*. The field takes any number of 0 or more; above 10 is allowed with the warning *Very slow: Reset to 1× brings it back*; text, negative numbers, `Infinity` and exponents are refused in words and nothing is sent. **Apply** writes the three (as `1.0`, `0.5`, the way Developer options writes them, window first), then reads all three back; **Reset to 1×** sets all three to 1× and applies; **Check again** reads again. The line *The phone holds: 1× · 1× · 1×*, the pill (one value, or *Mixed*) and the *Animations* chip come from the read-back; values the phone did not keep are reported as *Not applied as sent*, a refused write with Android's own words. Not run on a phone yet.

**Brightness bias.** `screen_auto_brightness_adj` in the System table, −1 to 1: 0 is neutral, above 0 shifts adaptive brightness brighter, below 0 darker (what the brightness slider changes while adaptive brightness is on). The value is shown big with a phrase (*+0.30 Brighter than normal*, *Much darker* from ±0.75), a slider −1…+1 (step 0.05) whose track shades dark→neutral→bright, a number field that **refuses** anything outside −1…1 in words (never a silent clamp), the chips **Darker −0.5 · Neutral 0 · Brighter +0.5**, **Apply**, **Reset to 0** and **Check again**. The section also reads `screen_brightness_mode`: when adaptive brightness is off it shows *Adaptive brightness is off, so this has no effect until you turn it on* with an **Open display settings** button (Android's own Display settings, `ACTION_DISPLAY_SETTINGS`); the app never switches adaptive brightness on by itself. After Apply the value is read back; when the phone reports something else (Samsung One UI and some other phones keep their own curve) the section says *Applied, but the phone reports 0.00 instead of +0.50. One UI and some other phones reset or ignore this value*. There is no live preview (the phone sets its brightness from the light around it), and a hint says so. Not run on a phone yet.

**Device telemetry.** Three rings, **RAM used** (MemTotal minus MemAvailable, as the Task Manager counts it), **Storage used** (the data partition) and **Battery**, with the percentage in the middle and *8.5 of 15.8 GB* under it; the battery ring turns amber at 20% or less, red at 10% or less, and shows a bolt while plugged in. Under them six chips: **Model**, **Android** (version, API level, One UI on a Samsung), **Kernel** (the short version; the report has it whole), **Uptime**, **Battery temp** (the Task Manager's unit, with the health) and **Swap used**. A value that cannot be read says *Not available*, never a blank or NaN. A **Refresh** icon button (it spins for a moment) and **Copy report** (one plain line each for model, Android, kernel, uptime, RAM, storage, battery and swap). The numbers refresh every five seconds while the tab is on screen (pill *Live*) and stop when you leave it or the screen goes off (*Paused*). One quick native call (`getTelemetry`) reuses what the Task Manager and the About tab already read (`/proc/meminfo` through MemStats, StatFs on the data partition, the battery broadcast, Build, `/proc/version`); no working mode and no shell. Not run on a phone yet.
## Extra Settings: Power and reboot

**Where it is:** the **Extra Settings** tab, the section **Power & reboot** (after Private DNS). Six tiles in two columns, each with a coloured icon, a bold title and one line in plain words: **Reboot** (`reboot`), **Soft reboot** (`setprop ctl.restart zygote`, Root mode only: in ADB, Wireless Debugging and Shizuku mode the tile is locked and says *Needs Root mode*, never a silent failure), **Recovery** (`reboot recovery`), **Power off** (`reboot -p`, then `svc power shutdown` when the first did nothing), **Bootloader** (`reboot bootloader`) and **Restart SystemUI** (`am crash com.android.systemui`, the same command the renderer switch in Task Manager runs). Power off is tinted red, Recovery and Bootloader amber. Everything runs through the working mode (`AndroidBridge.executeShellAsync`, an id per request, the answer through `window.onShellDone`, a timeout, and an exit-status marker as the other direct shell calls use); without a working mode every tile is disabled with *Viewing only*, a lock box says why and offers **Set Up**.

A tap opens the app's bottom sheet with what will happen in plain words, the exact command, the mode it runs through and what to expect. Nothing runs until you confirm there; opening and closing a sheet sends nothing. Reboot, Soft reboot and Restart SystemUI confirm with a tap; Recovery, Power off and Bootloader only with a **press-and-hold** of 1.2 seconds (a ring fills; a short press or a click does nothing; the keyboard works too). A reboot cannot be read back, so the sheet says what to expect and a **10-second watchdog** reports *Nothing happened within 10 seconds: the mode could not do it* with the command's output when the app is still there; a command the mode refuses is reported at once with its words. Restart SystemUI reads the System UI process id before and after and says whether it really restarted. Not run on a phone yet.

## Extra Settings: Doze and power governor

**Where it is:** the **Extra Settings** tab, the section **Doze & power governor**. A horizontal timeline of the five deep-Doze phases, Active, Inactive, Idle pending, Idle (deep) and Maintenance, with the current one lit and the exact token under it (`ACTIVE`, `INACTIVE`, `IDLE_PENDING`, `SENSING`, `LOCATING`, `IDLE`, `IDLE_MAINTENANCE`, `QUICK_DOZE_DELAY`), and a row of chips for the light state (`ACTIVE`, `INACTIVE`, `PRE_IDLE`, `IDLE`, `WAITING_FOR_NETWORK`, `IDLE_MAINTENANCE`, `OVERRIDE`). Read from `dumpsys deviceidle get deep`, `get light`, `get force` and a grep of the full report (`mUseMotionSensor`, `mMotionListener.active`, `mMotionSensor`), when the tab opens, after every action and on **Check again**. Four actions, each one sentence and the confirm sheet: **Force deep Doze** (`dumpsys deviceidle force-idle deep`), **Step to next phase** (`dumpsys deviceidle step deep`), **Wake device** (`dumpsys deviceidle unforce`, never `disable`) and **Simulate motion** (`dumpsys deviceidle motion`); the result is the phase read back from the phone, with the command's words under it. The **Motion sensor** line says only what the report says; a phone that does not print those lines reads as *state unknown*, and there is no switch because Android has none.

**Power profiles:** three cards, **Performance** (`cmd power set-fixed-performance-mode-enabled true` and `settings put global low_power 0`), **Balanced** (`low_power 0`, fixed performance off and `cmd power set-adaptive-power-saver-enabled true` where the phone has it) and **Ultra saver** (`low_power 1`); the settings go through the Hidden Settings path (`settingsOp`), the `cmd power` calls through the shell. The section reads `cmd power help` first and locks Performance with *Not on this Android version* when the command is missing (Android 10 and older). The marked card comes from the read-back only: `low_power` for Balanced / Ultra saver, and Performance only when the phone prints a fixed-performance state in `dumpsys power`; most phones do not, and the section then says so and keeps Balanced marked. A write the phone does not keep is reported with what it holds. Not run on a phone yet.

## Extra Settings: Zram and memory

**Where it is:** the **Extra Settings** tab, the last section, **Zram & memory**. A single-line badge (*3.0× compression*), a stacked bar (Compressed in RAM / RAM saved / Free swap) and a 2×2 grid: **Allocated** (`/sys/block/zram0/disksize`), **Compressed in RAM** (`mem_used_total`), **Uncompressed data** (`orig_data_size`) and **RAM saved** (`orig_data_size` minus `mem_used_total`, in the ok colour), from `/sys/block/zram0/mm_stat` and `/proc/meminfo` (`SwapTotal`, `SwapFree`), read through the working mode with plain `cat`, every 5 seconds while the tab is on the screen (the timer stops when the tab is left or the page goes to the background) and on the round button. A phone without zram, or whose shell may not read the counters, shows *Not available* with what `cat` said. Three buttons behind the confirm sheet: **Reclaim disk caches** (`sync; echo 3 > /proc/sys/vm/drop_caches`) and **Drop page caches** (`sync; echo 1 > /proc/sys/vm/drop_caches`), both Root mode only (locked with *Needs Root mode* elsewhere, never hidden), and **Trim app caches**, which runs the Settings page's own Trim Caches in All Applications path (`pm trim-caches 128G`) in every working mode. After an action the numbers are read again and the before/after change is shown in the sheet and under each box. Not run on a phone yet.

## Overlays and Material You

The **🌈 Overlays** tab (right after the Extra Settings tab, which follows Hidden Settings) has two halves, chosen by the sub-tabs at its top. Changing anything needs ADB,
Wireless Debugging, Shizuku or Root; with none of them ready the tab says so and opens Working Modes. The colors Android
is using *now* are read without any of them.

**Theme.** Android 12 and newer builds every system color from one *source color*. This half changes that source and the
style the palette is worked out with:

- **Colors in use** draws the five tonal palettes Android has right now (Accent 1–3 and Neutral 1–2, ten steps each), read from
  the system itself, with a line saying what is set (*System default*, or *Custom color #7E57C2 · Expressive · 2 min ago*).
  Tap a swatch to copy its color. After a change it redraws by itself once Android has repainted. On Android 11 and older
  it says that Material You needs Android 12, switches **Apply theme** and **Default** off, and the tab opens on the overlay list.
- **Color:** **Wallpaper** (what Android does by default) or **Custom color**. Pick one with a hex box (`6750A4`,
  `#6750a4` and `#abc` all work), the **hue / saturation / lightness** sliders, or the **657 named presets** (searchable,
  with family chips such as Blue or Green: the list the Tasker project this tab grew from carried). The last eight colors you used
  are kept as chips.
- **Style:** Tonal Spot (Android's default), Vibrant, Expressive, Fruit Salad, Rainbow or Spritz, each with a line saying what it does.
- **Apply theme** reads what the setting holds, writes the choice, reads it back, and shows **Undo**; **Default** returns to
  the wallpaper with Tonal Spot. The other choices stored in the same setting (a font, icon shape or icon pack on Pixel-like
  phones) are kept: only the color keys are replaced. Every theme change is also kept in **Hidden Settings → ⋯ → Changes** with a
  **Revert**. Android tends to restart apps (this one included) when the palette changes; the app notices that it was
  restarted, whether that came before or after the answer, and comes back to this tab with the Undo bar waiting.
- **Root-only theme controls** (the last card of the Theme half): colors Android takes only from an overlay written as root
  (`cmd overlay fabricate`, which Android's overlay service accepts from root alone, so ADB and Shizuku get locked rows, as on
  the Root Features tab). The first control is **Pure black backgrounds**: with Super User and the switch on, **Apply theme**
  also writes the overlay `com.android.shell:aam_monet` (true black dark backgrounds and a few dark greys for the panels; on
  Android 12 and 13 only the two darkest neutral shades, one overlay each; light mode keeps its colors, except that its darkest text
  becomes pure black: the two shade 900 entries belong to both modes, and Android's light theme takes its main text from neutral1 900), reads the list and the colors back to judge it (listed
  on with a color that comes back as Android's: the toast and the sheet's title say *The pure black overlay is on, but Android shows
  its own colors*, or *The theme overlay is on, but Android shows its own colors* with a style's colors, and the record is kept), and
  **Default** and Apply with the switch off switch it off again (it stays listed as off: the shell cannot remove a
  fabricated overlay). **Undo** (the bar after Apply or Default, also after a restart) puts back what was there before the
  change: the old theme and this app's overlay as it was, switched off when there was none, written again when there was one
  (a record with a style's colours keeps the source color, the mode and the contrast for this; one from an earlier version
  without them, or one still waiting for its own write's answer, is switched off, and the message says so; when the Undo's
  theme answer is lost to a restart, the overlay follows once the theme reads back as put back). Apply takes the switch, More styles and Color spec when it is tapped, with the other choices: a change
  while it reads Applying… counts for the next Apply only. When the theme's own answer is lost (the app restarted first, or no
  answer), the resume record keeps that Apply's overlay half and its choices: once the theme reads back as written, the overlay is
  written as that tap would have; read back as not written, nothing is written and a toast says *The colors were not written. Tap
  Apply theme again.* Not yet tried on a rooted phone.
- **Which Android versions:** each Android release builds its theme colors its own way (the shade rule, the list of
  roles and their names, the write order, the color spec), so the app computes the root colors for the build family the phone is
  and writes them with that release's names in its order; everything else is locked (fail closed). `MonetProfile` (Java, no
  Android classes) decides from the SDK level, which of five framework color names `getIdentifier(name, "color", "android")`
  finds (`system_inverse_surface_light`, `system_theme_notif_light`, `system_on_theme_app_ring_light`, `system_primary_dim_light`,
  `system_semantic_red_container_high_light`) and the One UI version; `getSystemPalette` reports it as `profile` and
  `profileReason`, with `build` (`Build.FINGERPRINT`) and `contrast` (`UiModeManager.getContrast()` on Android 14+, held to
  [-1, 1], 0 when it cannot be read). Live: Android 12 and 13 (`P12`, Pure black only), 14 (`P14`: Android 14.0's own maths on every
  Android 14 build; 14 QPR1-QPR3 compute their roles with an early libmonet and no color name tells 14.0 from QPR1/QPR2, so there,
  measured against libmonet android14-qpr3, Fidelity and Content differ in about 1 in 10 role values at contrast 0 (on-colors and the
  light outline), Monochrome in the light outline only, about 4 in 10 at a higher contrast; shades and Pure black are the same), 15 QPR1 (`P15Q1`, 165
  entries with Android 14's 44 roles in its order), 15 QPR2 (`P15Q2`) and 16.0 (`P16`, 177 entries: the 44 plus inverse_surface,
  inverse_on_surface, inverse_primary, shadow, scrim and surface_tint). Locked, all six rows, with Super User or without:
  15.0, 16 QPR1, 16 QPR2, 17 and any build that matches no row (*Not supported on this Android version yet*: 15.0 uses an older
  libmonet, 16 QPR1/QPR2 compute at spec 2025 with changes of their own and write `_light`/`_dark` palette names, 17 at 2026
  with the fixed colors in both modes), and any phone with a One UI version (*Not supported on One UI*: Samsung builds its theme
  with its own service, by other developers' reports, not checked); a Samsung phone whose One UI version cannot be read (getprop
  fails or times out and `SEM_PLATFORM_INT` gives none) is locked the same way, and that answer is not kept, while one whose
  `ro.build.version.oneui` is not set (another build of Android) is decided by its names. An older APK that reports no profile counts as *Update the
  app* on SDK 35 and up. Lock order: *Update the app* (no overlay bridge), the version lock, *Needs Super User*. A locked card's
  note says only whether an overlay written before is still on; with Super User, Apply (a disable), **Default** and **Undo**
  switch it off. Without Super User the note is *Shizuku and ADB cannot write these colors; Root can.* with the *is written* / *may be
  written* line under it when an overlay is still on, and **Default** (which then writes the theme setting only) ends its question with *The overlay stays
  on: switching it off needs Super User.* On 15 QPR1, 15 QPR2 and 16.0 shade s is tone (1000 - s) / 10 of the palette (500 is tone 50; Android 14 uses
  49.6), with no chroma cap, from the scheme of the mode the phone is in at Apply; SystemUI's own 21 or 22 custom colors (clock,
  shade, notification, widget and others) are not written and keep Android's values for the list style. Phone makers can change
  Android's color engine and names; none of this was checked on a phone.
- **Contrast and Android updates:** a style's roles and fixed colors are computed at the phone's contrast read when **Apply theme**
  is tapped (the palettes, the preview and Pure black do not depend on it); after changing the contrast in Settings, tap Apply
  again. The record (`ovl_root_applied`) also keeps `profile` (when it is not P14) and `build`; when the record's build and the
  phone's are both known and differ, the record's profile is not the phone's (a monthly update within one profile gives no note: the
  colors depend on the profile, not the build, so Apply would write the same entries), the record carries a style's colors and Apply
  would write a style's colors (Pure black alone is the same on every build and gives none), a note under the card reads *Android was updated after
  these colors were written. Tap Apply theme to write them for this version.* Nothing runs by itself. After an update to a
  locked release the rows lock instead and the overlay stays on (an overlay's entries whose names the new release lacks are
  skipped, read in idmap2) until it is switched off.
- **Color spec** (the second control of that card, between Pure black and More styles; Super User on Android 14, 15 QPR1/QPR2 or
  16.0, locked like More styles: *Needs Super User*, *Needs Android 14* on 12 and 13, the version locks above, *Update the app*
  without the overlay bridge or the spec maths): **2021** (the colors Android computes itself; the default, and what Android 14,
  15 and 16.0 build their own theme with, 15.0 with an older libmonet; 16 QPR1/QPR2 build theirs at 2025 with changes of their
  own and 17 at 2026, where the card is locked), **2025** (Google's newer tuning of the same styles) and **2026** (the 2025 tuning,
  plus the CMF style), kept in `ovl_spec`. Google's own
  rule (Material Color Utilities' `maybeFallbackSpecVersion`) gives the newer colors to Tonal Spot, Vibrant, Expressive and Spritz only
  (2025, also at 2026), CMF its 2026, and keeps Rainbow, Fruit Salad, Fidelity, Content and Monochrome at 2021 (16 QPR's libmonet gives
  every style its 2025 roles). At 2025 or 2026, with one
  of those four in the style list and More styles on **Off**, **Apply theme** writes the theme setting as before and then that style's 2025
  colors over Android's with root, the same 165 or 177 colors and overlay as More styles (a More style chosen wins); the hint reads *Also writes
  this style's 2025 colors with root, in place of Android's.* From 2025 the base palettes of Tonal Spot and Expressive differ between light
  and dark (Vibrant's, Spritz's and CMF's do not), so the 65 shades are those of the mode the phone is in when Apply is tapped (switch mode,
  tap Apply again; the preview is drawn again on a mode switch); the roles are written for both modes.
  The 2025 and 2026 maths is `assets/monet.js` part 2, a port of Material Color Utilities on GitHub (main, commit bd811e4, 2026,
  Apache-2.0); on 15 QPR1, 15 QPR2 and 16.0 part 2 computes every spec, 2021 included, at the phone's contrast. A grey custom
  color (chroma under 5) becomes Google blue for these styles and CMF, as Android does, and the preview says
  *From Google blue: #808080 is too grey for this style*. **2021** then Apply (with More styles on Off, Pure black off, the Intensity
  sliders at 100, no accent set and Accurate shades on) and **Default** switch the overlay off; **Undo** puts back the overlay from before (off when there was none). Not yet tried on a rooted phone.
- **More styles** (the third control of that card; Super User on Android 14, 15 QPR1/QPR2 or 16.0, a locked row reading *Needs Android 14* on
  12 and 13 and the version locks above elsewhere):
  **Off**, **Fidelity** (accents keep your exact color; backgrounds take a faint tint of it), **Content** (like Fidelity, with a third accent
  chosen to sit beside your color) and **Monochrome** (no color at all: black, white and greys), styles Android has no setting for, and,
  while Color spec is 2026, **CMF** (one color family: accents and backgrounds are your color at different strengths; leaving 2026 sets
  More styles back to Off). The app computes their colors itself (`assets/monet.js`: on Android 14 a port of Android 14's color engine
  from AOSP and Google's Material Color Utilities 0.4.0, both Apache-2.0; on 15 QPR1, 15 QPR2 and 16.0 part 2 at 2021, Fidelity and
  Content with MCU main's TemperatureCache, checked against those releases' SystemUI and libmonet compiled and run; CMF from part 2,
  see Color spec; Android 14 to 17 accept Monochrome as a theme style, none Fidelity or Content, and 17's code has a CMF case that it
  does not accept as a style) from the custom color, else the wallpaper's
  color (the one the theme setting carries for a wallpaper source, not a custom color it carries; else the first main color Android
  reports for the wallpaper set last, lock or home by WallpaperManager id as SystemUI does, that is not almost grey; SystemUI ranks
  the whole color map by hue, which apps cannot read, so this may differ from the color Android picks), else Google blue, taken when Apply is tapped; a **Preview** under the options (also with **Off**, for a
  list style Color spec recolors) shows the five rows (13 shades each) and names the source.
  **Apply theme** still writes the theme setting from the style list first and then the overlay on top: 165 colors on Android 14 and
  15 QPR1 (65 palette shades, the 44 roles light and dark, 12 fixed), 177 on 15 QPR2 and 16.0 (50 roles), with each release's names in
  its order, Pure black's values winning where both name a resource; the choice is kept in `ovl_root_style`,
  what the overlay carries in `ovl_root_applied` (with `spec` when it is not 2021, `adjust` when an Intensity slider is not at 100,
  `accents` when a Secondary or tertiary accent is set, `accurate: false` when Accurate shades was off, `profile` when it is not P14 and `build`; PR 2's `ovl_black_applied` is read as a fallback).
  Messages then speak of the *theme overlay*; **Off** then Apply and **Default** switch it off; **Undo** puts back the overlay from before (off when there was none). Spritz in the style list is the
  style other theming apps call Neutral; Android has it from 13, so it needs no root. Not yet tried on a rooted phone.
- **Intensity** (the fourth control of that card, under More styles and above the accents; Super User on Android 14, 15 QPR1/QPR2 or 16.0, locked like More
  styles: *Needs Super User*, *Needs Android 14* on 12 and 13, the version locks above, *Update the app* without the overlay bridge or
  when `monet.js` has no `MonetPlan.lib.adjustOf`; the locked row keeps its line): three native range sliders, **Accent saturation**,
  **Background saturation** and **Background lightness**, 0 to 200 % in steps of 5, 100 % when never moved, the row's line *100% leaves
  Android's colors as they are.* and a **Reset** text button that sets all three to 100 (greyed out at 100/100/100). The values are kept
  in `ovl_adj_accent_sat`, `ovl_adj_bg_sat` and `ovl_adj_bg_light` when a slider is let go (its change event); moving one (its input
  event) only redraws the value, the hint and the preview, writes nothing and runs nothing in the background. The maths is
  `opts.adjust = { accentSat, bgSat, bgLight }` in `assets/monet.js`, both parts, with formulas written for this project, applied to the
  scheme's tonal palettes before anything is derived from them: accent saturation scales the chroma of primary, secondary and
  tertiary, background saturation that of neutral and neutral variant, and background lightness has those two give, for tone t, the
  colour at clamp(t + 0.1 · (v − 100) · min(1, t / 10, (100 − t) / 10), 0, 100) (tones 0 and 100 never move: shade 0 stays #FFFFFF and
  shade 1000 #000000; at 200 shades 10, 50 and 100 of a neutral row reach white, at 0 shade 900 reaches black; so at 200 the light
  background roles from about tone 90 up, `background`, `surface`, `surface_bright` and the five `surface_container` ones, all turn
  white (at 2025 `surface_container_highest` at times only next to it), and at 0 `background`, `surface`, `surface_dim` and the lowest containers turn black in dark mode: the tiers stop
  differing). The error palette is never touched; the engine still solves every role on the adjusted palettes, and the tone map
  comes after it. With any slider not at
  100, **Apply theme** writes a style's colors with root: a More style as before, adjusted, else the list style at the spec Google's rule
  gives it (2021 included), adjusted; the hints are *Also writes this style's colors with root, adjusted by the Intensity sliders.*,
  the 2025 one, the More style and CMF ones (*…, in place of the style above, adjusted by the Intensity sliders.*) and each with pure
  black. Apply takes the values at the tap; the preview follows them live and appears or goes away as a value leaves or reaches 100
  (the page scrolls by as much, so the slider stays under the finger). All three at 100 change nothing: the plans, hints and records
  are the ones from before. Monochrome is unchanged by both saturation sliders and Rainbow's neutrals by background saturation (no
  chroma). Measured WCAG contrast (`MonetPlan.roles()`, saturation at 100, contrast 0, seeds 6750A4 1B6EF3 B5651D 00FF00 808080
  FFDE3F, light and dark; P14 and P16 equal), worst case at bgLight 100 / 0 / 200: on_surface on surface 14.29 / 12.29 / 13.07 for the
  nine styles at 2021, 11.53 / 13.05 / 8.29 for Tonal Spot, Vibrant, Expressive and Spritz at 2025, 12.09 / 13.07 / 9.00 for CMF;
  on_surface_variant on surface_container_highest 7.17 / 7.10 / 6.42, 4.84 / 5.38 / 4.13 and 4.92 / 5.41 / 4.42 (for these two pairs the
  drop is mostly in light mode at 200). Accent text does not follow bgLight while the panels do, so its worst cases are at the other
  ends: primary on surface_container_highest 4.96 / 3.76 / 5.05 at 2021, 4.84 / 3.72 / 5.00 at 2025, 4.95 / 3.74 / 4.14 for CMF (0 in
  light mode, CMF's 200 in dark mode); inverse_primary on inverse_surface (P16) 4.96 / 3.76 / 5.44 at 2021 (0 in dark mode), 6.04 /
  5.76 / 5.60 at 2025, 6.06 / 5.77 / 5.59 for CMF. **Reset** then Apply,
  **Default** and **Undo** act as for More styles. Not yet tried on a rooted phone.
- **Secondary and tertiary accents** (the fifth control of that card, under Intensity; Super User on Android 14, 15 QPR1/QPR2 or
  16.0, locked like Intensity: *Needs Super User*, *Needs Android 14* on 12 and 13, the version locks above, *Update the app* without the
  overlay bridge or when `monet.js` has no `MonetPlan.lib.accentsOf`; the locked row keeps its line): the row's line *Your own colors for
  the second and third accent. The main color stays as it is.* and two entries, **Secondary accent** and **Tertiary accent**, each the
  color bubble of Settings' Granular Color Pickers (the swatch and `#RRGGBB` when set, a dashed empty circle and *From the style* when
  not), with a **Clear** text button on a set entry. Tapping an entry opens the page's color popup (titled *Secondary accent* or
  *Tertiary accent*) at the stored color (*Current color #E91E63 • Apply theme writes it to the phone*) or, unset, at the key color the
  style gives that row now, for the style Apply would write without the accents and the sliders (*The style gives #7B748A now • Apply
  theme writes your color to the phone*); its **Apply** stores the color (an unchanged one too), **Cancel** stores nothing, and neither
  calls the bridge. Kept in `ovl_accent_secondary` and `ovl_accent_tertiary` (`RRGGBB` in capitals; `''`, nothing or any other value
  is "from the style"). The maths is `opts.accents = { secondary, tertiary }` in `assets/monet.js`, both parts, a rule written for this
  project: for a color X the scheme of the same style, spec, mode, contrast and profile is built from X as the seed (X as it is: the
  Google-blue rule for greys is not applied), and its primary palette P replaces the secondary (accent2) or tertiary (accent3) palette
  before the shades, the roles, the fixed colors and `palette_key_color_*` are derived; where P's hue is not X's within 0.5 degrees
  (Expressive at 2021, seed hue + 240, and Fruit Salad at every spec, seed hue - 50), the palette at X's hue with P's chroma takes its
  place. So the hue picked is kept in every style and the style sets the chroma: at 2021 that of the style's primary, 36 Tonal Spot, 200
  Vibrant, 40 Expressive, 48 Rainbow and Fruit Salad, 12 Spritz, on every profile and for every color (the styles' own secondary /
  tertiary: 16/24, 24/32, 24/32, 16/24, 36/36, 8/16); Fidelity, Content and CMF keep X's chroma (under CMF the tertiary and
  tertiary_container roles, and so the tertiary fixed colors, keep the seed's tone: CMF's 2026 rules take it from a second source
  color and the app gives CMF one, the seed; 6750A4 with FFDE3F gives tertiary_container 6F5E00); at 2025 Tonal Spot and Expressive differ
  between light and dark and Spritz gives blue hues 12, others 8. Grey colors give a colored row at the style's chroma in the stock
  styles (808080 and FFFFFF teal, 000000 pink). The accents come before `opts.adjust`, so Accent saturation scales them. Only accent2 and
  the secondary roles (accent3 and the tertiary roles) change; Monochrome's entries are the same with them, so under Monochrome the page
  passes none. With an accent set, **Apply theme** writes a style's colors with root: a More style as before, with the accents, else the
  list style at the spec Google's rule gives it (2021 included); the 2021 hints are *Also writes this style's colors with root.* and
  *Also writes this style's colors and pure black with root.*, the 2025, adjusted and More style ones are reused, and a second sentence
  names the accents (*Secondary accent: your color.*, *Tertiary accent: your color.*, *Secondary and tertiary accents: your colors.*).
  Apply takes the colors at the tap; the preview follows them as they are stored. With none set, the plans, hints and records are the
  ones from before; the record gains `accents` (the set ones) only when it has a style and one is set. Any source color works (the
  custom color, the wallpaper's, Google blue). **Clear** on both then Apply, **Default** and **Undo** act as for More
  styles. Not yet tried on a rooted phone.
- **Accurate shades** (the sixth control of that card, under the accents; Super User on Android 14, 15 QPR1/QPR2 or 16.0, locked like
  the accents: *Needs Super User*, *Needs Android 14* on 12 and 13, the version locks above, *Update the app* without the overlay bridge
  or when `monet.js` has no `MonetPlan.lib.accurateOf`; the locked row keeps its line, and a stored off counts as on while locked): a
  switch drawn like Pure black's, on by default, the row's line *On: every accent shade keeps its own tone. Off: the pale accent colors
  (shade 100 and, mostly with Color spec 2021, the light container and fixed colors) take a darker tone.* Kept in
  `ovl_accurate_shades` (`'0'` is off, `'1'` on; nothing or any other value is on). Flipping it redraws the preview and the hint, writes
  nothing and runs nothing in the background. The maths is `opts.accurate` in `assets/monet.js`, both parts, a rule written for this
  project (Android and Material Color Utilities have no such option): `false` makes the scheme's primary, secondary and tertiary
  palettes (after the accents and the Intensity sliders) answer tone 90 with their tone-70 color, so everything taken from them at
  exactly tone 90 gets the tone-70 color: `system_accentN_100` (which equals `system_accentN_300` on every profile; P14's shade rule
  with its chroma cap is unchanged) and every role, fixed and `palette_key_color_*` color whose computed tone is exactly 90. Tones are
  not changed: contrast still moves a role's tone first, and the tone rules that read a palette (Fidelity/Content's containers) read
  it as it is with the switch on. Neutral, neutral variant and error palettes never change; palettes() still reports each palette's
  own hue, chroma and key. `true`, `null` or left out change nothing (byte for byte the results from before the option); any other
  value throws. Which roles that is (measured on six seeds, no accents or sliders, every profile): at 2021 the light containers
  (`primary/secondary/tertiary_container`; Fidelity/Content only `secondary_container` for 2 of 6 seeds, on P15Q1/P15Q2/P16 but not
  P14), the dark on-containers, the three `_fixed` colors (all but Monochrome; `tertiary_fixed` like the other two) and light
  `control_activated` (all), and for Monochrome its greys at 90 (light `on_primary`, `on_tertiary`, the two `on_*_fixed_variant`; dark
  `on_secondary_container`, `tertiary`); at 2025/2026 only what lands on 90 (light `control_activated`, some containers and fixed
  colors for some seeds, `inverse_primary` with Expressive on P15Q2/P16, dark `secondary`/`tertiary` with Vibrant at 2025); at
  contrast 0.5 most of these move off tone 90. `palette_key_color_*` did not land on 90 for any seed checked. Example: 6750A4, Tonal
  Spot, 2021, every profile: accent1_100 E9DDFF becomes B3A2E1, and so do light `primary_container`, dark `on_primary_container`,
  `primary_fixed` and light `control_activated`; light `secondary_container` E8DEF8 becomes B0A7C0, light `tertiary_container`
  FFD9E3 becomes D29DAD; light `primary` 65558F and the neutral rows stay. At 2025 (P15Q2) light `secondary_container` changes the
  same way while `primary_container` (D4C3FD, not at 90) stays. Text contrast falls with it (WCAG ratio, same example): light
  `on_primary_container` on `primary_container` 7.26 to 4.09 (P14: 13.25 to 7.48), dark 7.26 to 4.09 on every profile. Where this
  shows is inferred, not measured: Material 3 components put `secondary_container` behind tonal buttons, selected chips and the
  navigation bar's indicator and `primary_container` behind FABs, so apps following Material You's role colors would show it in
  light mode at 2021; apps reading `system_accentN_100` show the darker shade. Which screens do is not known. The page's own Colors
  in use reads those shades (`getSystemPalette`); the app's own Material You look (`materialYouScheme`) reads accent shades 200 and
  600 and the neutral rows only, so it does not change. ColorBlendr's switch of the same name, read in its source and not run,
  changes the same kind of colors (shade 100 and what its roles read at that tone); one difference: it leaves `tertiary_fixed` light,
  this app does not. With the switch off, **Apply theme** writes a style's colors with root: a More style as before, with the
  darker colors (Monochrome too: E2E2E2 becomes ABABAB), else the list style at the spec Google's rule gives it (2021 included); the
  first sentence of the hint is the one the accents brought (*Also writes this style's colors with root.* and its pure black form at
  2021, else the 2025, adjusted or More style one), and a last sentence, after the accents' one, says *Accurate shades: off.* Apply
  takes the value at the tap (a tap, a resume record or a record without it counts as on); the record gains `accurate: false` only
  when it has a style and the switch was off, and Undo writes an overlay from before with the record's value, not the switch's. With
  the switch on, the plans, hints and records are the ones from before. The preview shows the shades only (shade 100 in shade 300's
  color), not the roles. Pure black names only neutral shades and dark backgrounds and surfaces, none of which the switch changes.
  The overlay check after a write reads accent1_600, neutral1_500, primary_light and surface_dark, none of which change. Turning it on
  then Apply, **Default** and **Undo** act as for More styles. Not yet tried on a rooted phone.

**Overlays.** Everything `cmd overlay list` reports, grouped by the app each overlay restyles (`android`,
`com.android.systemui` …), each group showing how many of its overlays are on:

- A **switch** on every row. **Press and hold** a row to flip it without opening anything, or **tap** the row for its sheet:
  state, target app, **Switch on / off**, and copies of the name, the target or the exact `cmd overlay` command.
- **Search**, and chips for **On**, **Off** and **Theme** (names that look like theme, icon-pack, navigation-bar, font or
  shape overlays). Long lists are drawn 120 rows at a time with **Show more**.
- An overlay Android lists as **unavailable** (its target app is missing, or it is not approved) says so and cannot be switched.
- After every change the whole list is read again, so a row shows what Android reports, not what was asked for. Some
  overlays are fixed on or off by the system; when one refuses, the answer says so (Android 12 and newer often give no
  reason at all, and the sheet says exactly that) instead of pretending. A bar offers **Undo**.

What is written is small and fixed. An overlay change is `cmd overlay enable '<name>'` or `disable`, followed by the list
again in the same line, which is how a change is told apart from a refusal
([`OverlayRules`](../src/com/bloatware/bingblop/OverlayRules.java)):

```java
static String changeScript(String op, String id) {
    return changeCommand(op, id) + "; echo " + GET_MARK + "; " + listCommand() + "; echo " + END_MARK;
}
```

and a theme is one `settings put secure theme_customization_overlay_packages '<json>'` whose color members are exactly this,
the shape Android's own wallpaper picker writes (the `_applied_timestamp` is the time of the change), followed or preceded by
whatever other choices the setting already held:

```json
{"android.theme.customization.system_palette":"7E57C2","android.theme.customization.color_source":"preset",
 "android.theme.customization.theme_style":"EXPRESSIVE","_applied_timestamp":1767225600000}
```

Nothing else is ever written: the color must be six hex digits, the style one of the six names, and the source `preset`
or `home_wallpaper`, whatever the page sends. The members that are kept are copied exactly as they were (only a flat JSON
object is merged; anything else is replaced, as the Tasker project did). Overlay names are single-quoted for the shell (a name
with a space, control character or leading `-` is refused, as are names over 300 characters), so nothing typed can run as a
command. Phones with Samsung's "wallpaper colors" switch (`wallpapertheme_state`, in whichever settings table already holds a
number for it) get it set to match, as the Tasker project did: off for a chosen color, on for the wallpaper. It is read
back like everything else: **Undo** puts it back to what it was, a theme that Android refuses puts it back at once, and a
switch that will not change is reported instead of hidden. **Default** leaves it alone.

> Careful: overlays change how Android and its apps look, and a few belong to the navigation bar or the system UI.
> Switching the wrong one can make the screen hard to use, and Android may restart apps when one changes. Every change is one
> tap from **Undo**, and what the phone reports after a change is what is shown. Whether a given overlay can be switched
> at all depends on the phone: many OEM overlays are fixed by the system, and some phones refuse changes from a shell.
> Overlays are listed and switched for the user the phone's shell defaults to (the owner, on nearly every phone).

## App menu

Tap **⋯** on any app for its version, install and update dates, any available update, sizes (APK, data,
cache; data and cache need *usage access*, which the app can grant through ADB/Shizuku/Root) and actions:
Launch, Force Stop, Freeze/Enable, Suspend/Unsuspend, Clear Data, Uninstall, Reinstall, Remove Updates, App
Info and **Extract APK** (a `.apk`, or an `.apks` bundle for split apps, saved to Downloads). **Uninstall**, **Clear Data** and
**Rem Updates** ask first. Ten tabs follow (the row scrolls sideways):

**Uninstalling a system app for one user without root** (`pm uninstall --user 0` answering "only root can delete
system app for a particular user"): the app automatically retries through a direct Binder call to
`IPackageManager.deletePackageAsUser`, run as a standalone `app_process` under whichever privileged shell (ADB or
Shizuku) is already active - the same technique App Manager and Canta use for the same refusal. This removes the
app for the current user; it stays in the system partition (the same result a normal uninstall gives for a
non-system app), and needs a working mode that isn't already Root (Root removes it directly, no fallback needed).

- **Permissions**: searchable, filterable; toggle runtime and development permissions
- **App Ops**: Allow / Foreground / Ignore / Deny / Reset per op, plus setting any op by name
- **Activities**: all activities (exported and unexported) with **Launch**, **Enable / Disable** and an exported filter. Unexported
  activities launch through ADB / Shizuku / Root; a launch that goes through just opens the activity, and Android's answer is
  shown only if it refuses
- **Components**: receivers, services (with **Stop**) and providers, each with its exported / enabled / permission state.
  **Enable / Disable** on an activity or component works in Root mode only: in every other mode (Read-Only, ADB, Wireless
  Debugging and Shizuku, Shizuku started with root included) the button carries a padlock, which here means "Needs Super User
  (Root)", and sends nothing to Android. A tap switches the working mode to Root when the phone has `su` (a no from `su` puts the
  mode you had back and offers the Root Features tab), else it offers that tab. Disabling a part of a system app asks to confirm first
- **Manifest**: decoded `AndroidManifest.xml` with search, copy, share and save to Downloads
- **Raw**: the full details JSON
- **Features**: the `uses-feature` list, each marked required or optional and *on this phone* or not (the OpenGL ES version is
  compared with the phone's); filters for Required, Optional and Not on this phone
- **Configurations**: `uses-configuration` (touch screen, keyboard, navigation, input features, OpenGL ES), the screen sizes and
  densities the app supports, minimum / target / compile Android version, hardware acceleration, large heap, and this phone's CPU types
- **Signatures**: the APK signature schemes present (v1 from META-INF, v2 / v3 / v3.1 from the APK Signing Block) and, for each signer
  (and the earlier certificates after a key rotation), the subject, issuer, serial number, validity, signature algorithm, public key and
  the MD5, SHA-1 and SHA-256 fingerprints (tap one to copy)
- **Libraries**: `uses-library` / `uses-static-library` / `uses-native-library` from the manifest, the shared library files linked
  into the app, and the native `.so` files of the base APK and its splits by CPU type

The four last tabs are read in one go by the bridge call `getAppExtras(pkg)` (`AppExtras.java` does the decoding; each part is read on
its own, so one that cannot be read does not blank the others).

**Trim Caches in All Applications** (a card of the **SD Maid** tab; it was in Settings before v7.10.10) runs `pm trim-caches 128G` through the working mode, off the page's
thread, and reports the free space of `/data` before and after. Only caches are cleared.

**Uninstalling on a connected device** follows the same steps: `pm uninstall --user 0`, then, for the root-only refusal, the helper
`assets/uninstall_runner.jar` (the `SystemlessUninstallRunner` classes only, built by `build.sh`) is pushed to `/data/local/tmp` of the
device, run with `app_process`, and deleted again (`DeviceUninstall.java`).

App rows show only **App Settings** and the **⋯ menu** (Force Stop lives in the menu and the batch sheet), so the list stays clean.

## App profiles

**🗂️ Profiles** (on the Applications tab) saves the apps you have disabled, suspended or uninstalled under a
name. Applying a profile first shows what will change (for example "disable 12, suspend 3, uninstall 8; 41
already match, 5 are not on this phone"), then runs it and records it in History so it can be undone.
**Share** sends the profile as a `.adbprofile.json` file; paste it into **Import** on another phone.

```json
{
  "format": "adb-app-manager-profile",
  "v": 1,
  "name": "Lean Samsung",
  "apps": [
    { "pkg": "com.facebook.appmanager", "name": "Facebook App Manager", "state": "disabled" },
    { "pkg": "com.netflix.partner.activation", "name": "Netflix Partner Activation", "state": "suspended" },
    { "pkg": "com.microsoft.skydrive", "name": "OneDrive", "state": "uninstalled" }
  ]
}
```

With nothing selected a profile covers every non-enabled app; with apps selected it covers only those.

**👁️ Watch a profile.** System updates often bring removed apps back. Tap **👁️ Watch** on one profile and the
app compares your phone with it every time it opens. When apps have come back, a banner on the Applications
tab says how many ("Your phone was updated. 3 apps from "Lean Samsung" no longer match it") and **Review**
opens the usual preview. After a reboot into a new system build a notification reminds you too (Android 13+ asks for
notification permission the first time). Nothing is applied automatically: removing apps always goes through
a review you confirm.

## Backup and restore

**💾 Backup** (batch menu, one app or several in turn) and **🗄️ Backups** (Applications tab) save an app as one `.adbbackup` file in
`Download/ADB App Manager/Backups/`. It is a zip holding:

| In the file | Needs |
|---|---|
| `backup.json`: name, version, signing key, granted permissions, changed app ops | any privileged mode |
| `apk/`: the base APK and all splits | any privileged mode |
| `data.tar`: the app's data folders (**optional**) | **Root** |

Restore installs the APK through ADB, Shizuku or Root (splits included), re-applies the permissions and app
ops, and, with Root, puts the data back. **Share** a backup, or **Choose backup file…** to restore one from
another phone. Before restoring it checks the signing key against the installed app and explains a version
downgrade or key mismatch instead of failing silently.

Private app data lives in `/data/user/0/<package>`, which only Root can read on current Android, so without
Root a backup is APK + settings. On Android 8.0–9 (API 26–28, before `MediaStore.Downloads` existed)
backups and extracted APKs are saved to this app's own storage instead of the public `Download/` folder,
and are removed if you uninstall it - keep a copy elsewhere before uninstalling on those versions. The data
scripts treat the backup file as untrusted: before touching
anything the restore refuses archives with paths outside the app's own folders, `..` steps, hard links or
links pointing elsewhere (checked in [`BackupScripts`](../src/com/bloatware/bingblop/BackupScripts.java)):

```sh
# excerpt of the generated restore script (runs through `su`)
tar -tf "$TAR" >/dev/null 2>&1 || { echo 'ERROR: the data archive is damaged'; exit 5; }
if tar -tf "$TAR" | grep -v -E '^(user/0|user_de/0)/com\.example\.app(/|$)' | grep -q .; then
  echo 'ERROR: the archive has files outside this app'; exit 6; fi
if tar -tvf "$TAR" | grep -q -E '^h'; then echo 'ERROR: the archive has hard links'; exit 6; fi
# ...only then: stop the app, clear its data folders, extract, chown to the app's uid, restorecon
```

## Quick Settings tiles and widget

Two tiles and one widget run **without opening the app**:

| | Does |
|---|---|
| **Working mode** tile / **🔄 Mode** widget button | Switches to the next mode that is ready (ADB TCP → Wireless Debugging → Shizuku → Root), or back to Automatic. The tile shows the current mode and is lit when the app last found a working mode usable: ADB TCP reachable (connected, or its port open), Wireless Debugging connected, Shizuku authorized or Root granted. It is dim with Automatic finding nothing, or Read-Only, and, after a new install or an update from 7.15 or older, until the app has been opened once. A tap that switches to Root asks no `su` (the root manager's question would come up behind the closing panel). So the tile stays dim after that tap, even where root works, until you open the app, which then asks; it is lit at once only when the app is still running and already has `su`'s yes |
| **Kill Apps** tile / **Kill Apps** widget button | Force-stops every app in your **quick list**, one after the other, with a notification that shows how far it is |

Choose the quick list in **Saved Lists** with **🛑 Quick list** on a card. To add the tiles, swipe down twice,
tap ✏️ and drag **Working mode** and **Kill Apps** into your panel. For the widget, long-press the home
screen → Widgets → ADB App Manager.

**The Kill Apps notification.** While the run goes on, a notification *Stopping apps* shows the place it has
reached (*3 of 12 · Chrome*), the list's name and a progress bar. It goes away when the run ends, and the usual
short message follows (*Force-stopped 5 apps*, with *, 1 failed* and *, 2 left alone* when they apply).

- **Cancel** stops the run after the app it is on, leaves the apps it did not reach as they are, and clears the
  notification (*Cancelled after 3 of 12 apps*).
- **Tapping the notification** opens the app on **Saved Lists** with the *Edit Saved List* sheet of the quick
  list. The run is not touched: it goes on while you look.
- On **Android 16 and newer** it asks to be a *Live Update* (a small chip in the status bar, *3/12*). That needs
  the **Live updates** row of the Permissions sheet (and Notifications); no command can switch it on, so its
  **Allow** asks for Notifications first and then opens the phone's Live updates screen for this app. Where the
  phone does not show a Live Update, you get the same notification without the chip.
- A second tap while a run goes on does not start another one.
- **Never stopped, whatever the list says:** this app, the phone's WebView app (the one that draws this
  app's window) and Media Picker (`com.google.android.providers.media.module`). They stay in the list and the
  message counts them as *left alone*.

Both go through `QuickActionActivity`, which is `MainActivity` without its UI, so the ADB, Shizuku and Root
code is shared instead of duplicated:

```java
public class QuickActionActivity extends MainActivity {
    @Override protected boolean isHeadless() { return true; }   // no WebView: run the action, show a Toast, finish
}
```

## Copy, share and search

- **Select and copy any text**: long-press app names, package names, versions, permissions, activities,
  manifest and terminal output. Buttons stay unselectable so taps work as usual.
- **Copy chips** for package, version and name in the app menu; **Copy Packages** for a
  batch selection.
- **Share sheet** (Android's own): package lists, the app-list CSV, a manifest, terminal output, profiles and
  backups. Files go through a private cache provider that grants one-off read access to the receiving
  app, so no storage permission is needed.
- **Find** in the manifest viewer and the terminal highlights every match, shows `2 / 7 matches`, and jumps
  with ▲ ▼. The manifest viewer can show only matching lines (with line numbers) or the whole file in context.

## Permissions

Three accesses have no pop-up of their own: Android keeps each one on a screen of its settings. The first time the app opens
(and once after the update that brought this sheet, if one of them is missing) a sheet offers them:

| Access | What the app uses it for |
|---|---|
| 📁 **All files access** | Browsing storage in the file manager, finding package files on it and installing from it |
| 📊 **Usage access** | Showing how big each app is and when it was last used |
| 🪟 **Display over other apps** | Opening another app's screen from the background, such as an app you have just installed |

**With a working mode (ADB, Shizuku or Root) active**, the same sheet also offers three more, privileged-only accesses -
there is no Android screen for any of them, so they only ever show up once there is a shell to grant them through:

| Access | What the app uses it for |
|---|---|
| 💾 **Read/Write External Storage** | The older storage permission some apps still check for |
| 🔐 **Write Secure Settings** | Changing a few protected system settings used by some advanced features |
| 🚫 **Access Restricted Settings** | Lifting Android's sideload block on a few sensitive toggles for this app |

**Allow** opens the Android screen for that access, or - for the three privileged-only ones - grants it straight through the
shell with nothing to open. **Allow all** walks through the ones still missing, one after the other (leaving a screen without
allowing does not stop the walk, and closing the sheet cancels the rest); with ADB, Shizuku or Root the app switches Usage
access, Display over other apps and the three privileged-only accesses on for itself, so only All files access ever needs its
own screen, and then restarts (a toast says "Restarting the app"; it closes and comes back by itself a moment later, in a fresh
process, so that what was granted holds everywhere). **Not now** skips them all: the same sheet is under **About → 🔐 Permissions** and shows what is allowed. On Android
10 and older there is no All-files switch, so **Allow** shows Android's own storage dialog (and, after "don't ask again", the
app's settings page); the app asks Android 10 for its legacy file access so that permission is enough there.

**When an action needs file access** and the app has neither it nor a working mode, the action says so and the app opens the
same sheet with just that access, naming what was being done. This covers opening, editing, saving or adding a file, reading
a package from storage, deleting a found package file, listing a storage folder you chose and the Installer's search for
package files. Once it is allowed the sheet closes and the action carries on by itself where that is safe: the folder is
listed again, the search starts again, the package is read again (an action asked for more than five minutes ago is left
alone). **Not now** cancels it; the failures that follow straight away (one action, many files) do not ask again, but a button
you press yourself (Go in the file manager, Find APKs) asks every time. The Files tab and the search the Installer starts when
it opens only show a note with a button. A failure on a path the access cannot help with (system folders, other apps' data) asks
for nothing, and a working mode reads storage without it.

## Themes

**Appearance:** Light, Dark, System (follows the phone), or Schedule (light/dark start times), plus optional
**pure black** in dark mode.

**Material 3** (default) and **Material You** (dynamic color from your wallpaper, Android 12+, follows
wallpaper changes) come first, followed by six classic palettes. Every palette has light and dark variants,
colors can be fine-tuned per mode, and everything is remembered between launches.

**Palette Style** (Settings, above the palettes): one **source color** (your wallpaper's, Material purple, or any color you pick) turned into a whole light and dark theme in one of ten
styles: Tonal Spot, Vibrant, Fidelity, Content, Neutral, Expressive, Fruit Salad, Rainbow, Monotone or CMF. The colors are worked out in the HCT color space with tonal palettes, as in Google's
[Material Color Utilities](https://github.com/material-foundation/material-color-utilities) (Apache-2.0); text stays readable for any source color.

**My Themes** (Settings, below Palette Style): save the look in use (palette or palette style, source color, color tweaks, Pure black) under a name, switch between up to 30 themes with a tap, rename, update or delete them;
Light / Dark / System stays as you set it.

**Screenshots in App Stores** open full screen when tapped: pinch / double tap / buttons to zoom, drag, swipe or arrows for the next one, Back to close.

**Saved lists backup** (Saved Applications tab, **Back up and restore**): back up to a folder you choose (or Download/ADB App Manager), share the file, switch on automatic backups into
`saved-lists-latest.json`, and restore from a file or from the folder, adding to your lists or replacing them (the Quick list follows).

**Haptic feedback** (Settings, on by default): a short tick on every tap of a button, tab, switch or row, with one switch to turn it off. **App Stores**: the **+** tab adds a store of your own (an F-Droid style repository, or a GitHub or Codeberg user, organization or project; the tab is named by you or from the address), and every store's apps now open a detail sheet with the full description and screenshots (it was ShizuStore only). **Task Manager**: **Apps first** in the Processes list. **SD Maid** (the tab's new name) now holds **Trim Caches** (it was in Settings), and AppCleaner asks the phone whether the accessibility service is ready when you Delete and has **Clear the rest with accessibility**. The gear wears the app's colours, and the list of fonts found is emptied when Settings is left.

**Saved devices** (Connected Devices, under the picker): every device that was connected, up to 20, with **Connect** and **Delete** (asked first), **Delete all**, and a switch to stop remembering new ones.

**Logcat entries** open when tapped, in a window with **Copy**, **Ask agent** (the default agent explains the entry; nothing is sent until you tap it) and **Web search**. **Hidden Settings** have **Web search** and **Ask agent** in the editor and 150 more settings described.
The Task Manager's **CPU** tab has the °F / °C menu, and the **GPU** tab starts with the renderer card. In the Apps tab the boxes at the top wear their own colours, and the **Bloatware (Uninstalled)** box clears the other filters.

**Morphe Helper downloads** keep going when the app is left: a foreground service with a notification (progress, speed, time left, **Pause all**, **Cancel**) and a switch to turn it off.

**Reconnecting devices** (Settings): the switch for reconnecting a dropped device on its own and the **retry timings** (seconds before each attempt, `0, 8, 25` by default); the Devices tab
has **Reconnect all** when two or more devices are not connected.

**Private DNS** moved to the **Extra Settings** tab in v7.12.12 (see [Extra Settings: Private DNS](#extra-settings-private-dns)); the gear Settings page only has a pointer card with an **Open Extra Settings** button.

**Motion:** the **Expressive Animations** switch (Settings, in its own card right below the theme cards; on by default) gives the
app's transitions Material 3 Expressive's springier, overshooting easing instead of a plain ease. Progress bars are left at a
plain ease so they never visibly pass 100%, and when the phone's own *reduce motion* setting is on the app follows it, whatever
the switch says.

## How it works

The UI is one HTML file rendered in a `WebView`; a small Java bridge turns taps into package-manager
commands and runs them through whichever backend is active. There is no Gradle: `build.sh` drives `aapt2`,
`javac`, `d8` and an APK signer directly, so the whole app builds in Termux.

**Actions are plain `pm` / `am` commands** ([`MainActivity.java`](../src/com/bloatware/bingblop/MainActivity.java)):

```java
@JavascriptInterface
public String executeAppAction(String action, String pkg) {
    // Every real caller passes a PackageManager-sourced name, but a saved list is free text the user
    // typed and a Quick Settings tile runs it with no review - so this is checked before anything below
    // builds a shell command out of it.
    if (!BackupScripts.isPackageName(pkg)) return "Error: \"" + pkg + "\" is not a valid package name";
    if ("freeze".equals(action))      return executeShell("pm disable-user " + pkg);
    if ("suspend".equals(action))     return executeShell("pm suspend " + pkg);
    if ("force_stop".equals(action))  return executeShell("am force-stop " + pkg);
    if ("uninstall".equals(action))   return executeShell("pm uninstall --user 0 " + pkg);
    if ("reinstall".equals(action))   return executeShell("pm install-existing " + pkg);
    // ...
}
```

**Shizuku** runs commands in its own process through its remote-process API, with the `rish` shell as a
fallback:

```java
Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
m.setAccessible(true);
Process p = (Process) m.invoke(null, new String[]{"sh", "-c", "exec 2>&1; " + cmd}, null, null);
```

**A private ADB key per install.** [`AdbKeyManager`](../src/com/bloatware/bingblop/AdbKeyManager.java) generates a
2048-bit RSA key and writes the public half in adb's own binary format (little-endian modulus, `n0inv`, and
`R² mod n`), checked byte-for-byte against `adb`:

```java
static byte[] encodePublicKey(BigInteger n, BigInteger e) {
    BigInteger r32 = BigInteger.ONE.shiftLeft(32);
    BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
    BigInteger rr = BigInteger.ONE.shiftLeft(KEY_BITS).pow(2).mod(n);
    ByteBuffer bb = ByteBuffer.allocate(4 + 4 + MODULUS_BYTES * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
    bb.putInt(MODULUS_BYTES / 4);  bb.putInt(n0inv.intValue());
    bb.put(toLittleEndian(n));     bb.put(toLittleEndian(rr));
    bb.putInt(e.intValue());
    return bb.array();
}
```

**Version comparison** for updates reads the first dotted number anywhere in the string, so `v1.2.3`,
`release-1.2.3` and `app_1.2.3-beta` all compare correctly
([`UpdateManager`](../src/com/bloatware/bingblop/UpdateManager.java)):

```java
Matcher m = Pattern.compile("([0-9]+(?:\\.[0-9]+)*)").matcher(v.trim());
```

**Sharing without a support library**: [`ShareProvider`](../src/com/bloatware/bingblop/ShareProvider.java) is a
tiny non-exported `ContentProvider` that serves `cache/share/` to the share sheet with one-off URI grants.

**The UI talks to Java through one object** and Java calls back with `evaluateJavascript`:

```js
const err = window.AndroidBridge.shareTextFile('app_list.csv', csv, 'text/csv');   // JS → Java
// Java → JS: window.onApkExtracted(json)
```

**What's new needs no extra file**: `build.sh` copies `CHANGELOG.md` into the APK's assets, and the app renders
it, so the in-app notes always match the release.

Also in the sources: [`ManifestDecoder`](../src/com/bloatware/bingblop/ManifestDecoder.java) turns the binary
`AndroidManifest.xml` inside an APK back into readable XML.

## Privacy &amp; security

- **No analytics, no account, no server of ours.** The app only makes requests you trigger: the UAD-NG list,
  update lookups (Galaxy Store, GitHub, Codeberg, F-Droid, IzzyOnDroid, the Obtainium catalog), the Store catalogs you
  open, downloads you start and, only if you add your own API key, VirusTotal lookups. **☕ Buy me a coffee** just opens
  PayPal in your browser.
- The ADB key is created on your phone and never leaves it. The APK contains no key.
- **Coding agents** only talk to the provider you pick, with the key you saved (encrypted with an Android Keystore key, sent
  only to that provider's own address). What you type, and the output of the steps you allow, goes to that provider; keys
  in output are masked first. Nothing runs or changes without your OK unless you turn that off in the Terminal settings.
- The special accesses are yours to give: the sheet only opens Android's own screens, or - for the three privileged-only
  ones (Read/Write External Storage, Write Secure Settings, Access Restricted Settings) - grants them through the shell
  directly, since none of them has a screen of its own. With a working mode the app can also switch Usage access and
  Display over other apps on for itself (the same shell could do it anyway); All files access is only ever given on
  Android's screen. Every one can be taken back there.
- Updates are installed only after the package name, version and **signing certificate** match.
- The reboot receiver only compares the build fingerprint and posts a reminder. It changes nothing.
- Backup files can hold app data; they are plain files in your Downloads folder, so treat them like the data
  they contain. Restoring data validates the archive first (see above).
- Powerful actions (uninstall, disable, clear data, changing Android settings) always go through a privileged mode
  you set up yourself. Read-Only mode can inspect but not change anything. The Hidden Settings tab only talks to the phone's
  own `settings` command, and the Overlays tab to `cmd overlay` and the one theme setting; the log of changes stays on the phone.
- The release keystore is git-ignored. Never commit one to a public repository.

## Building

### In Termux

```bash
pkg install aapt2 apksigner d8 ecj zipalign openjdk-17 python git
git clone https://github.com/Bingblop/ADB-Application-Manager
cd ADB-Application-Manager
./build.sh
```

The APK is written to `bin/ADB_Application_Manager_Pro.apk` and copied to `Download/` when Termux has storage
access (`termux-setup-storage`).

### Signing and updating in place

Android only installs an update over an existing install when both APKs are signed with the same key.
`build.sh` signs with `./release.keystore` (alias `adbmanager`, password `password`). Copy the keystore you
used before into the repo folder to update without uninstalling.

### On GitHub (automatic)

The **Build APK** workflow builds every push and pull request; download the APK from the run's **Artifacts**.
To sign it with your release key, add repository secrets under Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | output of `base64 -w0 release.keystore` |
| `KEYSTORE_PASSWORD` | your keystore password (`password` if you used build.sh's default) |
| `KEY_ALIAS` | optional, defaults to `adbmanager` |

Without the secrets, builds use a throwaway test key and are named `-test-signed`.

The **Build, sign and publish release** workflow (Actions → Build, sign and publish release → Run workflow → enter the
version, for example `5.9`) creates a GitHub Release with the signed APK and its `SHA256SUMS.txt` attached; its notes
are the matching section of [CHANGELOG.md](../CHANGELOG.md). Pushing a `v*` tag does the same.

### On Linux without an Android SDK

Every tool can be overridden with environment variables:

```bash
AAPT2=/path/to/aapt2 \
ANDROID_JAR=/path/to/android.jar \              # resources for aapt2 -I
BOOTCLASSPATH=/path/to/android-classes.jar \
D8_JAR=/path/to/r8.jar \                        # or have `d8` on PATH
UBER_SIGNER_JAR=/path/to/uber-apk-signer.jar \  # or have zipalign + apksigner on PATH
KEYSTORE=/path/to/release.keystore \
./build.sh
```

## Project layout

```
AndroidManifest.xml            App manifest (Shizuku provider, share provider, tiles, widget, pairing and boot receivers)
src/com/bloatware/bingblop/
  MainActivity                 The activity, the JavaScript bridge (AndroidBridge) and the ADB / Wireless Debugging /
                               Shizuku / Root backends
  AdbKeyManager, AdbPair,      The per-install ADB key, Wi-Fi pairing and the "pair from a notification" reply
  PairReceiver
  QuickActions, QuickActionActivity, ModeTileService, StopListTileService, QuickWidgetProvider, BootReceiver
                               Quick Settings tiles, the home-screen widget and the after-update reminder
  BackupScripts                Backup and restore shell scripts, and the package-name check every command goes through
  SettingsDb                   The Hidden Settings tab's rules: names, values, the commands and their read-back
  OverlayRules                 The Overlays tab's rules: reading `cmd overlay list`, overlay names, the theme value, the read-back
  FileRules, InstallHints      File-manager path rules; reading what `pm install` answered
  ZipTool                      The archive engine behind the file manager's package browser
  ApkSigner, SigningKey        Signing edited APKs on the device (Android Keystore key)
  RishShell                    The persistent Rish shell in the terminal
  ManifestDecoder              Binary AndroidManifest.xml back to readable XML
  ApkScan, ApkTrash, XapkInfo  The Installer's storage search (with progress), the rules for deleting a found file with Undo, and XAPK reading
  SplitInfo                    Which CPU / density / language / feature a split APK is for (read from its manifest)
  VirusTotal                   The optional VirusTotal lookup
  ModDetect                    Patched / repackaged app detection
  UpdateManager, FdroidIndex,  Update checks and the Store catalogs (ShizuStore, GitHub, F-Droid, Orion)
  ShizuStore, KomiApi, Stores
  ShareProvider                Share-sheet files without a storage permission
assets/index.html              The whole UI (HTML/CSS/JS, rendered in a WebView)
assets/libadb.so               arm64 adb client for ADB TCP / Wireless Debugging
assets/rish, rish_shizuku.dex  Shizuku shell fallback
res/                           Launcher icons, widget layout, tile and notification icons, strings
libs/                          Shizuku API 13.1.5 (api, provider, shared, aidl); RE2J 1.8 (linear-time
                               regex for untrusted, network-supplied patterns, see UpdateManager);
                               Apache Commons Compress 1.27.1 + Commons IO 2.16.1 + Commons Lang3 3.16.0
                               + Tukaani XZ 1.10 + SLF4J API 1.7.36 (7z and the tar family, see ArchiveIo);
                               junrar 7.5.5 (RAR 1.5–4.x, see RarReader); Aircompressor 0.27 (pure-Java
                               zstd, see ArchiveIo)
screenshots/              Images and the debloat-flow animation used in this README
tests/                         The UI scripts and Java suites the app is checked with, and how to run them (tests/README.md)
build.sh                       Build script (Termux or Linux)
release/                       Signed APKs up to v5.1 and SHA256SUMS.txt (newer ones are on the Releases page)
.github/workflows/             build.yml (CI) and release.yml (publish a GitHub Release)
CHANGELOG.md                   Release notes; the app shows them in What's new
```

## Testing

Checking the new features on a real phone: [DEVICE-TEST-CHECKLIST.md](DEVICE-TEST-CHECKLIST.md).

What is checked before each release, and what is not. The checks live in [`tests/`](../tests/) and can be re-run from a clone
(`cd tests && npm install && npx playwright install chromium && node run.js && node java/run.js`; what each needs and how a
script passes is in [tests/README.md](../tests/README.md)).

- **The UI** (`tests/run.js`, about four minutes): 100 headless-Chromium scripts drive the real `assets/index.html` against a mock Android bridge:
  every tab, theme, filter, share / copy / find action, profiles and the drift banner, backups, the Installer, the
  Store, the file manager and archive browser, the terminal, About, the Back button, the height of the app menu and, for the Hidden Settings tab, the
  list, search, filters and sort, tap-to-edit, press-and-hold flipping (with touch events of any hold length), creating,
  deleting, Undo, the change log, refused, unanswered and late requests, a 720-row table, and the layout at 320 and 360 px in
  light and dark. For the Overlays tab: the theme editor (hex, sliders, the 657 presets, styles), apply, reset and Undo, an
  app restart in the middle of a change, the palette redrawing, the overlay list (131 sample overlays, awkward names,
  search, filters, press and hold with a touch screen, a refused, fixed-on, unavailable, unanswered or late change), a
  phone without Material You, and both halves at 320 and 360 px in light and dark. For v6.1: the permission sheets (first
  launch, About, and the prompt an action raises, with the file manager, the storage search and the reading of a package each
  carrying on once the access arrives, an old or declined request left alone, and a working mode that grants two of the three on the
  spot), the Installer's result-dialog buttons at three screen sizes, the search's progress bar, press-and-hold delete with Undo
  (touch included), the scroll to the package, the splits that fit the phone (CPU order, density rounding, language aliases and regions), both lists of common
  installers and requesters, the lit counters and the tip under Export. For v7.0: the tab bar built from one registry (names, two lines, order, the
  update count on App Updater), the header gear and Settings, the Feature List (switches, arrows, Reset to Default, a saved choice from an older
  version, a damaged one, a tab that is off reached by a link or by Back, the layout at 320, 360 and 412 px), the search bar and its menu
  (placement, the three options, the swap rule, patterns and plain text, exact matches, kept between launches, closing by tap, Escape and Back, three
  screen sizes) and a scan of the sources and of every tab and sheet for emoji. For v7.8: the Terminal (commands in a real shell,
  history, extra keys, TAB completion, colours and progress lines, one screen per shell, every shell that cannot start) and
  the coding agents against recorded provider streams (key tests, approvals, files written and edited for real, undo, STOP,
  errors, switching agents mid-chat, the setup sheets, sign-in tools, Cursor's cloud agents). For v7.9: the batch, Dex optimization
and profile progress bars with Stop, the Always allow card for file changes, Add storage, Device Specs, the Termux sync
(the switch, the button, and the option reaching the native side), and Expressive Animations (the curve actually changing on a
real transition, the progress bars staying plain, the phone's reduce-motion setting winning, and the choice surviving a relaunch).
- **Native rules, off the device** (`tests/java/run.js`, 36 suites): the parts of the Java that need no Android classes are compiled
  and run as plain Java. For the Hidden Settings tab that is 311 checks of `SettingsDb`, including round trips through a real `sh` (and a fake
  `settings` that refuses on purpose) for hostile values (quotes, `;`, `$(...)`, backticks, `>`, newlines, Unicode), and a
  comparison of the name, value and size checks in Java and in the page over 1,322 cases (the color check of the Overlays tab
  included). For the Overlays tab that is 551 checks of `OverlayRules`: `cmd overlay list` output in the shapes different Android
  versions print, hostile overlay names and theme values run through a real `sh` (with a fake `cmd` and `settings`), every
  verdict for a refused, fixed-on, vanished or unanswered change, the exact theme value and its merge into what the setting
  holds, and every step of an Apply, Undo, Default and refusal against a fake phone (the Samsung switch included). The same
  approach covers the file-manager path rules, the install-answer reader, the package-file scan and XAPK paths, the F-Droid and
  GitHub catalog parsers (against real indexes and feeds), the manifest decoder (against every compiled XML file of a real APK),
  the zip engine (two suites, with generated bad, truncated, encrypted and 4 GB archives), the 7z / tar family and rar engines
  (checked against 7-Zip, py7zr, the system archive tools, and real RAR4/RAR5 archives from two upstream projects' own test
  suites), the Rish shell (one suite against
  a real `mksh` with `toybox`) and the APK signer (v2 signatures cross-checked with `apksigner`). For v7.5, `CpuStats`, `MemStats`,
  `NetStats`, `ProcStats` and `GpuStats` (293 checks across five suites) parse real `/proc/stat`, `/proc/meminfo`, `/proc/net/dev` and
  `ps` output (toybox and busybox shapes, and the kernel/native rows a package name cannot be read from), and every vendor sysfs path
  the GPU reading tries. For v6.1, 133 checks of `ApkTrash` (which
  paths may be deleted, where a file waits and how it comes back, and the search's progress against a real folder tree) and 17 of
  `SplitInfo` (what a split's manifest says about it). The backup scripts and the regex handling of the update checks have no suite of
  their own yet.
- **The build**: `build.sh` compiles, signs and verifies every APK (zipalign, v2 / v3 signatures), and each published
  release is downloaded again and checked: SHA-256, signing certificate, version and alignment.
- **Reviews**: each release's new code gets separate, independent review passes (automated ones, not a human), and
  what they found was fixed and re-tested before shipping. The Hidden Settings tab had two (native side: 9 findings, page: 17) and
  so did the Overlays tab (native side: 7 findings, then 8 more in a second look at what the first fixes added; page: 11);
  each finding was checked against the code and fixed, except that the sheets still do not trap keyboard focus, like the
  app's others, and that overlays are switched for the phone's main user (`--user current` could not be tried on a real phone).
  The v6.1 code had three (native side: 8 findings, page: 13, and a second look at the native fixes: 4 more), all checked and fixed
  with a test that fails when the fix is taken out, except that a command a working mode never answers is bounded only by that
  mode's own ten-minute limit, and that a file deleted on an SD card that is removed before the app next starts leaves its hidden
  trash folder on the card.
- **Not tested on hardware**: the parts that only exist on a phone (the ADB, Shizuku and Root backends, the Quick
  Settings tiles, the widget, the share sheet, the boot notification, the real `settings` and `cmd overlay` commands and the
  Material You engine on your phone's Android version and skin) are checked by compilation, review and these simulations, not on a device. If something
  misbehaves on yours, please [open an issue](https://github.com/Bingblop/ADB-Application-Manager/issues).

## Credits

Debloat data from [UAD-NG](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation)
(GPL-3.0, downloaded at runtime). Shell access via [Shizuku](https://github.com/RikkaApps/Shizuku-API).
Update sources: Galaxy Store, GitHub, Codeberg, [F-Droid](https://f-droid.org),
[IzzyOnDroid](https://apt.izzysoft.de/fdroid) and the [Obtainium](https://github.com/ImranR98/Obtainium)
community catalog. The Task Manager tab is modeled on
[RohitKushvaha01/TaskManager](https://github.com/RohitKushvaha01/TaskManager).

Changes by version: [CHANGELOG.md](../CHANGELOG.md).

**Overlays, ordered and explained** (RRO/Monet Customization > Overlays, v7.10.11): the list is in three parts, **Enabled**, **Disabled**, **Installed, not changeable**, each with its count and, inside it, the overlays grouped under their target. Every row has a very short description of what the overlay is for (a best guess from its name and target).

**Command suggestions** (Terminal tab and the ADB Shell of the Console tab, v7.10.11): a grey completion line above the input offers the rest of what you type, from your history first, then the packages on the phone, Hidden Settings keys and common commands. **Right arrow** at the end of the line accepts it; a **tap on the line** does too. It never runs anything.

**Issues and Contact the developer** (top of the About card, v7.10.11): **Issues** opens the GitHub issue page. **Contact the developer** opens a sheet to write a message (with or without the app, Android and phone details) and opens your email app with the subject **ADB App Manager**, addressed to a forwarding alias so the developer's own address is not shown or stored in the app.

**Batch results read back from the phone** (v7.10.12): after Uninstall, Uninstall (keep data), Reinstall, Freeze and Unfreeze the batch asks the package manager (`pm list packages --user 0`, `-d` for frozen apps) what became of each app, and the Batch Results tags are **Uninstalled / Still installed**, **Installed / Not installed**, **Frozen / Not frozen**, **Enabled / Still frozen**. A command that reported a failure for an app that is gone counts as a success, with a note on its card. If the phone cannot be asked, the command's own result is used.

**One app, read back too** (v7.10.13): the app menu's Uninstall, Reinstall, Freeze, Enable, Suspend and Unsuspend run natively, then the phone is asked (package lists; for Suspend the app's own flag in `dumpsys package`) and the message says what was found. A batch shows **Checking the phone…** on its progress sheet first. Clear data and Force stop are not read back (nothing to read). The **Connected Devices console** now suggests commands like the Terminal does, including adb's own commands in adb mode.

**Read-back everywhere** (v7.10.14): the Connected Devices app list lists the device again after Enable, Freeze (Disable until v7.13.0), Uninstall and Reinstall and reports what it finds (*Checking the device…*); Clear data is read back in Root mode by counting the files and size of the app's `/data/user/0/<package>` folder before and after; **Settings > Progress messages** switches off the "Working on it… / Checking…" toasts (the result and every problem stay).

**UAD-NG tag** (v7.10.15): tapping it opens the app's classification (level, group, the project's description, needed by / depends on). What the four levels mean is behind the **Removal Levels** button at the bottom of that sheet (the same lines as in the Debloater tab).

**Clear Data from Uninstalled Apps** (SD Maid tab, above Trim Caches, v7.10.16): lists the apps that are uninstalled (in Root mode only those with a data folder left, with sizes), you tick and confirm, and each is cleared with `pm clear`, else (Root) by deleting its data folders, else by bringing it back with `pm install-existing` and removing it again without keeping the data. **Batch menu** (v7.10.16): grab handle (up = taller, down = smaller, down again = closes to the checkmark button), outlined **Select All** / **Clear All**, **Show Applications**, **Share List** in the grid, **Keep selection after running** on every time; leaving the Apps tab drops the selection; **Running** turns **Uninstalled** off; package names in a softer theme colour. **VirusTotal API key** (Settings): one key, tested once, used by the Installer and Morphe Helper.

**Force stop and Clear data read back** (v7.10.17): Force stop shows *Stopped* / *Still running* from the process list; Clear data counts files (private folder in Root mode, the app's `Android/data` folder in ADB and Shizuku modes, twice, to see files that come back). A row shows a spinner and *Working…* / *Checking…* while its app is changed and checked. The opened row of the Debloater list has a **Removal Levels** button. Morphe Helper reads APKMirror's variants table (APK and BUNDLE rows) and, when the browser check blocks the variant page, opens that variant in the in-app browser.

**Select All / Clear All, one app's leftover data, VirusTotal quota** (v7.10.18): the Selected apps list and the Clear Data from Uninstalled Apps sheet have outlined **Select All** / **Clear All** buttons (so has **New List**). In the Uninstalled filter each row has a bin button that clears the data that one app left behind (asks first; Root mode names the size). The VirusTotal card in Settings (and the Installer's) shows *N of 500 lookups left today*, read from VirusTotal.

**Trackers** (v7.11.0): the Apps tab has a **Trackers** pill and the app menu a **TRACKERS** chip (green 0, orange N, grey ?) that opens the list of the tracker libraries found in the app's code, from the Exodus Privacy list (ODbL 1.0). It reads class names offline, keeps the result until the app updates, and cannot see renamed (R8/ProGuard) or later-downloaded code.

**Default agent and Ask agent buttons** (v7.12.0): Settings has a **Default agent** card under Language; that agent (key and model from the Terminal tab) answers every **Ask agent** button: on each Hidden Setting (rows and editor), the app menu's permission / app op / activity / service / receiver / provider / feature / library lists, overlays, Task Manager processes, Debloater rows without a description, trackers, what SD Maid found and log entries. The box shows exactly what is sent and asks at once; with no default agent (or one that is not connected) a built-in, key-free web lookup answers instead (it searches for the entry's name only and shows what the pages say; v7.12.6). Crawl4AI and Browser Use (free-tier keys from their hosted clouds) can be chosen as the default agent too, as can Ollama or any own server (**Own server (OpenAI-compatible)**), so the questions can stay on your network. **Row tints** in Settings (Off, Subtle, Normal) sets how strongly Apps rows are tinted; the Ask agent window has a progress bar and a Stop button (so do the log-entry and Hidden Settings windows) and a **History** of the last 12 answers; **Test the default agent** in Settings checks the chosen agent with one sample question (v7.12.7). For an app the UAD-NG list does not know, an **Ask agent** button stands where the classification chip would be ("Is it safe to disable?"); the chip itself now shows only the level.

**Logcat** (v7.12.0): the view keeps its lines (a poll that finds nothing no longer empties it; Error-only views no longer lose their lines after a second). **Record to file** writes the log to a file in the background; **Recordings** lists them and opens one as a still picture (search, level key, pages, Copy shown, Save, Share, Delete). **SD Maid**: the two data cards come after the four tools.

**Hidden Settings back up and restore, Update the tracker list** (v7.11.1): the Changes sheet has **Back up changes** (a JSON file with each changed setting's current and earlier value) and **Restore…** (checks the file, asks, puts the values back, every one into the history). The Trackers box has **Update the list**, which downloads the current Exodus Privacy list after checking it is whole.

**APK Installer/Updater, Find and download an app, KeyStore, Default Ask Agent, Authorization Manager** (v7.12.8): the APK Installer tab is now **APK Installer/Updater** with two boxes at its top, **Application Installer** (left, the page the tab always opens on; the old APK Installer) and **Application Updater** (right); the **App Stores** tab keeps its name and holds only the stores. The tab label and the Updater box show the number of waiting updates in brackets. Cards of the Updater box, in order: **Find and download an app** (search by name or package; sources APKMirror (default), Uptodown, Aptoide, F-Droid and IzzyOnDroid, which download inside the app (F-Droid's file is F-Droid's own signed build; neither site publishes a checksum, so the file is checked to be a real package of the asked name and version), plus Google Play, which only opens its page; a Version box; Allow split APK, Exact version only, Allow closest nearby version, Skip alpha and beta builds, VirusTotal scan, Install on download, Use the Default Ask Agent to search as well; save to the app's own storage, Downloads/App Updater or a folder you choose; the file must be a real Android package, for the asked package and version), **App update**, **Updates Available**, **Play Store apps (via Aurora)**, the update rows, and **KeyStore** (**Export KeyStore** to `Downloads/Morphe Patcher/morphe.keystore`, **Import KeyStore**, **New key**; the key is the one that signs apps patched in the Morphe Patcher tab). The Settings card **Default agent** is now the **Default Ask Agent**: a research agent that answers every Ask agent button and the Updater search; Perplexity until you choose (**Exa**, a new research agent with a key from dashboard.exa.ai/api-keys, sits right after it), the built-in web lookup until its key is added, and no coding agents in the list. **Authorization Manager** (About tab): the app has its own authorization code of 25 letters and digits (five groups of five), made on the phone and kept in the app's private storage; the About tab shows it with a copy button and a refresh button (asks first, then replaces the code). Nothing accepts the code yet: it is kept for later. See the Help Guide and the [CHANGELOG](../CHANGELOG.md).

**The install question on every path, and no privileged authorizer in Read-Only mode** (going into v7.12.11; not run on a phone). The question "Install this app?" that v7.12.10 asked before a downloaded app was installed through ADB, Shizuku or Root now comes before every privileged install the page can start: the **Install** button of the Application Installer (the source is "a file on this phone" and its name, not a web host), the **Update** button of the Application Updater (after the download, with the host the file came from; **Update All** asks once, before any download, with a list of the apps, at most eight named), the App Stores' **Install**, restoring a backup, and the Morphe Patcher's **Install** and **Install when finished**. What the app read from the file itself comes first (source, mode, new or replacing, what else happens, such as uninstalling the installed copy first); the package, version, file name and install options come after it, in quotation marks and labelled "not checked". Only the dialog's own button says yes; Cancel, Back, a tap outside, two minutes without an answer or closing the app mean no. The app's own self-update does not ask: it fetches only from its own release page and installs only a newer build of this app signed with this install's key. In Read-Only mode (no working mode) the app no longer honours ADB, Shizuku or Root as the Installer's Authorizer, even when one is still connected: the install goes through Android's own installer, which asks you, and the page offers only **No privilege** with a line that says why. The Authorizer also follows the working mode when it changes until you pick one yourself (before, it stayed where the first visit left it). Installing from the File Manager and restoring a backup need a privileged working mode and say so otherwise. See the Help Guide, section "The install question".

**ShizuStore rows have an Install button** (going into v7.12.12; not run on a phone). In App Stores > ShizuStore every row now has the same **Install** button on its right as the GitHub, F-Droid and Orion rows, in the same colors (black or white text over the accent-to-secondary gradient). It installs without opening the detail sheet: the app fetches the details of that app (once, then keeps them), takes the same download the sheet would offer and starts the same install, so in ADB, Shizuku and Root mode the question "Install this app?" comes first. The row shows "Loading...", "Starting...", the progress and the end ("Installed ..." or "Install failed: ...") under its description, and the button is off while it works; a second tap is ignored, and searching, sorting or "Show more" meanwhile does not lose the line or start a second install. An app with no direct APK (or only on the Play Store) says so on the row and the button reads **No APK**; details that cannot be loaded are told on the row and the button is back. Progress of an install no longer appears in a detail sheet that is open on another app.
