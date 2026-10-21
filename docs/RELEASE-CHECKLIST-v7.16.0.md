# v7.16.0 release: before, during and after

A release cut from `main` on top of v7.15.0. It ships #253 to #283: the Root Features tab and its Super User hand-offs, the root theme card on the RRO/Monet Customization tab (Pure black, Color spec, More styles, Intensity, Secondary and tertiary accents, Accurate shades) for Android 14 to 17 and their quarterly updates, the CMF palette style, Start Shizuku (ADB) and its launch offer, Allow all restarting the app, the Kill Apps and Working mode tiles, the list-name question in Saved Applications, and the fixes of two test passes. The release pull request is the version bump only. No permission is new and none is removed (the manifest gained a Quick Settings long-press alias to MainActivity in #253, no permission); package, folders, APK file names and the signing certificate are unchanged. Not run on a device.

## The sequence, in order

1. [ ] #253 to #283 are merged into `main`, and the release pull request (this version bump on top of #283) is merged after the owner's word, so the tag points at the reviewed commit. The `## v7.16.0-Pro` section of `CHANGELOG.md` is the list of what ships; this page keeps no list of its own.
2. [ ] The bump changes only the version, the changelog, the README, the guides, `docs/LAUNCH.md`, the v7.16.0 section of `docs/COPILOT_CLAUDE_HANDOFF.md` and these checklists. The Help Guide carries the version from `AndroidManifest.xml`: after the bump, run `node docs/guide/build.js` and commit `assets/guide.js`, or the build job fails with "assets/guide.js is out of date".
3. [ ] Repository secrets are set: `KEYSTORE_BASE64` (the release keystore, base64), and `KEYSTORE_PASSWORD` and `KEY_ALIAS` if the keystore does not use the defaults (`password` and `adbmanager`). Without `KEYSTORE_BASE64` the run fails at "Prepare signing key".
4. [ ] Read the `## v7.16.0-Pro (versionCode 862)` section of `CHANGELOG.md` once as the person who will see it: the workflow copies it into the release text, so every sentence must be true of this build.
5. [ ] The whole UI suite and the Java suites are green on the tip of the release pull request (the `tests` workflow, or `node tests/run.js` and `node tests/java/run.js`). Known flaky scripts on the shared runners (each passes alone): t137, t261, t53/t54, t157 (the font-scan hook), t31/t33/t155 and t142/t97/t126/t13/t111 under load; Java `archivecancel`, `extractspace`, `sdmengine`, `ziptool`, `morphehelper` under load. A red job is re-run once; a second failure is read, not waved through.
5a. [ ] [PHONE-SCRIPT-v7.16.0.md](PHONE-SCRIPT-v7.16.0.md) has been offered to the owner, as `AGENTS.md` requires before a release.
5b. [ ] **The owner's test before publishing**, as for v7.15.0: the `arm64-v8a` APK of the release pull request's "Build APK" artifact (signed with the release key, so it installs over v7.15.0) is sent to him, and he has reported back; whatever he found is fixed on the pull request (and a new APK sent) before step 6. The root sections of the phone script (C and D) need a rooted phone; if none was used, the owner decides whether to publish with them untested (the changelog already says the root theme colours were checked only on a computer). **Do not merge the release pull request or start step 6 until he has said to publish.**
6. [ ] Merge the release pull request, then start the release: Actions, "Build, sign and publish release", Run workflow, branch `main`, version `7.16.0` (no leading `v`, no `-Pro`), **leave "allow_throwaway_key" unticked**.
7. [ ] Wait for it to finish green. It first checks that `7.16.0` is the version in `AndroidManifest.xml` and in a `CHANGELOG.md` heading with the manifest's versionCode, then builds three APKs (`arm64-v8a`, `armeabi-v7a`, `universal`), writes `SHA256SUMS.txt`, checks the signing certificate against the pinned value, and publishes the release with the changelog section as its text.
8. [ ] Verify the published files (next section).
9. [ ] Install the arm64 file over v7.15.0 on a phone and work through [PHONE-SCRIPT-v7.16.0.md](PHONE-SCRIPT-v7.16.0.md) (the owner's test of the release pull request's APK counts if the published file has the same version and certificate).

## Verify the published release

Download the four files of the release into an empty folder, then from that folder (needs `apksigner` and `aapt2` from build-tools 35 on the PATH):

```sh
sha256sum -c SHA256SUMS.txt
ls -1 *.apk | wc -l
for f in ADB_Application_Manager_Pro-v7.16.0-*.apk; do
  echo "== $f"
  apksigner verify --verbose --print-certs "$f" | grep -E "Number of signers|Signer #1 certificate SHA-256"
  aapt2 dump badging "$f" | grep -E "^(package|application-label):"
done
```

- [ ] `sha256sum -c` says `OK` for every line, and exactly three `.apk` files are listed.
- [ ] For each APK: `Number of signers: 1`, and `Signer #1 certificate SHA-256 digest: 9461870f7cd2406406a119f4d37533c23bb7d1ed850506bad7ea3e6b4bd26d17` (the value in the README and in `release.yml`).
- [ ] For each APK, the `package:` line says `name='com.bloatware.bingblop'`, `versionCode='862'` and `versionName='7.16.0-Pro'`, and `aapt2 dump badging` lists `application-label:'ADB/Root Full System Toolbox'`.
- [ ] The arm64 file is the first one in the list of assets (an older copy of the app that updates itself takes the first `.apk`; so does the Obtainium configuration's `arm64-v8a` filter).
- [ ] The release text is the changelog section and nothing else (an empty body means the heading `## v7.16.0` was not found).
- [ ] If the digest differs, or the workflow printed the "throwaway key" warning: stop. Do not tell anyone to update; delete the release (or mark it a pre-release) and fix the secrets first. Phones refuse an update over a different certificate.

## After it finished: on a phone

Install the arm64 file over v7.15.0 (an update, not a fresh install). The steps are [PHONE-SCRIPT-v7.16.0.md](PHONE-SCRIPT-v7.16.0.md). In short:

- [ ] About says 7.16.0, and settings, saved lists, keys and pinned tabs are still there; Palette Style has ten styles, CMF last (steps 1 and 2).
- [ ] Start Shizuku (ADB), the launch offer, the Working mode and Kill Apps tiles, and Allow all coming back by itself (steps 3 to 7).
- [ ] On a rooted phone: Root Features unlocks after the grant in the manager, and the component toggle works only with Root (steps 10 and 11).
- [ ] On a rooted phone with Android 14 or newer: the root theme card writes what its preview shows, and Default puts Android's theme back (steps 12 to 16).

## If something is wrong

- A bad build of the release: mark the release as a pre-release or delete it from the Releases page, say so in the issue tracker, and fix forward with a patch release (never reuse a tag for different files: phones and mirrors cache by version).
- A problem found only on one phone model: open a phone bug report (the issue template asks for the working mode, the One UI version and the checklist step), and add a row to the shared findings backlog in `docs/COPILOT_CLAUDE_HANDOFF.md`.
