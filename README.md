<div align="center">

# ⚡ ADB Application Manager Pro

**Manage, debloat, update and inspect every app on your Android phone — straight from the phone.**

Freeze · Suspend · Uninstall · Debloat · Update · Back up &amp; restore · Inspect manifests · Launch hidden activities
through **ADB over TCP, Wireless Debugging, Shizuku or Root**.

[![Build APK](https://github.com/Bingblop/ADB-Application-Manager/actions/workflows/build.yml/badge.svg)](https://github.com/Bingblop/ADB-Application-Manager/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/Bingblop/ADB-Application-Manager?label=release)](https://github.com/Bingblop/ADB-Application-Manager/releases/latest)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)

### [⬇️ Download the latest APK](https://github.com/Bingblop/ADB-Application-Manager/releases/latest)

`com.bloatware.bingblop` · v5.8-Pro · signed APK, installs over every earlier version without uninstalling

<table>
  <tr>
    <td><img src="docs/screenshots/apps-dark.png" width="210" alt="Application list"></td>
    <td><img src="docs/screenshots/app-menu.png" width="210" alt="App menu"></td>
    <td><img src="docs/screenshots/debloat-flow.gif" width="210" alt="Debloating in seven steps"></td>
  </tr>
  <tr>
    <td align="center"><sub>App list: versions, update hints, sorting</sub></td>
    <td align="center"><sub>App menu: sizes, dates, copy, share, backup</sub></td>
    <td align="center"><sub>Debloat, review, log and undo</sub></td>
  </tr>
</table>

</div>

---

## Contents

[Download](#download--install) · [Features](#features) · [Screenshots](#screenshots) · [Working modes](#working-modes) ·
[Debloater](#debloater) · [Updates](#updates) · [App menu](#app-menu) · [Profiles](#app-profiles) ·
[Backup &amp; restore](#backup-and-restore) · [Quick tiles &amp; widget](#quick-settings-tiles-and-widget) ·
[Copy, share &amp; search](#copy-share-and-search) · [Themes](#themes) · [How it works](#how-it-works) ·
[Building](#building) · [Project layout](#project-layout) · [Privacy &amp; security](#privacy--security)

## Download &amp; install

1. Open the [**Releases** page](https://github.com/Bingblop/ADB-Application-Manager/releases/latest) and download
   the signed APK there (every version is also in [`release/`](release/), with
   [`SHA256SUMS.txt`](release/SHA256SUMS.txt)).
2. Allow installing from your browser or file manager when Android asks, then open the APK.
3. Pick a [working mode](#working-modes). The easiest is **ADB over TCP**: run `adb tcpip 5555` once from a
   computer, or use Wireless Debugging (Android 11+) with the in-app pairing flow.

Check the download:

```bash
sha256sum -c SHA256SUMS.txt --ignore-missing
```

Updating: install the new APK over the old one. All releases are signed with the same key. The app can also
tell you when a new version is out (see [Updates](#updates)).

## Features

| | |
|---|---|
| **Apps** | Browse every package, including ones uninstalled for your user · search · sort by name, update date, install date, size or "updates first" · filters for running, 3rd party, system, frozen, suspended, uninstalled and **updated in the last 7 days** · versions and update hints in the list · select many and run batch actions · save selections as named lists · export everything to CSV |
| **Actions** | **App menu (one app):** Launch · Force Stop · Freeze / Enable · **Suspend / Unsuspend** · Clear Data · Uninstall for user 0 · Reinstall removed system apps · Remove Updates · App Info · **Extract APK** · **Share APK** · **Backup**. **Batch (selected apps):** Freeze · Enable · Force Stop · Suspend · Unsuspend · Clear Data · Uninstall · Reinstall · Save to List · Copy Packages · Share List |
| **Debloater** | The [UAD-NG](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation) community list (5,000+ packages) with descriptions and dependency warnings · filter by removal level, vendor list, state and **phone brand** · review step before anything runs · **history log with one-tap Undo** |
| **Updates** | **Update the app itself** from its own GitHub releases (dedicated card, signed-key check, seamless with a privileged mode or the system installer without one) · **Galaxy Store** (Samsung system apps) · **GitHub, Codeberg, F-Droid, IzzyOnDroid and the Obtainium catalog** for sideloaded open-source apps · import your **Obtainium** export · Update one or **Update All** · signing-key check before installing |
| **Inspector** | Permissions and App Ops as separate lists · **activities, services, broadcast receivers and content providers**, each with its exported / enabled / permission state, and any component can be **enabled or disabled** · unexported activities are **launchable through ADB / Shizuku / Root** · decoded **AndroidManifest.xml** viewer · version, install and update dates · APK, data and cache sizes |
| **Profiles** | Save which apps are disabled, suspended or uninstalled; re-apply on this phone or **share to another phone** · **👁️ Watch** a profile to be told when its apps come back after a system update |
| **Backup &amp; restore** | **💾 Backup** an app's APK (with splits), permissions and app ops in any privileged mode, plus its **data with Root** · restore through ADB / Shizuku / Root · share backups or pick one from another phone |
| **Quick actions** | **Quick Settings tiles** and a **home-screen widget** to switch the working mode and force-stop a list of apps without opening the app |
| **What's new** | The changelog is inside the app: it opens once after an update, and from Color &amp; Themes → About |
| **Productivity** | Select and copy any text · copy buttons for package, version and name · **share sheet** for package lists, CSV, manifest, terminal output and APKs · **search with highlight and next/previous** in the manifest viewer and terminal · remembered filters and sort |
| **Terminal** | Run shell commands through the active mode (off the page's thread), with output search, copy and share · **🕘 history, ⭐ saved scripts and pinned chips** · **🐚 Rish mode**: a persistent Shizuku shell where `cd` and `export` stick, with a STOP that ends a command and its children · a searchable **📋 ADB cheat sheet** of ~90 commands you tap to drop into the input |
| **Files** | Privileged file manager · **select many** and copy / move / delete together · open **.apk / .zip / .xapk / .jar … without extracting**, preview text, images and decoded Android XML, extract, rename, delete, add files and edit text in place · **install from inside an archive**, **open nested archives**, **compare two archives** · **sign an edited APK** on the device |
| **Logcat** | Readable, **color-coded** log (one row per entry, tappable level key) · **limit it to one app** · save or share the filtered log as a bug-report text file |
| **Modes** | ADB over TCP · Wireless Debugging (pairing and mDNS port detection) · Shizuku · Root · Automatic · Read-Only |
| **Themes** | **Material 3** (default) · **Material You** (follows your wallpaper) · six more palettes · Light / Dark / System / Schedule · pure-black AMOLED option · per-mode color tuning |
| **Security** | A **private ADB key is generated on each install** (nothing is bundled) · fingerprint shown in the app · signing-certificate comparison before every update |

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

  Every install downloads from each app's own upstream — nothing is rehosted — through your active mode or the system installer.
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

<img src="docs/screenshots/debloat-flow.gif" width="260" alt="Debloating in seven steps: open, filter, select, review, run, history, undo">

<sub><b>Debloat in seven steps</b>: open → filter → select → review → run → history → undo</sub>

</div>

<table>
  <tr>
    <td><img src="docs/screenshots/installer.png" width="230" alt="All-in-one installer"></td>
    <td><img src="docs/screenshots/files.png" width="230" alt="Privileged file manager"></td>
    <td><img src="docs/screenshots/logcat.png" width="230" alt="Logcat reader"></td>
  </tr>
  <tr>
    <td align="center"><sub>All-in-one APK / APKS / APKM installer</sub></td>
    <td align="center"><sub>Privileged file manager</sub></td>
    <td align="center"><sub>Logcat reader</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/batch-select.png" width="230" alt="Batch actions"></td>
    <td><img src="docs/screenshots/manifest-search.png" width="230" alt="Manifest viewer with search"></td>
    <td><img src="docs/screenshots/updates.png" width="230" alt="Updates tab"></td>
  </tr>
  <tr>
    <td align="center"><sub>Batch actions on the selection</sub></td>
    <td align="center"><sub>Manifest viewer with highlighted matches and next/previous</sub></td>
    <td align="center"><sub>Updates: Galaxy Store, F-Droid, GitHub</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/profiles.png" width="230" alt="App profiles"></td>
    <td><img src="docs/screenshots/terminal-search.png" width="230" alt="Terminal search"></td>
    <td><img src="docs/screenshots/working-modes.png" width="230" alt="Working modes"></td>
  </tr>
  <tr>
    <td align="center"><sub>App profiles with a preview before applying</sub></td>
    <td align="center"><sub>Terminal with find, copy and share</sub></td>
    <td align="center"><sub>Working modes, ADB key fingerprint</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/backups.png" width="230" alt="Backups"></td>
    <td><img src="docs/screenshots/drift-banner.png" width="230" alt="Watched profile banner"></td>
    <td><img src="docs/screenshots/quick-list.png" width="230" alt="Quick list"></td>
  </tr>
  <tr>
    <td align="center"><sub>Backups: create, restore, share</sub></td>
    <td align="center"><sub>A watched profile noticed apps came back</sub></td>
    <td align="center"><sub>Choose the quick list for the tile and widget</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/whats-new.png" width="230" alt="What's new"></td>
    <td><img src="docs/screenshots/themes.png" width="230" alt="Theme picker"></td>
    <td><img src="docs/screenshots/pure-black.png" width="230" alt="Pure black"></td>
  </tr>
  <tr>
    <td align="center"><sub>What's new after an update</sub></td>
    <td align="center"><sub>Material 3, Material You and more</sub></td>
    <td align="center"><sub>Pure black for AMOLED</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/about.png" width="230" alt="About tab"></td>
    <td><img src="docs/screenshots/about-light.png" width="230" alt="About tab, light"></td>
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
    <td><img src="docs/screenshots/apps-light.png" width="230" alt="Light: app list"></td>
    <td><img src="docs/screenshots/app-menu-light.png" width="230" alt="Light: app menu"></td>
    <td><img src="docs/screenshots/debloater-light.png" width="230" alt="Light: Debloater"></td>
  </tr>
  <tr>
    <td align="center"><sub>App list</sub></td>
    <td align="center"><sub>App menu</sub></td>
    <td align="center"><sub>Debloater</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/profiles-light.png" width="230" alt="Light: profiles"></td>
    <td><img src="docs/screenshots/backups-light.png" width="230" alt="Light: backups"></td>
    <td></td>
  </tr>
  <tr>
    <td align="center"><sub>Profiles</sub></td>
    <td align="center"><sub>Backups</sub></td>
    <td></td>
  </tr>
</table>

> The screenshots and the animation are rendered from the app's real UI with sample data. The Quick Settings
> tiles, the widget and the notification are drawn by Android itself, so they are described below instead.

## Working modes

| Mode | Needs | Notes |
|---|---|---|
| **Automatic** (default) | — | Uses the first ready backend: ADB TCP → Wireless Debugging → Shizuku |
| **ADB over TCP** | `adb tcpip 5555` once | Connects to `127.0.0.1:5555` or any `IP:port` |
| **Wireless Debugging** | Android 11+ | Pair with code, then connect. Fields accept `IP:port` or just the port; **Auto-Detect Ports** uses mDNS |
| **Shizuku** | Shizuku running | Tap **Authorize &amp; Use Shizuku** and approve the prompt |
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

The **⬆️ Updates** tab checks:

- **Samsung system apps and Galaxy Store apps** against the Galaxy Store
- **Sideloaded open-source apps** against your own sources, your imported **Obtainium** list, the Obtainium
  community catalog, **IzzyOnDroid** and **F-Droid**, with releases read from **GitHub** and **Codeberg**
- **This app** against its GitHub Releases

Update apps one by one or **Update All**. Each download is checked first: same package, newer version, **same
signing key** (a mismatch such as an F-Droid build over a developer build is stopped with an explanation),
then installed through ADB, Shizuku or Root. Apps only Obtainium can track open in Obtainium; untracked apps
get **＋ Set source**. Play Store apps keep updating through the Play Store, since Google gives other apps no
way to check them.

## App menu

Tap **⋯** on any app for its version, install and update dates, any available update, sizes (APK, data,
cache; data and cache need *usage access*, which the app can grant through ADB/Shizuku/Root) and actions:
Launch, Force Stop, Freeze/Enable, Suspend/Unsuspend, Clear Data, Uninstall, Reinstall, Remove Updates, App
Info, **Extract APK** (a `.apk`, or an `.apks` bundle for split apps) and **Share APK**. Five tabs follow:

- **Permissions**: searchable, filterable; toggle runtime and development permissions
- **App Ops**: Allow / Foreground / Ignore / Deny / Reset per op, plus setting any op by name
- **Components**: all activities (exported and unexported) with **Launch**, plus services. Unexported
  activities launch through ADB / Shizuku / Root and show Android's answer if it refuses
- **Manifest**: decoded `AndroidManifest.xml` with search, copy, share and save to Downloads
- **Raw**: the full details JSON

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

**💾 Backup** (app menu) and **🗄️ Backups** (Applications tab) save an app as one `.adbbackup` file in
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
links pointing elsewhere (checked in [`BackupScripts`](src/com/bloatware/bingblop/BackupScripts.java)):

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
| **Working mode** tile / **🔄 Mode** widget button | Switches to the next mode that is ready (ADB TCP → Wireless Debugging → Shizuku → Root), or back to Automatic. The tile shows the current mode |
| **Stop apps** tile / **🛑 Stop apps** widget button | Force-stops every app in your **quick list** |

Choose the quick list in **Saved Lists** with **🛑 Quick list** on a card. To add the tiles, swipe down twice,
tap ✏️ and drag **Working mode** and **Stop apps** into your panel. For the widget, long-press the home
screen → Widgets → ADB App Manager.

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
- **Copy chips** for package, version and name in the app menu; **Copy Packages** and **Share List** for a
  batch selection.
- **Share sheet** (Android's own): package lists, the app-list CSV, a manifest, terminal output, profiles and
  extracted APKs. Files go through a private cache provider that grants one-off read access to the receiving
  app, so no storage permission is needed.
- **Find** in the manifest viewer and the terminal highlights every match, shows `2 / 7 matches`, and jumps
  with ▲ ▼. The manifest viewer can show only matching lines (with line numbers) or the whole file in context.

## Themes

**Appearance:** Light, Dark, System (follows the phone), or Schedule (light/dark start times), plus optional
**pure black** in dark mode.

**Material 3** (default) and **Material You** (dynamic color from your wallpaper, Android 12+, follows
wallpaper changes) come first, followed by six classic palettes. Every palette has light and dark variants,
colors can be fine-tuned per mode, and everything is remembered between launches.

## How it works

The UI is one HTML file rendered in a `WebView`; a small Java bridge turns taps into package-manager
commands and runs them through whichever backend is active. There is no Gradle: `build.sh` drives `aapt2`,
`javac`, `d8` and an APK signer directly, so the whole app builds in Termux.

**Actions are plain `pm` / `am` commands** ([`MainActivity.java`](src/com/bloatware/bingblop/MainActivity.java)):

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

**A private ADB key per install.** [`AdbKeyManager`](src/com/bloatware/bingblop/AdbKeyManager.java) generates a
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
([`UpdateManager`](src/com/bloatware/bingblop/UpdateManager.java)):

```java
Matcher m = Pattern.compile("([0-9]+(?:\\.[0-9]+)*)").matcher(v.trim());
```

**Sharing without a support library**: [`ShareProvider`](src/com/bloatware/bingblop/ShareProvider.java) is a
tiny non-exported `ContentProvider` that serves `cache/share/` to the share sheet with one-off URI grants.

**The UI talks to Java through one object** and Java calls back with `evaluateJavascript`:

```js
const err = window.AndroidBridge.shareTextFile('app_list.csv', csv, 'text/csv');   // JS → Java
// Java → JS: window.onApkExtracted(json)
```

**What's new needs no extra file**: `build.sh` copies `CHANGELOG.md` into the APK's assets, and the app renders
it, so the in-app notes always match the release.

Also in the sources: [`ManifestDecoder`](src/com/bloatware/bingblop/ManifestDecoder.java) turns the binary
`AndroidManifest.xml` inside an APK back into readable XML.

## Privacy &amp; security

- **No analytics, no account, no server of ours.** The app only makes requests you trigger: the UAD-NG list,
  Galaxy Store / GitHub / Codeberg / F-Droid / IzzyOnDroid lookups for updates, and update downloads.
- The ADB key is created on your phone and never leaves it. The APK contains no key.
- Updates are installed only after the package name, version and **signing certificate** match.
- The reboot receiver only compares the build fingerprint and posts a reminder. It changes nothing.
- Backup files can hold app data; they are plain files in your Downloads folder, so treat them like the data
  they contain. Restoring data validates the archive first (see above).
- Powerful actions (uninstall, disable, clear data) always go through a privileged mode you set up yourself.
  Read-Only mode can inspect but not change anything.
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

The **Publish release** workflow (Actions → Publish release → enter the version, for example `4.6`) creates a
GitHub Release with the signed APK and its checksum attached.

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
AndroidManifest.xml            App manifest (Shizuku provider, share provider)
src/                           MainActivity (+ JavaScript bridge), ManifestDecoder, AdbKeyManager,
                               UpdateManager, ShareProvider, BackupScripts, QuickActions,
                               QuickActionActivity, ModeTileService, StopListTileService,
                               QuickWidgetProvider, BootReceiver, ZipTool (archive engine),
                               ApkSigner + SigningKey (on-device APK signing), RishShell,
                               XapkInfo, InstallHints (reads what pm install answered)
assets/index.html              The whole UI (HTML/CSS/JS, rendered in a WebView)
assets/libadb.so               arm64 adb client for ADB TCP / Wireless Debugging
assets/rish, rish_shizuku.dex  Shizuku shell fallback
res/                           Launcher icons, widget layout, tile and notification icons, strings
libs/                          Shizuku API 13.1.5 (api, provider, shared, aidl); RE2J 1.8 (linear-time
                               regex for untrusted, network-supplied patterns, see UpdateManager)
docs/screenshots/              Images and the debloat-flow animation used in this README
build.sh                       Build script (Termux or Linux)
release/                       Signed release APKs and SHA256SUMS.txt
.github/workflows/             build.yml (CI) and release.yml (publish a GitHub Release)
```

## Testing

A full pass through every source file, not just this round's diff, backed by GitHub Copilot's automated
review (eight rounds on this PR) plus a standalone, independent full-file audit, with a runnable check for
every finding that survived verification:

- **UI**: 24 headless-browser suites drive the real `index.html` against a mock Android bridge (every tab,
  theme, filter, share/copy/find action, profiles, drift banner, What's new, quick list, backup and restore
  flows, and - the latest addition - that editing or deleting the active quick list correctly nudges or
  clears it instead of leaving a stale widget label or a dangling reference, and that importing a profile
  with a duplicated package keeps exactly one entry for it).
- **Backup scripts**: run against a fake `/data` tree with real `tar`, including hostile archives (other apps'
  paths, `..`, hard links, outward links, device/FIFO entries, damaged files); with `chown`/`restorecon`
  stood in for on `PATH`, both a real device's success path and a failed ownership/SELinux repair correctly
  reported as an error rather than silently restored; with a stand-in `tar`, its own exit code (not just
  "did it leave a file behind") decides OK/WARN/ERROR, and a failure partway through restoring data rolls
  the app's original data back instead of having already deleted it before extraction could even be
  attempted: 42 checks.
- **Manifest decoder**: hand-built adversarial binary AXML (negative strings-pool count, an overflowing chunk
  size, a mismatched closing-tag reference, an oversized manifest, a strings-pool count that passes a
  byte-size check but could never fit its own offset table) alongside a well-formed one, run against the real
  decoder with Android's own framework classes on the classpath: 6 checks.
- **Update checks**: a package-name validator rejecting shell metacharacters (19 cases); the working-mode
  tile/widget's fallback order (8 cases); a direct timing proof that skipping an unwanted multi-gigabyte
  backup entry no longer decompresses it (6.9s → 0ms); the two regexes matched against untrusted catalog
  data run through [RE2J](https://github.com/google/re2j) instead of a timeout around `java.util.regex` -
  two earlier attempts at that timeout (a shared thread pool, then a fresh thread per attempt) each turned
  out to leak unkillable, permanently-backtracking threads in a way a determined catalog entry could still
  turn into a standing or growing problem, which a genuinely linear-time engine doesn't have in the first
  place. Proven directly: the classic `(a+)+$` ReDoS pattern against 35 `a`s resolves in low tens of
  milliseconds, not the exponential blowup a backtracking engine would hit; a pattern using a backreference
  (unsupported by RE2J) falls back to "no transformation"/"no filter" instead of throwing (9 checks).
- **Build**: every APK is compiled, signed and verified (zipalign, v2/v3 signatures) by `build.sh`.
- The parts that only exist on a phone (the ADB, Shizuku and Root backends, the Quick Settings tiles, the
  widget, the share sheet and the boot notification) are checked by compilation and review rather than on
  hardware. If something misbehaves on your device, please [open an issue](https://github.com/Bingblop/ADB-Application-Manager/issues).

## Credits

Debloat data from [UAD-NG](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation)
(GPL-3.0, downloaded at runtime). Shell access via [Shizuku](https://github.com/RikkaApps/Shizuku-API).
Update sources: Galaxy Store, GitHub, Codeberg, [F-Droid](https://f-droid.org),
[IzzyOnDroid](https://apt.izzysoft.de/fdroid) and the [Obtainium](https://github.com/ImranR98/Obtainium)
community catalog.

Changes by version: [CHANGELOG.md](CHANGELOG.md).
