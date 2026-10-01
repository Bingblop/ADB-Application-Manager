# Changelog

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
