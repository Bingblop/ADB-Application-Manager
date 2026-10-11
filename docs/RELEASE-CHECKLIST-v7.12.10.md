# v7.12.10 release: before, during and after

The release is a pull request (this one), then the `release.yml` workflow with the version `7.12.10`. **Nothing here has been run on a phone by the people and tools that wrote it.** The workflow is started by the owner, never by a tool: this page says what to look at before and after.

## The sequence, in order

1. [ ] Every pull request that belongs in the release is merged into `main`: the native install confirmation (#109), the audit fixes (#119 to #122, #123, #124, #125 and the Stop fix M-4 if it is in the changelog) and whatever the phone check of v7.12.9 turned up. CI on `main` is green for the head commit.
2. [ ] The release pull request is merged. It changes no code, only the version, the changelog, the README, the guides and these checklists.
3. [ ] Repository secrets are set: `KEYSTORE_BASE64` (the release keystore, base64), and `KEYSTORE_PASSWORD` and `KEY_ALIAS` if the keystore does not use the defaults (`password` and `adbmanager`). Without `KEYSTORE_BASE64` the run now fails at "Prepare signing key"; it no longer publishes anything.
4. [ ] Read the `## v7.12.10-Pro (versionCode 852)` section of `CHANGELOG.md` once as the person who will see it: the workflow copies it into the release text, so every sentence must be true of this build.
5. [ ] The quick phone check ([PHONE-QUICK-CHECK-v7.12.9.md](PHONE-QUICK-CHECK-v7.12.9.md)) has been offered to the owner (AGENTS.md asks for that before a release). It can be run on a build from `main` (`./build.sh`) before the release exists.
6. [ ] Start it: Actions, "Build, sign and publish release", Run workflow, branch `main`, version `7.12.10` (no leading `v`, no `-Pro`), **leave "allow_throwaway_key" unticked**. A tag `v7.12.10` pushed to the repository starts the same workflow; use one or the other, not both. The workflow always publishes a real GitHub release, so a "test run" creates one: use a version such as `7.12.10-test` and delete the release and its tag afterwards.
7. [ ] Wait for it to finish green. It builds three APKs (`arm64-v8a`, `armeabi-v7a`, `universal`), writes `SHA256SUMS.txt`, checks the signing certificate against the pinned value (this check has not run before in a real release) and publishes.
8. [ ] Verify the published files (next section).
9. [ ] Install the arm64 file over the previous version on a phone and run the quick phone check.

## Verify the published release

Download the four files of the release into an empty folder, then from that folder (needs `apksigner` and `aapt2` from build-tools 35 on the PATH):

```sh
sha256sum -c SHA256SUMS.txt
ls -1 *.apk | wc -l
for f in ADB_Application_Manager_Pro-v7.12.10-*.apk; do
  echo "== $f"
  apksigner verify --print-certs "$f" | grep -E "Number of signers|Signer #1 certificate SHA-256"
  aapt2 dump badging "$f" | head -1
done
```

- [ ] `sha256sum -c` says `OK` for every line, and exactly three `.apk` files are listed.
- [ ] For each APK: `Number of signers: 1`, and `Signer #1 certificate SHA-256 digest: 9461870f7cd2406406a119f4d37533c23bb7d1ed850506bad7ea3e6b4bd26d17` (the value in the README and in `release.yml`).
- [ ] For each APK, the `package:` line says `name='com.bloatware.bingblop'`, `versionCode='852'` and `versionName='7.12.10-Pro'`.
- [ ] The arm64 file is the first one in the list of assets (an older copy of the app that updates itself takes the first `.apk`).
- [ ] The release text is the changelog section and nothing else (an empty body means the heading `## v7.12.10` was not found).
- [ ] If the digest differs, or the workflow printed the "throwaway key" warning: stop. Do not tell anyone to update; delete the release (or mark it a pre-release) and fix the secrets first. Phones refuse an update over a different certificate, and users who uninstall to get around that lose their data.

## After it finished: on a phone

Install the arm64 file over the previous version (an update, not a fresh install), then follow [PHONE-QUICK-CHECK-v7.12.9.md](PHONE-QUICK-CHECK-v7.12.9.md) (the longer [DEVICE-TEST-SCRIPT.md](DEVICE-TEST-SCRIPT.md) has the full adb commands). In short:

- [ ] The app opens, the About tab says 7.12.10, and settings, saved lists and the GitHub token and VirusTotal key (if they were set) are still there. The keys were moved into the Keystore vault on first use: set a VirusTotal key, close the app completely, open it again and check the key is still accepted.
- [ ] Terminal (full screen): open it, run `sleep 30`, press Restart, type `echo ok`: the answer appears once and the terminal accepts input and closes. Do it three times quickly.
- [ ] Connected Devices, with a device connected: `adb devices` works; `adb -P 5037 devices` and `adb kill-server` are refused with the message about another server.
- [ ] ADB cheat sheet: the full-reference button opens the gist in the browser and the page itself is unchanged.
- [ ] Morphe Patcher tab: it opens, the list of apps shows, tapping a card opens its details.
- [ ] APK Installer/Updater: an update through the system installer (standard mode) still shows the system installer's question.
- [ ] Dex optimization sheet: **space** and **reset** are offered; **Standby** in the app menu shows a bucket and changes it.
- [ ] Share a picked file from the File Manager ("Open with" or Share): it works. (The file is copied to the share folder first.)

Also in this release (see section 20 of [DEVICE-TEST-CHECKLIST.md](DEVICE-TEST-CHECKLIST.md) for the full list):

- [ ] Install question (#109): installing a downloaded app in ADB, Shizuku or root mode shows a dialog with the host the file came from, whether the app is new or replaces one, and the package and version read from the file; Cancel stops the install, and the dialog closes by itself after two minutes.
- [ ] Already in v7.12.9, so only a quick look: in the File Manager, opening, sharing or editing a file from the app's own private folders (`shared_prefs`, the adb key) is refused with a message; a file picked from storage, a patched APK and the Helper's downloads still work (see PHONE-QUICK-CHECK, step 4).
- [ ] A 100 KB paste into the terminal while `yes` is running does not freeze it, and Ctrl-C still stops `yes`.

## If something is wrong

- A bad build of the release: mark the release as a pre-release or delete it from the Releases page, say so in the issue tracker, and fix forward with a patch release (never reuse a tag for different files: phones and mirrors cache by version).
- A problem found only on one phone model: write down the model, Android version, working mode (standard, adb, Shizuku, root) and the exact step, then add a row to the shared findings backlog in `docs/COPILOT_CLAUDE_HANDOFF.md`.

## Also in this release

- [ ] Archives: a small zip and tar.gz still extract; a tar with a hard link (`ln a b; tar cf t.tar a b`) gives two files with the same content.
- [ ] Morphe Patcher: importing a text file as a signing key is refused and the old key stays; patching still works; Patched APKs lists the result.
