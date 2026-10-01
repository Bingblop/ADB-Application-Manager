# ADB Application Manager Pro

Turn the Tasker bloatware removal tool into a native Android application: browse every installed
package, freeze/enable, force-stop, clear data, uninstall for user 0, reinstall removed system apps,
manage permissions, save package lists, and run shell commands — through ADB TCP, Wireless
Debugging, Shizuku or Root.

**Latest release:** [`release/ADB_Application_Manager_Pro-v3.2.apk`](release/ADB_Application_Manager_Pro-v3.2.apk) — see [CHANGELOG.md](CHANGELOG.md).

## Working modes

| Mode | Needs | Notes |
|---|---|---|
| **Automatic** (default) | — | Uses the first ready backend: ADB TCP → Wireless Debugging → Shizuku |
| **ADB over TCP** | `adb tcpip 5555` once | Connects to `127.0.0.1:5555` or any `IP:port` |
| **Wireless Debugging** | Android 11+ | Pair with code, then connect. Fields accept `IP:port` or just the port; **Auto-Detect Ports** uses mDNS |
| **Shizuku** | Shizuku running | Tap **Authorize & Use Shizuku** and approve the prompt |
| **Root** | su (Magisk / KernelSU / APatch) | Requested on demand |
| **Read-Only** | — | Inspect only |

Every mode card has a **Use This Mode** button, so you can switch modes at any time — including
while ADB TCP 5555 is enabled. Status checks never change the selected mode.

## App menu

Tap **⋯** on any app for actions (Launch, Force Stop, Freeze/Enable, Clear Data, Uninstall,
Reinstall, Remove Updates, App Info) and five tabs:

- **Permissions**: searchable, filterable list; toggle runtime and development permissions
- **App Ops**: per-op Allow / Foreground / Ignore / Deny / Reset, plus setting any op by name
- **Components**: activities and services
- **Manifest**: decoded `AndroidManifest.xml` with find, copy and save to Downloads
- **Raw**: the full details JSON

## Themes

Six dark palettes plus **Material 3** (M3 baseline dark scheme) and **Material You** (M3 dynamic
color from your wallpaper, Android 12+). Every color can be fine-tuned, and the theme is remembered
between launches.

## Project layout

```
AndroidManifest.xml          App manifest (includes the Shizuku provider)
src/                         Java sources (MainActivity + JavaScript bridge, ManifestDecoder)
assets/index.html            The whole UI (HTML/CSS/JS, rendered in a WebView)
assets/libadb.so             arm64 adb client used for ADB TCP / Wireless Debugging
assets/rish, rish_shizuku.dex  Shizuku shell fallback
assets/adbkey(.pub)          ADB client key pair
res/                         Launcher icons and strings
libs/                        Shizuku API 13.1.5 (api, provider, shared, aidl)
build.sh                     Build script (Termux or Linux)
release/                     Signed release APKs
```

## Building

### In Termux

```bash
pkg install aapt2 apksigner d8 ecj zipalign openjdk-17 python git
git clone https://github.com/Bingblop/ADB-Application-Manager
cd ADB-Application-Manager
./build.sh
```

The APK is written to `bin/ADB_Application_Manager_Pro.apk` and copied to `Download/` when Termux
has storage access (`termux-setup-storage`).

### Signing and updating in place

Android only installs an update over an existing install when both APKs are signed with the same
key. `build.sh` signs with `./release.keystore` (alias `adbmanager`, password `password`, same as the
original `compile.sh`). Copy the keystore you used before into the repo folder to update without
uninstalling. Keystores are git-ignored — never commit them to this public repository.

### On Linux without an Android SDK

Every tool can be overridden with environment variables:

```bash
AAPT2=/path/to/aapt2 \
ANDROID_JAR=/path/to/android.jar \        # resources for aapt2 -I
BOOTCLASSPATH=/path/to/android-classes.jar \
D8_JAR=/path/to/r8.jar \                  # or have `d8` on PATH
UBER_SIGNER_JAR=/path/to/uber-apk-signer.jar \  # or have zipalign + apksigner on PATH
KEYSTORE=/path/to/release.keystore \
./build.sh
```
