# Changelog

## v4.7-Pro (versionCode 370)

Installs over v3.1 – v4.6 without uninstalling (same signing key).

- **✨ What's new**: the changelog is bundled in the app. After an update it opens once with the changes
  since the version you last ran; **Color & Themes → About** has the button, version and links.
- **👁️ Watch a profile**: in **🗂️ Profiles**, tap **👁️ Watch** on one profile. When apps from it come back
  (typically after a system update) a banner on the Applications tab says how many and opens the same
  reviewed Apply step. After a reboot with a new system build, a notification reminds you (Android 13+
  asks for notification permission the first time you watch a profile). Nothing is changed without your
  confirmation.
- **Quick Settings tiles and a home-screen widget** (they run without opening the app):
  - **Working mode** tile / **🔄 Mode** widget button: switches to the next mode that is ready
    (ADB TCP → Wireless Debugging → Shizuku → Root), or back to Automatic.
  - **Stop apps** tile / **🛑 Stop apps** widget button: force-stops every app in your **quick list**.
    Choose it with **🛑 Quick list** on a card in **Saved Lists**.
- **🗄️ Backup and restore**: **💾 Backup** in the app menu (and **🗄️ Backups** on the Applications tab).
  A backup is one `.adbbackup` file in `Download/ADB App Manager/Backups/` holding the APK (with splits),
  granted permissions and changed app ops. With **Root** it can also hold the app's data. Restore
  installs the APK through ADB, Shizuku or Root, re-applies permissions and app ops, and with Root puts the
  data back. You can **Share** a backup or **Choose a backup file** from another phone. Data restore
  refuses archives that would write outside the app's own folders.
  Without Root, private app data cannot be read on current Android, so backups are APK + settings only.
- README: debloat-flow animation, light-theme screenshots and the new features.

## v4.6-Pro (versionCode 360)

Installs over v3.1 – v4.5 without uninstalling (same signing key).

- **Copy buttons and share sheet**: 📋 Package / Version / Name chips and 📤 Share in the app menu;
  **Copy Packages** and **Share List** for a batch selection; **Share CSV** for the app list; **Share**
  for the manifest and the terminal output; **Share APK** extracts an app and opens the share sheet.
  Files go through a private, non-exported provider (`ShareProvider`) with one-off read grants.
- **Search with highlight**: the manifest viewer and the terminal highlight every match, show
  "2 / 7 matches" and jump with ▲ ▼. The manifest viewer keeps "Matches only" (with line numbers) and can
  show the whole file in context instead.
- **🗂️ App profiles**: save the disabled / suspended / uninstalled apps under a name, preview what
  applying would change, apply (recorded in History, so it can be undone), share as
  `.adbprofile.json` and import on another phone.
- README rewritten with screenshots, code snippets and the full feature list.

## v4.5-Pro (versionCode 350)

Installs over v3.1 – v4.4 without uninstalling (same signing key).

- **Select and copy text**: long-press any text (app names, package names, versions, permission and
  activity names, the manifest viewer, terminal output, debloat descriptions and history) to
  highlight it and use the system Copy menu. Buttons, pills and toggles stay unselectable, so taps
  still work as before.
- Dragging to select text on an app row no longer selects or expands the row by accident.

## v4.4-Pro (versionCode 340)

Installs over v3.1 – v4.3 without uninstalling (same signing key).

### App list
- **Sort** by name, last updated, install date, size, or updates first. When sorting by date or
  size, each row shows that value. The choice is remembered.
- **🆕 Updated 7d** filter for apps updated in the last week (handy for spotting a bad update).
- **📤 Export** saves every app with its version, install and update dates, type, state, APK size and
  any known update as CSV to `Download/ADB App Manager/`.

### App menu
- **Sizes**: APK size (and how many parts a split app has); data and cache too once **usage access**
  is allowed. "Show data usage" grants it through ADB/Shizuku/Root, or opens the settings page.
  Sorting by size then uses total storage instead of APK size.
- **📦 Extract APK** saves the app to `Download/ADB App Manager/APKs/`: a single `.apk`, or for split
  apps a `.apks` bundle (base + splits) that split-APK installers such as SAI can install.

## v4.3-Pro (versionCode 330)

Installs over v3.1 – v4.2 without uninstalling (same signing key).

- **Install and update dates** in the app menu, under the version
  ("Installed Jan 15, 2024 • Updated Sep 20, 2026").
