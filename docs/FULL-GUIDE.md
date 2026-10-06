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

`com.bloatware.bingblop` · v7.9.15-Pro · signed APK, installs over every earlier version without uninstalling

<table>
  <tr>
    <td><img src="screenshots/apps-dark.png" width="210" alt="Application list"></td>
    <td><img src="screenshots/app-menu.png" width="210" alt="App menu"></td>
    <td><img src="screenshots/debloat-flow.gif" width="210" alt="Debloating in seven steps"></td>
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

[Download](#download--install) · [Features](#features) · [The tabs](#the-tabs) · [Screenshots](#screenshots) ·
[Terminal &amp; coding agents](#terminal-and-coding-agents) · [Working modes](#working-modes) · [Debloater](#debloater) · [Updates](#updates) · [Hidden settings](#hidden-settings) ·
[Overlays &amp; Material You](#overlays-and-material-you) · [App menu](#app-menu) · [Profiles](#app-profiles) · [Backup &amp; restore](#backup-and-restore) ·
[Quick tiles &amp; widget](#quick-settings-tiles-and-widget) · [Copy, share &amp; search](#copy-share-and-search) ·
[Permissions](#permissions) · [Themes](#themes) · [How it works](#how-it-works) · [Building](#building) · [Project layout](#project-layout) ·
[Testing](#testing) · [Privacy &amp; security](#privacy--security)

## Download &amp; install

1. Open the [**Releases** page](https://github.com/Bingblop/ADB-Application-Manager/releases/latest) and download
   the signed APK there, together with that release's `SHA256SUMS.txt`. (Builds up to v5.1 are also kept in
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
| **Apps** | Browse every package, including ones uninstalled for your user · search · sort by name, update date, install date, size or "updates first" · filters for running, 3rd party, system, frozen, suspended, uninstalled, **updated in the last 7 days** and **🧩 patched** (ReVanced, Morphe, Xposed / LSPosed modules, LSPatch / NPatch, debug-signed repackages) · **the big counters at the top light up for the filter you are looking at** · a clean row layout (name, package and badges flush left; checkbox, letter icon, gear and ⋯ together on the right) · versions and update hints in the list (versions are off by default; the Versions filter turns them on) · select many and run batch actions · save selections as named lists · export everything to CSV |
| **Actions** | **App menu (one app):** Launch · Force Stop · Freeze / Enable · **Suspend / Unsuspend** · Clear Data · Uninstall for user 0 (a system app that needs root falls back automatically to a direct Binder call, the same workaround App Manager and Canta use - see below) · Reinstall removed system apps · Remove Updates · App Info · **Extract APK** · **Backup**. **Batch (selected apps):** Freeze · Enable · Force Stop · Suspend · Unsuspend · Clear Data · Uninstall · Reinstall · Save to List · Copy Packages · Share List · **Keep selection after running** (optional switch: run another batch command on the same apps without reselecting them) · batch actions, Dex optimization and applying a profile all run **off the page's thread with a live progress bar (app name and package, fill bar) and a Stop button**, so the page never freezes while they work |
| **Debloater** | The [UAD-NG](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation) community list (5,000+ packages) with descriptions and dependency warnings · filter by removal level, vendor list, state and **phone brand** · review step before anything runs · **history log with one-tap Undo** |
| **Installer** | All-in-one installer for `.apk`, `.apks` (bundletool), `.apkm` (APKMirror) and `.xapk` (with OBB / game data) · reads the package name, version, SDK range, size and signing certificate first · pick the authorizer (ADB / Shizuku / Root / none) and every `pm install` flag (grant all permissions, downgrade, test, all users, update ownership …) · signature-mismatch and unknown-signature gates · optional dex optimization and auto-delete · **storage search** with a progress bar that lists every package file on the phone, where **press and hold deletes a file with Undo** · **the splits that fit the phone are ticked** (CPU, screen density, language) · **▾ lists of common installers and requesters** for the `-i` and `--originating-uri` boxes · **Launch Application / Application Settings** buttons once an install worked · optional **VirusTotal** check with your own API key (a SHA-256 lookup; nothing is uploaded unless you say so) · the app can be Android's handler for APK files |
| **Updates** | **Update the app itself** from its own GitHub releases (dedicated card, signed-key check, seamless with a privileged mode or the system installer without one) · **Galaxy Store** (Samsung system apps) · **GitHub, Codeberg, F-Droid, IzzyOnDroid and the Obtainium catalog** for sideloaded open-source apps · import your **Obtainium** export · Update one or **Update All** · signing-key check before installing · Google Play apps hand off to Aurora Store or the Play Store page |
| **Inspector** | Permissions and App Ops as separate lists · **activities, services, broadcast receivers and content providers**, each with its exported / enabled / permission state, and any component can be **enabled or disabled** · unexported activities are **launchable through ADB / Shizuku / Root** · decoded **AndroidManifest.xml** viewer · version, install and update dates · APK, data and cache sizes |
| **Profiles** | Save which apps are disabled, suspended or uninstalled; re-apply on this phone or **share to another phone** · **👁️ Watch** a profile to be told when its apps come back after a system update |
| **Backup &amp; restore** | **💾 Backup** an app's APK (with splits), permissions and app ops in any privileged mode, plus its **data with Root** · restore through ADB / Shizuku / Root · share backups or pick one from another phone |
| **Quick actions** | **Quick Settings tiles** and a **home-screen widget** to switch the working mode and force-stop a list of apps without opening the app |
| **What's new** | The changelog is inside the app: it opens once after an update, and from About |
| **Productivity** | Select and copy any text · copy buttons for package, version and name · **share sheet** for package lists, CSV, manifest, terminal output and backups · **search with highlight and next/previous** in the manifest viewer and terminal · remembered filters and sort |
| **Terminal** | A **Termux-style terminal** with three persistent shells (this app's sandbox, the working mode, or **your own Termux** with bash and pkg packages), Termux's extra keys, colours, history and TAB completion · **Coding Agents**: Gemini, Claude, ChatGPT, Cursor, Copilot, Perplexity, Grok, Muse and Deepseek (API key, or your subscription through the providers' own CLIs in Termux) and free options (Jan.ai, AnythingLLM, Ollama on the phone, OpenCode) that **run commands and edit files with your OK at each step**, with a Model and an Effort to pick from · a **Shift** key for the symbols a phone keyboard buries, a larger input box and a **Cheat Sheet** that changes with the shell (bash for Termux, file and text basics for the sandbox, the ADB reference for the working mode) · **Sync with Termux** makes the in-app Termux shell match your real one (aliases and functions from `~/.bashrc` included) and grants Termux storage access in one tap · keys encrypted and sent only to their own provider · the classic ADB Console can ask the same agent for syntax help too — see [Terminal and coding agents](#terminal-and-coding-agents) |
| **ADB Console** | Run shell commands through the active mode (off the page's thread), with output search, copy and share · **🕘 history, ⭐ saved scripts and pinned chips** · **🐚 Rish mode**: a persistent Shizuku shell where `cd` and `export` stick, with a STOP that ends a command and its children · a searchable **📋 ADB cheat sheet** of ~90 commands you tap to drop into the input |
| **Files** | Privileged file manager · **Add storage**: an SD card, USB drive or shared folder through Android's own document picker (no working mode or root needed) · **select many** and copy / move / delete together · open **.apk / .zip / .xapk / .jar … without extracting**, preview text, images and decoded Android XML, extract, rename, delete, add files and edit text in place · **install from inside an archive**, **open nested archives**, **compare two archives** · **sign an edited APK** on the device |
| **Logcat** | Readable, **color-coded** log (one row per entry, tappable level key) · **limit it to one app** · save or share the filtered log as a bug-report text file |
| **Hidden Settings** | Read and edit the phone's **Global**, **Secure** and **System** settings, one sub-tab per table · search names, values and descriptions · tap to edit, **press and hold to flip** a switch (1 / 0, true / false), **＋ to create** a setting · plain-English descriptions and ⚠️ warnings for the ones that bite · every change is read back to prove it, with **Undo** and a log of changes with **Revert** — see [Hidden settings](#hidden-settings) |
| **Overlays** | **New in v6.0:** change Android's **Material You** theme from the phone — the **wallpaper** or **any color** (hex, sliders or 657 named presets) with one of six **styles** (Tonal Spot … Spritz), and the palette Android is really using shown afterwards · list every **overlay** (`cmd overlay list`) grouped by the app it restyles, search and filter it, **switch one on or off** with its switch or by **pressing and holding** its row · every change is read back to prove it, with **Undo** — see [Overlays and Material You](#overlays-and-material-you) |
| **App Stores** | **ShizuStore**, **GitHub** (up to 5,000 apps, live search), **F-Droid** (any known repository, streamed) and **Orion** as sub-tabs, each with a category drop-down · every install comes from the app's own upstream (nothing is rehosted), through your active mode or the system installer · a successful install offers **Launch Application** and **Application Settings**, the same as the APK Installer |
| **Modes** | ADB over TCP · Wireless Debugging (pairing, mDNS port detection, and **🔔 pairing from a notification** so the code can't expire while you switch apps) · Shizuku · Root · Automatic · Read-Only |
| **Themes** | **Material 3** (default) · **Material You** (follows your wallpaper) · six more palettes · Light / Dark / System / Schedule · pure-black AMOLED option · per-mode color tuning · **Expressive Animations** (Material 3 Expressive's springier motion across the whole app, on by default, with its own switch under Settings → Motion) |
| **Security** | A **private ADB key is generated on each install** (nothing is bundled) · fingerprint shown in the app · signing-certificate comparison before every update |
| **Navigation** | A Back button that closes the open sheet, clears a selection, steps up a folder or out of an archive, returns to the previous tab, and only then asks for a deliberate second press to leave (it also asks first while an install, update, file job or command is running) |
| **Permissions** | A first-launch sheet offers **All files access**, **Usage access** and **Display over other apps** (each optional, also under About); with a working mode it also offers **Read/Write External Storage**, **Write Secure Settings** and **Access Restricted Settings**, granted straight through the shell. An action that fails for want of file access asks for it on the spot and carries on once it is allowed — see [Permissions](#permissions) |
| **About** | The developer and the GitHub repo, this build's version, device and **signing certificate** (✅ for the official release key), a **Device Specs** card (hardware, software, system, battery, network, camera and sensors, refreshed when the tab opens), copyable debug info for bug reports, the **🔐 Permissions** sheet, and an optional **☕ Buy me a coffee** (PayPal, $1 or any amount) |

## The tabs

Left to right, with the **settings gear** (it opens Settings: language, appearance, colors, font and the Feature List) in the header:

| Tab | For |
|---|---|
| **Application Manager** | Every package on the phone: search (with a menu for names, package names and regex), sort, filter, batch actions, profiles, backups, CSV export; **⋯** opens an app's menu, the gear next to it the app's Android settings |
| **Saved Applications** | Named groups of apps to freeze, enable, stop or share together, and the **quick list** behind the tile and widget |
| **UAD-NG Debloater** | The UAD-NG list for your phone, a review step before anything runs, history with Undo |
| **APK Installer** | Install `.apk` / `.apks` / `.apkm` / `.xapk` with full control of the options, plus the optional VirusTotal check |
| **File Manager** | A privileged file manager that also opens packages and archives without extracting them, and can sign an edited APK |
| **Command-Line Interface** | **Terminal**: a Termux-style terminal with coding agents ([details](#terminal-and-coding-agents)). **ADB Console**: a shell through the active mode, with history, saved scripts, a cheat sheet and a persistent Rish shell |
| **Hidden Settings** | Read, flip, edit and create Android's own Global, Secure and System settings ([details](#hidden-settings)) |
| **RRO/Monet Customization** | Recolor Android (Material You: wallpaper or any color, six styles) and switch system overlays on or off ([details](#overlays-and-material-you)) |
| **App Updater** | This app, Galaxy Store apps and sideloaded open-source apps (GitHub, Codeberg, F-Droid, IzzyOnDroid, Obtainium) |
| **App Stores** | ShizuStore, GitHub, F-Droid and Orion |
| **Logcat Viewer** | A color-coded device log you can limit to one app, save or share |
| **Task Manager** | Processes, CPU, RAM, GPU, battery and network, live, each with a graph ([details](#new-in-v75)) |
| **About** | Who made it, which build and key you have, debug info, the Permissions sheet, and the coffee button |

Every tab except Application Manager and About can be switched off or moved in **Settings → Feature List**.

## New in v7.9

**v7.9.18 is the latest build.** Everything from v7.9 through v7.9.15 is below; the full, dated list is in
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

The first page of the **Command-Line Interface** tab is a Termux-style terminal with AI coding agents built in; a big switch
at the top goes to the classic **ADB Console** next to it.

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
| Jan.ai, AnythingLLM | the address of the computer that runs it (found by itself when it runs on the phone); AnythingLLM also needs its key |
| Ollama (on-device) | Ollama in Termux with a small coding model, installed from the agent's sheet: free and offline |
| OpenCode | the OpenCode CLI, installed in Termux |
| DroidMind, Kilo Code, Leon.ai | explained in their sheet: DroidMind is a tool other agents use to control Android (every agent here can already work with the phone through the Working mode shell); Kilo Code is an editor extension with no phone terminal of its own; Leon is a computer assistant |

**The classic ADB Console can ask an agent too.** Next to its input, a **$ / AI** button switches it to ask the same agent,
model and effort picked in the Terminal above — for ADB/shell syntax and code help, not full file access. The agent answers
inline in the console, and may propose one command at a time, shown with **Run** / **Skip** (or run on its own when "Ask
before running commands" is off) through the console's own execution path. Its memory of the conversation is its own,
separate from the Terminal's.

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

## Hidden settings

The **🛠️ Hidden Settings** tab (between Logcat and Overlays) is a front end for Android's own `settings` command: the three
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

## Overlays and Material You

The **🌈 Overlays** tab (right after Hidden Settings) has two halves, chosen by the sub-tabs at its top. Changing anything needs ADB,
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
Info and **Extract APK** (a `.apk`, or an `.apks` bundle for split apps, saved to Downloads). Five tabs follow:

**Uninstalling a system app for one user without root** (`pm uninstall --user 0` answering "only root can delete
system app for a particular user"): the app automatically retries through a direct Binder call to
`IPackageManager.deletePackageAsUser`, run as a standalone `app_process` under whichever privileged shell (ADB or
Shizuku) is already active - the same technique App Manager and Canta use for the same refusal. This removes the
app for the current user; it stays in the system partition (the same result a normal uninstall gives for a
non-system app), and needs a working mode that isn't already Root (Root removes it directly, no fallback needed).

- **Permissions**: searchable, filterable; toggle runtime and development permissions
- **App Ops**: Allow / Foreground / Ignore / Deny / Reset per op, plus setting any op by name
- **Components**: all activities (exported and unexported) with **Launch**, plus services. Unexported
  activities launch through ADB / Shizuku / Root; a launch that goes through just opens the activity, and Android's answer is
  shown only if it refuses
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
own screen. **Not now** skips them all: the same sheet is under **About → 🔐 Permissions** and shows what is allowed. On Android
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
