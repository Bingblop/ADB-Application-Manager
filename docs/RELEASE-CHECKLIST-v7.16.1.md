# v7.16.1 release: before, during and after

A patch release cut from `main` on top of v7.16.0. Its one change is the **privileged-mode unlock**: the **Root Features** tab and the per-app **component Enable / Disable** now unlock not only in Root mode with su granted, but also when the **Shizuku** server runs as the Android **system user (UID 1000)** or as **root (UID 0)** — the UIDs Android lets change a single component. Shizuku started over ADB (the shell user, **UID 2000**) still does not unlock them, and an unknown UID does not either. The stricter root-only tier — writing a fabricated colour overlay (the root theme card) — still needs **UID 0** (real root, or a Shizuku server started as root); Shizuku as UID 1000 keeps that one card locked. The Shizuku badge now names the server's real UID. UID 1000 is the system user, **not** root, and nothing in the app or the guide calls it root. The release pull request is this change plus the version bump. No permission is new and none is removed; package, folders, APK file names and the signing certificate are unchanged. Not run on a device.

## The sequence, in order

1. [ ] The change and the version bump are on the release pull request, merged into `main` so the tag points at the reviewed commit. The `## v7.16.1-Pro` section of `CHANGELOG.md` is the list of what ships; this page keeps no list of its own.
2. [ ] The bump changes only the version, the changelog, the README, the guides, `docs/LAUNCH.md`, the v7.16.1 section of `docs/COPILOT_CLAUDE_HANDOFF.md` and these checklists. The Help Guide carries the version from `AndroidManifest.xml`: after the bump, run `node docs/guide/build.js` and commit `assets/guide.js`, or the build job fails with "assets/guide.js is out of date".
3. [ ] Repository secrets are set: `KEYSTORE_BASE64` (the release keystore, base64), and `KEYSTORE_PASSWORD` and `KEY_ALIAS` if the keystore does not use the defaults (`password` and `adbmanager`). Without `KEYSTORE_BASE64` the run fails at "Prepare signing key".
4. [ ] Read the `## v7.16.1-Pro (versionCode 863)` section of `CHANGELOG.md` once as the person who will see it: the workflow copies it into the release text, so every sentence must be true of this build.
5. [ ] The whole UI suite and the Java suites are green on the tip of the release pull request (the `tests` workflow, or `node tests/run.js` and `node tests/java/run.js`). Known flaky scripts on the shared runners (each passes alone): t137, t261, t53/t54, t157 (the font-scan hook), t31/t33/t155 and t142/t97/t126/t13/t111 under load; Java `archivecancel`, `extractspace`, `sdmengine`, `ziptool`, `morphehelper` under load. A red job is re-run once; a second failure is read, not waved through.
5a. [ ] [PHONE-SCRIPT-v7.16.1.md](PHONE-SCRIPT-v7.16.1.md) has been offered to the owner, as `AGENTS.md` requires before a release.
5b. [ ] **Publishing is already authorised for this release.** The owner's instruction was to ship v7.16.1 as soon as the UID 1000 change is in, so the arm64 APK phone-test gate that held v7.15.0 and v7.16.0 is waived here. The unlock itself only shows on a phone whose Shizuku can run as UID 1000 or root (a rooted phone); on an ordinary phone Shizuku runs as UID 2000 and the features stay locked, unchanged — which is the part the owner can verify. Merge once CI is green.
6. [ ] Merge the release pull request, then start the release: Actions, "Build, sign and publish release", Run workflow, branch `main`, version `7.16.1` (no leading `v`, no `-Pro`), **leave "allow_throwaway_key" unticked**.
7. [ ] Wait for it to finish green. It first checks that `7.16.1` is the version in `AndroidManifest.xml` and in a `CHANGELOG.md` heading with the manifest's versionCode, then builds three APKs (`arm64-v8a`, `armeabi-v7a`, `universal`), writes `SHA256SUMS.txt`, checks the signing certificate against the pinned value, and publishes the release with the changelog section as its text.
8. [ ] Verify the published files (next section).
9. [ ] Install the arm64 file over v7.16.0 on a phone and work through [PHONE-SCRIPT-v7.16.1.md](PHONE-SCRIPT-v7.16.1.md) when a suitable phone is available.

## Verify the published release

Download the four files of the release into an empty folder, then from that folder (needs `apksigner` and `aapt2` from build-tools 35 on the PATH):

```sh
sha256sum -c SHA256SUMS.txt
ls -1 *.apk | wc -l
for f in ADB_Application_Manager_Pro-v7.16.1-*.apk; do
  echo "== $f"
  apksigner verify --verbose --print-certs "$f" | grep -E "Number of signers|Signer #1 certificate SHA-256"
  aapt2 dump badging "$f" | grep -E "^(package|application-label):"
done
```

- [ ] `sha256sum -c` says `OK` for every line, and exactly three `.apk` files are listed.
- [ ] For each APK: `Number of signers: 1`, and `Signer #1 certificate SHA-256 digest: 9461870f7cd2406406a119f4d37533c23bb7d1ed850506bad7ea3e6b4bd26d17` (the value in the README and in `release.yml`).
- [ ] For each APK, the `package:` line says `name='com.bloatware.bingblop'`, `versionCode='863'` and `versionName='7.16.1-Pro'`, and `aapt2 dump badging` lists `application-label:'ADB/Root Full System Toolbox'`.
- [ ] The arm64 file is the first one in the list of assets (an older copy of the app that updates itself takes the first `.apk`; so does the Obtainium configuration's `arm64-v8a` filter).
- [ ] The release text is the changelog section and nothing else (an empty body means the heading `## v7.16.1` was not found).
- [ ] If the digest differs, or the workflow printed the "throwaway key" warning: stop. Do not tell anyone to update; delete the release (or mark it a pre-release) and fix the secrets first. Phones refuse an update over a different certificate.

## After it finished: on a phone

Install the arm64 file over v7.16.0 (an update, not a fresh install). The steps are [PHONE-SCRIPT-v7.16.1.md](PHONE-SCRIPT-v7.16.1.md). In short:

- [ ] About says 7.16.1, and settings, saved lists, keys and pinned tabs are still there.
- [ ] With Shizuku started over ADB (UID 2000): Root Features and the component toggle stay locked with *Needs Super User (Root)*, and the Shizuku badge reads UID 2000 — unchanged.
- [ ] On a phone whose Shizuku runs as the system user (UID 1000) or root (UID 0): the Root Features tab and the component Enable / Disable unlock, the badge names the real UID, and at UID 1000 the root theme card stays locked (it needs UID 0) while at UID 0 it unlocks.

## If something is wrong

- A bad build of the release: mark the release as a pre-release or delete it from the Releases page, say so in the issue tracker, and fix forward with a patch release (never reuse a tag for different files: phones and mirrors cache by version).
- A problem found only on one phone model: open a phone bug report (the issue template asks for the working mode, the One UI version and the checklist step), and add a row to the shared findings backlog in `docs/COPILOT_CLAUDE_HANDOFF.md`.
