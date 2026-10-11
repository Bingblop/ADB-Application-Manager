# The WebView / Kotlin swap: what each app does and expects

For whoever builds the Kotlin app. v7.12.10 is the final WebView release. It carries the way to move to the Kotlin version (Settings, **Try the Kotlin build (beta)**), and
the Kotlin app must carry the way back. Both apps stay installed; neither replaces or updates the other. The WebView side is `src/com/bloatware/bingblop/KotlinBuild.java`
(all the constants are in one block at its top) and `docs/guide/06-system.html`.

## (a) What the WebView app does and expects

| | |
|---|---|
| Package ids | WebView app `com.bloatware.bingblop`. Kotlin app **`com.bloatware.bingblop.kotlin`**: change the Kotlin app's `applicationId` to this before its first release. A different id is what lets both stay installed. |
| Installed? | The card asks Android for that package: if present it shows the `versionName` and **Open the Kotlin build** starts its launcher activity, so the Kotlin app needs one (`MAIN` / `LAUNCHER`). |
| Where the build is | A release of `Bingblop/ADB-Application-Manager`: tag `kotlin-<version>` (letters, digits, `.`, `_`, `-`; up to 64 characters), not a draft, **not marked pre-release**, and an asset named `ADB-Manager-Kotlin*.apk` (letters, digits, `.`, `_`, `-`; the file must be reachable at the normal `https://github.com/Bingblop/ADB-Application-Manager/releases/download/<tag>/<name>`, state "uploaded" and a size that GitHub reports, above 0 and under 600 MB). |
| Which one | The newest by the number in the tag (`kotlin-8.0.10` is above `kotlin-8.0.9`), found in the newest 500 releases (5 pages of 100). A release with no matching file is skipped. |
| Checksum | When the release publishes one, the download must match it or the install is refused. In this order: the asset's GitHub `digest` (`sha256:...`); a file `<apk name>.sha256`; a file `SHA256SUMS.txt` (the `sha256sum` format, `<hex>  <file name>`). If one of the two files exists but is unreadable, empty, or has no valid SHA-256 for that exact APK name, the lookup fails and nothing is installed. With none of the three, nothing is checked, so **publish `SHA256SUMS.txt`**. |
| Install | The file goes through the app's one install path: hardened download (redirects by hand, never https to http), the checksum, a check that the file really is `com.bloatware.bingblop.kotlin`, then the native confirmation (ADB, Shizuku or Root) or Android's own installer. Android accepts an update of the Kotlin app only if it is signed with the same key as the installed one, so sign every Kotlin release with one key. |
| Never | The WebView app never replaces, updates or removes itself because of this card, and never touches the Kotlin app's data. |

## (b) What every Kotlin version must do in return

A Settings entry **Switch to the WebView version** (keep it in every future version, with the same behaviour). It must:

1. List the releases of `https://github.com/Bingblop/ADB-Application-Manager/releases` (the API `https://api.github.com/repos/Bingblop/ADB-Application-Manager/releases?per_page=100`, more than one page: the repository has over 30 releases). Take the newest release that is not a draft or pre-release and whose tag is `v<major>.<minor>.<patch>` (for example `v7.12.10`). Ignore the `kotlin-*` tags.
2. Take the file for the phone from that release (release.yml builds exactly these, and `SHA256SUMS.txt`):
   `ADB_Application_Manager_Pro-v<version>-arm64-v8a.apk`, `ADB_Application_Manager_Pro-v<version>-armeabi-v7a.apk`, `ADB_Application_Manager_Pro-v<version>-universal.apk`, `SHA256SUMS.txt`.
   Use arm64-v8a if the phone supports it, else armeabi-v7a, else universal.
3. Download it over https only (follow redirects by hand, never https to http).
4. Read `SHA256SUMS.txt` (lines `<sha256>  <file name>`), find the line for that exact file name, and compare it with the SHA-256 of the download. If the line is missing, malformed or different, stop: install nothing (fail closed).
5. Before installing, check that the file is package `com.bloatware.bingblop` and that its single signer certificate has SHA-256 `9461870f7cd2406406a119f4d37533c23bb7d1ed850506bad7ea3e6b4bd26d17` (the check `tools/check-signing-cert.sh` makes in the release workflow). A different certificate means refuse.
6. Install through Android's system installer (it needs "Install unknown apps" for the Kotlin app) and let the person confirm there.
7. If `com.bloatware.bingblop` is already installed, open it (its launcher intent) instead of downloading, and offer the download only for a newer release.
8. Never remove, replace or update the Kotlin app itself, and keep the WebView app installed alongside it.