- **Versions in the app list**: each app shows its version on the badge row. The **🔢 Versions**
  pill hides or shows them, and the choice is remembered.
- **Update hints**: when the Updates tab has found a newer version, the app list shows
  "v26.0 → 27.1" and the app menu shows "→ 27.1 available on Galaxy Store" with an **Update**
  button (or Download / Release for updates that install elsewhere).

## v4.2-Pro (versionCode 320)

Installs over v3.1 – v4.1 without uninstalling (same signing key).

- The app menu shows the app's **version** right under its package name, as
  "Version <name> (<version code>)". Apps uninstalled for your user show their version too.

## v4.1-Pro (versionCode 310)

Installs over v3.1 – v4.0 without uninstalling (same signing key).

### Open-source updates for sideloaded apps
- The Updates tab now checks sideloaded apps (not from the Play Store or Galaxy Store) against, in
  order: **your sources**, your **imported Obtainium list**, the **Obtainium community catalog**
  (looked up online, honouring its APK filter / version rules), **IzzyOnDroid** and **F-Droid**.
  Apps installed through an F-Droid client are checked against F-Droid first.
- Releases are read from **GitHub** (API, falling back to the public release pages when the
  60-checks-an-hour limit is reached; an optional **GitHub token** raises it to 5,000) and
  **Codeberg**. The APK matching the phone's architecture is picked automatically.
- **📥 Import Obtainium List** reads an Obtainium export (Settings → Export) so every app you track
  in Obtainium is checked here too.
- Apps whose releases live somewhere only Obtainium can track get **Ⓞ Open in Obtainium**; sideloaded
  apps with no known source are listed under **Not tracked** with **＋ Set source**.
- Before installing, the download's **signing key is compared** with the installed app. A mismatch
  (for example an F-Droid build over a developer build) is stopped with a clear explanation instead
  of a failed install.
- Update All covers every installable source; releases without an APK for this phone link to their
  release page.

## v4.0-Pro (versionCode 300)

Installs over v3.1 – v3.9 without uninstalling (same signing key).

### ⬆️ Updates tab
- Checks the **Galaxy Store** for updates to Samsung system apps and apps installed from the Galaxy
  Store, using Samsung's public update service, and checks this app's **GitHub Releases**.
- **Update** per app, or **Update All** (one at a time). Each update is the official APK from the
  Galaxy Store, verified to be the same package and a newer version, then installed through ADB,
  Shizuku or Root. Failures show Android's reason with a Retry button.
- Updates for this app open in the browser so Android's installer handles them.
- The tab shows how many updates are pending. Play Store apps aren't covered: Google provides no way
  for other apps to check them, and the Play Store keeps updating them itself.

### 📜 Debloat history
- Every Debloater run, batch action and single-app freeze/uninstall/suspend (and update) is logged
  with time, packages and result. **Undo** reverses a run (reinstall what was uninstalled, enable
  what was disabled, and so on). **Copy Log** and **Clear History** included. Kept between launches
  (last 150 entries).

### Remembered filters
- The Applications filter and all Debloater filters (levels, vendor, state, brand) are restored
  when the app starts.

## v3.9-Pro (versionCode 290)

Installs over v3.1 – v3.8 without uninstalling (same signing key).

### Debloater: filter by brand
- New brand filter row in the Debloater. Your phone's own brand comes first (📱, detected from the
  phone's manufacturer), followed by the other brands found on the phone (Google, Meta, Microsoft,
  carriers, chip makers...), each with a package count that follows the other filters.
- UAD-NG has no brand field, so the maker is worked out from the package name (`com.samsung.*` /
  `com.sec.*` → Samsung, `com.facebook.*` → Meta, `com.oplus.*` → Oppo, and so on), with a
  fallback to the organisation part of the name. Each package also shows its brand as a badge,
  and search matches brand names.

## v3.8-Pro (versionCode 280)

Installs over v3.1 – v3.7 without uninstalling (same signing key).

### Debloater tab
- New **🧹 Debloater** tab powered by the
  [Universal Android Debloater Next Generation](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/wiki)
  (UAD-NG) community list: descriptions, removal levels and dependency notes for 5,000+ packages.
- The list (`uad_lists.json`, GPL-3.0) is **downloaded from UAD-NG's GitHub at runtime** and cached
  on the phone, not bundled. It refreshes automatically when older than a week, or with **Update List**.
