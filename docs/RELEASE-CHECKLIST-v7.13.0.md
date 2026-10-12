# v7.13.0 release: before, during and after

The release is run from the branch `release/v7.12.12` (the branch kept the name the work started under; the version it ships is 7.13.0, versionCode 854), not from `main`, on the same grounds as v7.12.10 and v7.12.11: `main` carries direct pushes that are not part of this release (the Kotlin-era console markup, a Content-Security-Policy tag, and the project thread's own merges). The branch starts at the commit of the v7.12.11 release (tag `v7.12.11`). The `release.yml` workflow is started on that branch with the version `7.13.0`, and the branch is merged into `main` afterwards. The owner decided on 2026-10-12 that there is no release candidate: this build is the release. **On a phone, only the three Private DNS read-back checks of section 21 were run (by the owner, on v7.12.11); nothing else in this release has been run on a phone by the people and tools that wrote it.**

## The sequence, in order

1. [ ] Every pull request named in the `## v7.13.0-Pro` section of `CHANGELOG.md` is merged into `release/v7.12.12`: #156, #157, #158, #159, #160, #161, #162, #163, #164, #170, #172, #173 (the port of #165), #174 (the dictionary dedupe), #175 (Connected Devices wording), and the version bump. The changelog is the list; this page keeps no list of its own.
2. [ ] The branch changes only what the changelog says, the version, the README, the guides and these checklists. Nothing else is added to it before the release.
3. [ ] Repository secrets are set: `KEYSTORE_BASE64` (the release keystore, base64), and `KEYSTORE_PASSWORD` and `KEY_ALIAS` if the keystore does not use the defaults (`password` and `adbmanager`). Without `KEYSTORE_BASE64` the run fails at "Prepare signing key".
4. [ ] Read the `## v7.13.0-Pro (versionCode 854)` section of `CHANGELOG.md` once as the person who will see it: the workflow copies it into the release text, so every sentence must be true of this build.
5. [ ] The whole UI suite and the Java suites are green on the tip of the branch (the `tests` workflow, or `node tests/run.js` and `node tests/java/run.js`), and the version-bump pull request is merged into `release/v7.12.12`, so the tag points at the reviewed commit. Known flaky scripts on the shared runners (not caused by any pull request of this release; each passes alone): t137, t53/t54, t157 (the font-scan hook), t31/t33/t155 and t142/t97/t126/t13/t111 under load; Java `archivecancel`, `extractspace`, `ziptool`, `morphehelper` under load. A red job is re-run once; a second failure is read, not waved through. **Integration run on the release head after #164 (3be3407), on a computer, 2026-10-12:** all 175 UI scripts passed with two parallel jobs (980 s); 101 of 107 Java suites passed, 6 were skipped for a missing prerequisite (`storedetail`, `fdroid` without Robolectric's android-all jar, `morpheengine`, `signer`, `signwritefail`, `signmismatch`), and the two that failed while the UI suite ran beside them (`extractspace`, `sdmengine`) passed when run alone.
5a. [ ] The on-device checklist ([DEVICE-TEST-CHECKLIST.md](DEVICE-TEST-CHECKLIST.md), sections 21 and 23 to 27) has been offered to the owner, as `AGENTS.md` requires before a release, and the owner has said to release (for v7.13.0: the owner ran the three Private DNS checks of section 21 on v7.12.11, said there would be no release candidate, and asked for the release to be finished and put out).
6. [ ] Start it: Actions, "Build, sign and publish release", Run workflow, branch `release/v7.12.12`, version `7.13.0` (no leading `v`, no `-Pro`), **leave "allow_throwaway_key" unticked**. This publishes from the release branch before it is merged into `main`, which differs from the order in `AGENTS.md` (CI green, merge, `release.yml`); it is the owner's approved exception (see the top of this page). The branch is merged into `main` right after (step 8a).
7. [ ] Wait for it to finish green. It builds three APKs (`arm64-v8a`, `armeabi-v7a`, `universal`), writes `SHA256SUMS.txt`, checks the signing certificate against the pinned value, and publishes the release with the changelog section as its text.
8. [ ] Verify the published files (next section).
8a. [ ] Merge `release/v7.12.12` into `main`, resolving the conflicts with the direct pushes to `main`, and run the whole UI suite on the result.
9. [ ] Install the arm64 file over the previous version on a phone and run the checks below.

## Verify the published release

Download the four files of the release into an empty folder, then from that folder (needs `apksigner` and `aapt2` from build-tools 35 on the PATH):

```sh
sha256sum -c SHA256SUMS.txt
ls -1 *.apk | wc -l
for f in ADB_Application_Manager_Pro-v7.13.0-*.apk; do
  echo "== $f"
  apksigner verify --print-certs "$f" | grep -E "Number of signers|Signer #1 certificate SHA-256"
  aapt2 dump badging "$f" | head -1
done
```

- [ ] `sha256sum -c` says `OK` for every line, and exactly three `.apk` files are listed.
- [ ] For each APK: `Number of signers: 1`, and `Signer #1 certificate SHA-256 digest: 9461870f7cd2406406a119f4d37533c23bb7d1ed850506bad7ea3e6b4bd26d17` (the value in the README and in `release.yml`).
- [ ] For each APK, the `package:` line says `name='com.bloatware.bingblop'`, `versionCode='854'` and `versionName='7.13.0-Pro'`.
- [ ] The arm64 file is the first one in the list of assets (an older copy of the app that updates itself takes the first `.apk`).
- [ ] The release text is the changelog section and nothing else (an empty body means the heading `## v7.13.0` was not found).
- [ ] If the digest differs, or the workflow printed the "throwaway key" warning: stop. Do not tell anyone to update; delete the release (or mark it a pre-release) and fix the secrets first. Phones refuse an update over a different certificate.

## After it finished: on a phone

Install the arm64 file over the previous version (an update, not a fresh install). The steps are in [DEVICE-TEST-CHECKLIST.md](DEVICE-TEST-CHECKLIST.md): section 21 (Private DNS and the speed test), 23 (the v7.12.12 fixes, the DUMP row, the ShizuStore button), 24 (the Console tab), 25 (power, Doze, zram), 26 (animation, brightness, telemetry) and 27 (the bottom bar and its shortcuts). In short:

- [ ] The app opens, the About tab says 7.13.0, and settings, saved lists and keys are still there. The tabs the phone had pinned or reordered are kept; the bottom bar shows Apps, Installer, Extra, Console and More.
- [ ] Extra Settings > Private DNS: **Test speed** fills the tiles; AdGuard DNS is applied and read back; **Automatic** puts it back (the owner's three checks passed on v7.12.11; the speed test is new to a phone).
- [ ] Extra Settings > Animation speed: 0.5× applied and read back ("The phone holds: 0.5× · 0.5× · 0.5×"), then **Reset to 1×**.
- [ ] Extra Settings > Power & reboot: **Restart SystemUI** asks first and does what it says; the three dangerous tiles need the press-and-hold.
- [ ] Console tab: a command in ADB Shell shows its card; Live Logcat streams only while on screen; the Terminal tab still opens the full-screen terminal.
- [ ] Hold the Installer slot of the bottom bar: the shortcut menu opens; **Update All** still shows the install question.
- [ ] Installer > Application Updater: **Check for Updates** streams rows, Cancel stops it, and a host that is down is named once.
- [ ] App Stores > ShizuStore: every row has an Install button and it asks before installing.

## If something is wrong

- A bad build of the release: mark the release as a pre-release or delete it from the Releases page, say so in the issue tracker, and fix forward with a patch release (never reuse a tag for different files: phones and mirrors cache by version).
- A problem found only on one phone model: write down the model, Android version, working mode (standard, adb, Shizuku, root) and the exact step, then add a row to the shared findings backlog in `docs/COPILOT_CLAUDE_HANDOFF.md`.
