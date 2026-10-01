# ADB Application Manager Pro

Turn the Tasker bloatware removal tool into a native Android application: browse every installed
package, freeze/enable, force-stop, clear data, uninstall for user 0, reinstall removed system apps,
manage permissions, save package lists, and run shell commands — through ADB TCP, Wireless
Debugging, Shizuku or Root.

**Latest release:** [`release/ADB_Application_Manager_Pro-v4.0.apk`](release/ADB_Application_Manager_Pro-v4.0.apk) — see [CHANGELOG.md](CHANGELOG.md).

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

**ADB key:** each install generates its own private ADB key on the phone (nothing is bundled in the
APK). Working Modes shows its fingerprint, which matches the phone's "Allow debugging?" prompt.

## Debloater

The **🧹 Debloater** tab uses the community package list from
[Universal Android Debloater Next Generation](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/wiki)
(UAD-NG). It shows the packages on your phone with UAD-NG's description and removal level
(Recommended, Advanced, Expert, Unsafe). Filter by removal level, vendor list, state or brand (your
phone's brand first), and uninstall (for your user), disable or restore
them after a review step. The list is GPL-3.0 licensed, so it is downloaded from UAD-NG's GitHub at
runtime and cached on the phone rather than included in this repository.

Every action is recorded in **📜 History** with one-tap **Undo**, and your filters are remembered
between launches.

## Updates

The **⬆️ Updates** tab checks the Galaxy Store for newer versions of Samsung system apps and
Galaxy Store apps, and GitHub Releases for this app. Update apps one by one or all at once; each
official APK is verified (same package, newer version) and installed through ADB, Shizuku or Root.
Play Store apps keep updating through the Play Store.

## App menu

Tap **⋯** on any app for actions (Launch, Force Stop, Freeze/Enable, Suspend/Unsuspend, Clear Data, Uninstall,
Reinstall, Remove Updates, App Info) and five tabs:

- **Permissions**: searchable, filterable list; toggle runtime and development permissions
- **App Ops**: per-op Allow / Foreground / Ignore / Deny / Reset, plus setting any op by name
- **Components**: all activities (exported and unexported) with Launch, plus services; unexported
  activities launch through ADB / Shizuku / Root and show Android's answer if refused
- **Manifest**: decoded `AndroidManifest.xml` with find, copy and save to Downloads
- **Raw**: the full details JSON

## Themes

**Appearance:** Light, Dark, System (follows the phone's dark mode) or Schedule (light/dark start
times). Optional **pure black** backgrounds in dark mode for AMOLED screens.

**Material 3** (the default) and **Material You** (dynamic color from your wallpaper, Android 12+,
updates when the wallpaper changes) come first, followed by six classic palettes. Every palette has a
light and a dark version. Colors can be fine-tuned per mode, and everything is remembered between
launches.

## Project layout

```
AndroidManifest.xml          App manifest (includes the Shizuku provider)
src/                         Java sources (MainActivity + JavaScript bridge, ManifestDecoder, AdbKeyManager, UpdateManager)
assets/index.html            The whole UI (HTML/CSS/JS, rendered in a WebView)
assets/libadb.so             arm64 adb client used for ADB TCP / Wireless Debugging
assets/rish, rish_shizuku.dex  Shizuku shell fallback
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

### On GitHub (automatic)

The **Build APK** workflow builds every push and pull request. Download the APK from the run's
**Artifacts** section in the Actions tab. To sign it with your release key (so it installs over the
app on your phone), add two repository secrets under Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | output of `base64 -w0 release.keystore` (run in Termux) |
| `KEYSTORE_PASSWORD` | your keystore password (`password` if you used build.sh's default) |
| `KEY_ALIAS` | optional, defaults to `adbmanager` |

Without the secrets, builds are signed with a throwaway test key and named `-test-signed`.

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