- Shows only packages found on your phone, including ones already uninstalled for your user.
- Filters: removal level (Recommended by default; Advanced, Expert, Unsafe), vendor list (OEM, Google,
  AOSP, Carrier, Misc) and state (on device, enabled, disabled, uninstalled), plus search across
  package, name and description.
- Tap a package to read its full description and dependencies; packages that other installed
  packages need are flagged.
- **Uninstall** (for your user, reversible), **Disable**, **Restore** and **Save to List**. Every action
  is reviewed first, with a prominent warning when Expert or Unsafe packages are included.
- **Removal Levels** explains the four levels (from the UAD-NG wiki); **UAD-NG Wiki** opens the wiki.

## v3.7-Pro (versionCode 270)

Installs over v3.1 – v3.6 without uninstalling (same signing key).

### Suspend / Unsuspend
- The app menu has **Suspend** (for active apps) and **Unsuspend** (for suspended ones).
  A suspended app stays installed with its data, but can't be opened until it is unsuspended.
- Suspended apps are marked in the app list with a **⏸ SUSPENDED** badge and a greyed-out icon,
  and a new **⏸️ Suspended** filter shows only them. Suspension is detected in every mode,
  including Read-Only.
- The batch sheet has **Suspend** (asks for confirmation, like Freeze and Uninstall) and
  **Unsuspend**.

## v3.6-Pro (versionCode 260)

Installs over v3.1 – v3.5 without uninstalling (same signing key).

### Security: private ADB key per install
- Versions up to 3.5 shipped the same ADB key inside every APK (and in this public repo), so anyone
  with that key could connect to a phone that had approved it with ADB TCP open. **The bundled key is
  removed.** Each install now generates its own 2048-bit RSA key on the phone.
- On first launch after updating, the old shared key is replaced automatically. Your phone asks
  **"Allow debugging?"** once on the next ADB connection, and Wireless Debugging must be paired again.
  To fully remove the old key, tap **Revoke USB debugging authorizations** in Developer Options.
- Working Modes shows the key's fingerprint (it matches what the "Allow debugging?" prompt shows),
  with **Copy Fingerprint** and **Regenerate Key**.

### Appearance
- **Schedule**: a fourth Appearance option. Light and dark start times (default 07:00 / 19:00,
  ranges may cross midnight). The theme switches on time while the app is open.
- **Pure black in dark mode**: true #000000 backgrounds for AMOLED screens, with cards lifted just
  enough to stay visible.

### Builds on GitHub
- New **Build APK** workflow builds and signs the APK on every push and pull request and uploads it as
  a downloadable artifact. Add the `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD` secrets to sign with
  your release key (see README).

## v3.5-Pro (versionCode 250)

Installs over v3.1 – v3.4 without uninstalling (same signing key).

