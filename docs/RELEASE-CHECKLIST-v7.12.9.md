# v7.12.9 release: before, during and after

The release is a pull request (this one), then the `release.yml` workflow with the version `7.12.9`. **Nothing here has been run on a phone by the people and tools that wrote it.** The workflow is started by the owner, never by a tool: this page says what to look at before and after.

## 1. Before starting the workflow

- [ ] The release pull request is merged and CI on `main` is green for that commit (build, Java suites, UI scripts, the Copilot reviewer check).
- [ ] `CHANGELOG.md` has a `## v7.12.9-Pro (versionCode 851)` section. The workflow copies that section into the release text, so read it once as the person who will see it: every sentence must be true of this build. A pull request that was left out must not be mentioned.
- [ ] `AndroidManifest.xml` says versionName `7.12.9-Pro` and versionCode `851`, and the README "what's new" matches the changelog.
- [ ] The repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD` and `KEY_ALIAS` are set. Without them the APKs are signed with a throwaway key and will not install over the current app (the workflow only warns).
- [ ] The on-device checklist (`docs/DEVICE-TEST-CHECKLIST.md`, section 19, and `docs/DEVICE-TEST-SCRIPT.md` for the adb commands) has been offered to the owner. AGENTS.md asks for that before a release.
- [ ] If the `release.yml` patch that was handed over during the review is wanted, it goes in as its own pull request first. It is a change to release policy, so it needs the owner.

## 2. Starting it

Actions, "Build, sign and publish release", Run workflow, branch `main`, version `7.12.9`. (A tag `v7.12.9` pushed to the repository starts the same workflow; use one or the other, not both.)

The workflow builds three APKs (`arm64-v8a`, `armeabi-v7a`, `universal`), writes `SHA256SUMS.txt`, checks the signing certificate against the pinned value when the release key is set, and publishes the release with the changelog section as its text.

## 3. After it finished: the release itself

Download the four files of the release into an empty folder, then from that folder:

```sh
sha256sum -c SHA256SUMS.txt
```

- [ ] Every line says `OK`. There are exactly three `.apk` files and `SHA256SUMS.txt`.
- [ ] The arm64 file is the first one in the list of assets (an older copy of the app that updates itself takes the first `.apk`).
- [ ] The release text is the changelog section and nothing else (no empty body: the extraction is `awk` on the heading `## v7.12.9`).

Signing certificate, for each APK (needs `apksigner` from build-tools 35):

```sh
apksigner verify --print-certs ADB_Application_Manager_Pro-v7.12.9-arm64-v8a.apk
```

- [ ] One signer, and its SHA-256 digest is `9461870f7cd2406406a119f4d37533c23bb7d1ed850506bad7ea3e6b4bd26d17` (the value in the README and in `release.yml`). Repeat for the other two files.
- [ ] If the digest differs, or the workflow printed the "throwaway key" warning: stop. Do not tell anyone to update; delete the release (or mark it a pre-release) and fix the secrets first. Phones refuse an update over a different certificate, and users who uninstall to get around that lose their data.

Version, for each APK (`aapt2` from build-tools 35):

```sh
aapt2 dump badging ADB_Application_Manager_Pro-v7.12.9-arm64-v8a.apk
```

- [ ] `versionCode='851'` and `versionName='7.12.9-Pro'`, package `com.bloatware.bingblop`.

## 4. After it finished: on a phone

Install the arm64 file over the previous version (an update, not a fresh install), then open the app. Use the device script for the exact adb commands. In this order:

- [ ] The app opens, the About tab says 7.12.9, and settings, saved lists and the GitHub token and VirusTotal key (if they were set) are still there. The keys were moved into the Keystore vault on first use: set a VirusTotal key, close the app completely, open it again and check the key is still accepted.
- [ ] Terminal (full screen): open it, run `sleep 30`, press Restart, type `echo ok`: the answer appears once and the terminal accepts input and closes. Do it three times quickly.
- [ ] Connected Devices, with a device connected: `adb devices` works; `adb -P 5037 devices` and `adb kill-server` are refused with the message about another server.
- [ ] ADB cheat sheet: the full-reference button opens the gist in the browser and the page itself is unchanged.
- [ ] Morphe Patcher tab: it opens, the list of apps shows, tapping a card opens its details.
- [ ] APK Installer/Updater: an update through the system installer (standard mode) still shows the system installer's question.
- [ ] Dex optimization sheet: **space** and **reset** are offered; **Standby** in the app menu shows a bucket and changes it.
- [ ] Share a picked file from the File Manager ("Open with" or Share): it works. (The file is copied to the share folder first.)

Only if the changelog lists them (check the section you published):

- [ ] Installing a downloaded app in ADB, Shizuku or root mode shows a dialog with the host the file came from, whether the app is new or replaces one, and the package and version read from the file; Cancel stops the install, and the dialog closes by itself after two minutes.
- [ ] Sharing, scanning with VirusTotal or opening with another app a file from the app's own private folders (`shared_prefs`, the adb key) is refused with a message; a file picked from storage or a patched APK still works.
- [ ] A 100 KB paste into the terminal while `yes` is running does not freeze it, and Ctrl-C still stops `yes`.

## 5. If something is wrong

- A bad build of the release: mark the release as a pre-release or delete it from the Releases page, say so in the issue tracker, and fix forward with a patch release (never reuse a tag for different files: phones and mirrors cache by version).
- A problem found only on one phone model: write down the model, Android version, working mode (standard, adb, Shizuku, root) and the exact step, then add a row to the shared findings backlog in `docs/COPILOT_CLAUDE_HANDOFF.md`.
