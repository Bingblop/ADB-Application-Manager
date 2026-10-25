# v7.16.2 release: before, during and after

A feature release cut from `main` on top of v7.16.1, with three changes the owner asked for on 2026-10-25, each merged as its own pull request: **Live Logcat** colours and speed (#322), **saved lists edited by ticking apps** (#323) and **switching an app's trackers off and on** with Super User (#324); and, at his word the same evening, the held drafts of 2026-10-12 brought up to date: the **Connection Check** card with **Turn on Wireless Debugging** (#167, #177), **Wireless Debugging reconnect over mDNS** (#168) and **Screenshot / Record screen** in the Console (#179). The tracker switch is gated exactly like the per-app component switch: Root mode with su granted, or Shizuku running as the system user (UID 1000) or root (UID 0); Shizuku over ADB (UID 2000) and an unknown UID stay locked. The release pull request is the version bump on top of them. No permission is new and none is removed; package, folders, APK file names and the signing certificate are unchanged. Not run on a device.

## The sequence, in order

1. [ ] #322, #323, #324, #167 (with #177), #168 and #179 are merged into `main`, and the version bump is on the release pull request, merged into `main` so the tag points at the reviewed commit. The `## v7.16.2-Pro` section of `CHANGELOG.md` is the list of what ships; this page keeps no list of its own.
2. [ ] The bump changes only the version, the changelog, the README, the guides, `docs/LAUNCH.md`, the v7.16.2 section of `docs/COPILOT_CLAUDE_HANDOFF.md`, the device checklist and these checklists. The Help Guide carries the version from `AndroidManifest.xml`: after the bump, run `node docs/guide/build.js` and commit `assets/guide.js`, or the build job fails with "assets/guide.js is out of date".
3. [ ] Repository secrets are set: `KEYSTORE_BASE64` (the release keystore, base64), and `KEYSTORE_PASSWORD` and `KEY_ALIAS` if the keystore does not use the defaults (`password` and `adbmanager`). Without `KEYSTORE_BASE64` the run fails at "Prepare signing key".
4. [ ] Read the `## v7.16.2-Pro (versionCode 864)` section of `CHANGELOG.md` once as the person who will see it: the workflow copies it into the release text, so every sentence must be true of this build.
5. [ ] The whole UI suite and the Java suites are green on the tip of the release pull request (the `tests` workflow, or `node tests/run.js` and `node tests/java/run.js`). Known flaky scripts on the shared runners (each passes alone): t137, t261, t53/t54, t157 (the font-scan hook), t31/t33/t155 and t142/t97/t126/t13/t111 under load; Java `archivecancel`, `extractspace`, `sdmengine`, `ziptool`, `morphehelper` under load. A red job is re-run once; a second failure is read, not waved through.
5a. [ ] Sections 34 to 40 of [DEVICE-TEST-CHECKLIST.md](DEVICE-TEST-CHECKLIST.md), as `AGENTS.md` requires before a release, and their one-page form, [PHONE-SCRIPT-v7.16.2.md](PHONE-SCRIPT-v7.16.2.md), have been offered to the owner.
5b. [ ] **Publishing is already authorised for this release.** The owner asked for the three changes on 2026-10-25 and then to "Release the updates"; as for v7.16.1, the release ships without the arm64 APK phone-test gate. Sections A to D and F of the phone script work on an ordinary phone; the tracker switch itself only shows on a rooted phone (Root with su, or Shizuku started as root or the system user). Merge once CI is green.
6. [ ] Merge the release pull request, then start the release: Actions, "Build, sign and publish release", Run workflow, branch `main`, version `7.16.2` (no leading `v`, no `-Pro`), **leave "allow_throwaway_key" unticked**.
7. [ ] Wait for it to finish green. It first checks that `7.16.2` is the version in `AndroidManifest.xml` and in a `CHANGELOG.md` heading with the manifest's versionCode, then builds three APKs (`arm64-v8a`, `armeabi-v7a`, `universal`), writes `SHA256SUMS.txt`, checks the signing certificate against the pinned value, and publishes the release with the changelog section as its text.
8. [ ] Verify the published files (next section).
9. [ ] Install the arm64 file over v7.16.1 on a phone and work through [PHONE-SCRIPT-v7.16.2.md](PHONE-SCRIPT-v7.16.2.md) when a suitable phone is available.

## Verify the published release

Download the four files of the release into an empty folder, then from that folder (needs `apksigner` and `aapt2` from build-tools 35 on the PATH):

```sh
sha256sum -c SHA256SUMS.txt
ls -1 *.apk | wc -l
for f in ADB_Application_Manager_Pro-v7.16.2-*.apk; do
  echo "== $f"
  apksigner verify --verbose --print-certs "$f" | grep -E "Number of signers|Signer #1 certificate SHA-256"
  aapt2 dump badging "$f" | grep -E "^(package|application-label):"
done
```

- [ ] `sha256sum -c` says `OK` for every line, and exactly three `.apk` files are listed.
- [ ] For each APK: `Number of signers: 1`, and `Signer #1 certificate SHA-256 digest: 9461870f7cd2406406a119f4d37533c23bb7d1ed850506bad7ea3e6b4bd26d17` (the value in the README and in `release.yml`).
- [ ] For each APK, the `package:` line says `name='com.bloatware.bingblop'`, `versionCode='864'` and `versionName='7.16.2-Pro'`, and `aapt2 dump badging` lists `application-label:'ADB/Root Full System Toolbox'`.
- [ ] The arm64 file is the first one in the list of assets (an older copy of the app that updates itself takes the first `.apk`; so does the Obtainium configuration's `arm64-v8a` filter).
- [ ] The release text is the changelog section and nothing else (an empty body means the heading `## v7.16.2` was not found).
- [ ] If the digest differs, or the workflow printed the "throwaway key" warning: stop. Do not tell anyone to update; delete the release (or mark it a pre-release) and fix the secrets first. Phones refuse an update over a different certificate.

## After it finished: on a phone

Install the arm64 file over v7.16.1 (an update, not a fresh install). The steps are [PHONE-SCRIPT-v7.16.2.md](PHONE-SCRIPT-v7.16.2.md). In short:

- [ ] About says 7.16.2, and settings, saved lists, keys and pinned tabs are still there.
- [ ] Live Logcat: every row is its level's colour from end to end, matching the colour key, in dark, light and pure black; a busy log scrolls smoothly and follows the newest line only at the bottom.
- [ ] Saved Applications: New List and Edit tick apps on this phone's rows, count what was added and removed, and keep a NOT INSTALLED row for a package that is not on the phone.
- [ ] Trackers: with Shizuku over ADB (UID 2000) the Trackers box, Disable Trackers / Enable Trackers and the Root Features row are locked and switch nothing; on a rooted phone (Root with su, or Shizuku as UID 1000 or 0) they switch an app's tracker parts off and on, and History's Undo switches back exactly what its step switched off.
- [ ] Working Modes: the Connection Check card lists the five rows with a reason each, Turn on Wireless Debugging switches it on, and after Wireless debugging is switched off and on the app reconnects on the new port by itself.
- [ ] Console: Screenshot saves a PNG in Pictures/ADB App Manager and Record screen an MP4 in Movies/ADB App Manager that plays to the end.

## If something is wrong

- A bad build of the release: mark the release as a pre-release or delete it from the Releases page, say so in the issue tracker, and fix forward with a patch release (never reuse a tag for different files: phones and mirrors cache by version).
- A problem found only on one phone model: open a phone bug report (the issue template asks for the working mode, the One UI version and the checklist step), and add a row to the shared findings backlog in `docs/COPILOT_CLAUDE_HANDOFF.md`.