### Light and dark mode
- New **Appearance** switch at the top of Color & Themes: **Light**, **Dark** or **System**
  (follows the phone's dark mode, and re-themes instantly when it changes).
- Every palette has a light version: **Material 3** uses the official M3 light scheme,
  **Material You** reads both light and dark schemes from the wallpaper, and the six classic
  palettes get a generated light scheme with accents darkened to readable contrast on white.
- Status and navigation bar icons switch to dark on light themes.
- The whole UI is now theme-aware (sheets, inputs, buttons, toasts, avatars, terminal); text on
  accent-colored buttons picks black or white automatically.
- Color picker tweaks are saved separately for light and dark, with a **Reset tweaks** button.

### Defaults
- New installs start on **Material 3** with Appearance set to **System**. Existing users keep
  their current palette in Dark mode.

### Material You
- Follows wallpaper changes: the dynamic colors are re-read when the wallpaper changes and whenever
  you return to the app.

### Fixes
- ADB Console output keeps its line breaks.

## v3.4-Pro (versionCode 240)

Installs over v3.1 – v3.3 without uninstalling (same signing key).

- Color & Themes: **Material 3** is now the first palette and **Material You** the second, with the
  six classic palettes below them.

## v3.3-Pro (versionCode 230)

Installs over v3.1 / v3.2 without uninstalling (same signing key).

### Components
- The Components tab now lists **every** activity, including **unexported** and disabled ones, with
  badges (exported / unexported / disabled / protected), a count, search and filters
  (All / Exported / Unexported).
- Every activity has a **Launch** button:
  - Exported activities start with a normal intent (works in any mode).
  - Unexported activities start with `am start -W -n` through the active privileged mode
    (ADB TCP, Wireless Debugging, Shizuku or Root). Read-Only mode asks you to set one up first.
  - The full `am start` output is shown, so when Android refuses a launch (for example a
    "Permission Denial" for the shell user, or a disabled activity) you see the exact reason.
    Root can usually start activities the shell user cannot.
- Component names are validated and single-quoted in shell commands, so inner classes with `$`
  launch and stop correctly.

## v3.2-Pro (versionCode 220)

Installs over v3.1 without uninstalling (same signing key).

### App menu (single app)
- **Permissions** now has its own list: search, filters (All / Granted / Denied / Changeable /
  Install-time), protection badges (runtime, development, normal, signature, app op) and permission
  labels. Runtime and development permissions toggle between Granted/Denied; install-time
  permissions are shown locked because `pm grant/revoke` cannot change them.
- **App Ops** is a separate list: search, filters (All / Allowed / Ignored-Denied / Foreground) and
  one-tap modes per op (Allow, Foreground, Ignore, Deny, Reset). Uid-level modes are labeled. Any
  other op can be set by name (e.g. `RUN_ANY_IN_BACKGROUND`).
- New **Manifest** viewer: decodes the app's binary `AndroidManifest.xml` into readable,
  syntax-highlighted XML, with resource references resolved to names. **Find** shows matching lines
  with line numbers. **Copy** puts the XML on the clipboard; **Save to Downloads** writes
  `Download/ADB App Manager/<package>_AndroidManifest.xml`.
- The menu shows **Freeze** for enabled apps and **Enable** for frozen ones.

### App list
- Each app row now has only **App Settings**, **Force Stop** and the **Menu** button.
  Freeze/Enable moved to the menu. App Settings works in every mode, including Read-Only.

## v3.1-Pro (versionCode 210)

### Material Design 3
- New **Material 3** palette: the M3 baseline dark scheme (primary `#D0BCFF`, surface `#141218`,
  surface container `#211F26`, on-surface text `#E6E0E9`).
- New **Material You** palette: M3 dynamic color generated from the wallpaper on Android 12+
  (falls back to the M3 baseline on older versions).
- Palettes now also theme text, muted text, surfaces and the secondary color, and the status and
  navigation bars follow the theme background.
- The selected theme is now restored on launch (it was saved but never loaded before).

### Working modes
- **Fixed: modes could not be changed while ADB over TCP 5555 was enabled.** Every status refresh
  re-connected port 5555 and forced the saved mode back to ADB TCP. Status checks are now read-only
  and the selected mode only changes when you pick one.
- New **Automatic** mode card and a **Use This Mode** button on every mode.
- The header badge shows when a pinned mode is selected but not ready (orange), instead of silently
  falling back to another backend.
- Disconnecting ADB returns to Automatic, so Shizuku or Root can take over.
- `tcpip 5555` now targets the wireless debugging connection when one exists (avoids
  "more than one device").
- Root is no longer picked automatically (it caused su prompts); select it explicitly.

### Wireless Debugging
- **Fixed: IP addresses could not be typed.** Address fields were `type="number"`, which blocks `.`
  and `:` after the first few digits. They are now text fields that accept `IP:port`, `[IPv6]:port`
  or just a port.
- Added an IP field to Step 2 (Connect), plus **Use My Wi‑Fi IP** and **Auto-Detect Ports** (adb mDNS).
- Pairing accepts `IP:port` too.

### Shizuku
- **Fixed: Shizuku could not authorize the app.** The manifest was missing Shizuku's
  `ShizukuProvider` and the `moe.shizuku.client.V3_SUPPORT` marker, so Shizuku could not deliver its
  binder or show the permission prompt. Authorization now uses the official Shizuku API (13.1.5).
- Approving the prompt switches to Shizuku mode immediately, even with ADB TCP 5555 enabled.
- Commands run through the Shizuku remote process API (faster), with `rish` as fallback.
- New **Open Shizuku** button and clearer statuses (not installed / not running / update needed).

### Other fixes
- "Select All" in the batch bar called a function that did not exist.
- `adb devices` parsing now checks the exact target and its `device` state.
- Batch action results now include each command's output.
