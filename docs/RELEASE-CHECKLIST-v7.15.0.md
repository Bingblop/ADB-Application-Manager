# v7.15.0 release: before, during and after

A release cut from `main` on top of v7.14.1. It ships the top-to-bottom UI pass the owner asked for on 2026-10-18 and the new name, ADB Full System Manager: part 1 (#251, the header, colours, bottom bar, Apps and the app menu) and parts 2 to 4 with this version bump (#252). No permission is new and none is removed; package, folders, APK file names and the signing certificate are unchanged. Not run on a device.

## The sequence, in order

1. [ ] #251 is merged into `main` (it is), and #252 (parts 2 to 4 and this bump) is merged after the owner's word, so the tag points at the reviewed commit. The `## v7.15.0-Pro` section of `CHANGELOG.md` is the list of what ships; this page keeps no list of its own.
2. [ ] The bump changes only the version, the changelog, the README, the guides, `docs/LAUNCH.md`, the v7.15.0 section of `docs/COPILOT_CLAUDE_HANDOFF.md` and these checklists. The Help Guide carries the version from `AndroidManifest.xml`: after the bump, run `node docs/guide/build.js` and commit `assets/guide.js`, or the build job fails with "assets/guide.js is out of date".
3. [ ] Repository secrets are set: `KEYSTORE_BASE64` (the release keystore, base64), and `KEYSTORE_PASSWORD` and `KEY_ALIAS` if the keystore does not use the defaults (`password` and `adbmanager`). Without `KEYSTORE_BASE64` the run fails at "Prepare signing key".
4. [ ] Read the `## v7.15.0-Pro (versionCode 861)` section of `CHANGELOG.md` once as the person who will see it: the workflow copies it into the release text, so every sentence must be true of this build.
5. [ ] The whole UI suite and the Java suites are green on the tip of #252 (the `tests` workflow, or `node tests/run.js` and `node tests/java/run.js`). Known flaky scripts on the shared runners (each passes alone): t137, t261, t53/t54, t157 (the font-scan hook), t31/t33/t155 and t142/t97/t126/t13/t111 under load; Java `archivecancel`, `extractspace`, `sdmengine`, `ziptool`, `morphehelper` under load. A red job is re-run once; a second failure is read, not waved through.
5a. [ ] [PHONE-SCRIPT-v7.15.0.md](PHONE-SCRIPT-v7.15.0.md) has been offered to the owner, as `AGENTS.md` requires before a release.
5b. [ ] **The owner's gate (2026-10-18, 03:15Z):** "send me the APK to test and then I'll let you know if you can publish it." The file is the `arm64-v8a` APK of #252's "Build APK" artifact (signed with the release key, so it installs over v7.14.1). It has been sent to him, and he has reported back; whatever he found is fixed on #252 (and a new APK sent) before step 6. **Do not merge #252 or start step 6 until he has said to publish.**
6. [ ] Merge #252, then start the release: Actions, "Build, sign and publish release", Run workflow, branch `main`, version `7.15.0` (no leading `v`, no `-Pro`), **leave "allow_throwaway_key" unticked**.
7. [ ] Wait for it to finish green. It first checks that `7.15.0` is the version in `AndroidManifest.xml` and in a `CHANGELOG.md` heading with the manifest's versionCode, then builds three APKs (`arm64-v8a`, `armeabi-v7a`, `universal`), writes `SHA256SUMS.txt`, checks the signing certificate against the pinned value, and publishes the release with the changelog section as its text.
8. [ ] Verify the published files (next section).
9. [ ] Install the arm64 file over v7.14.1 on a phone and work through [PHONE-SCRIPT-v7.15.0.md](PHONE-SCRIPT-v7.15.0.md) (the owner's test of the #252 APK counts if the published file has the same version and certificate).

## Verify the published release

Download the four files of the release into an empty folder, then from that folder (needs `apksigner` and `aapt2` from build-tools 35 on the PATH):

```sh
sha256sum -c SHA256SUMS.txt
ls -1 *.apk | wc -l
for f in ADB_Application_Manager_Pro-v7.15.0-*.apk; do
  echo "== $f"
  apksigner verify --verbose --print-certs "$f" | grep -E "Number of signers|Signer #1 certificate SHA-256"
  aapt2 dump badging "$f" | head -1
done
```

- [ ] `sha256sum -c` says `OK` for every line, and exactly three `.apk` files are listed.
- [ ] For each APK: `Number of signers: 1`, and `Signer #1 certificate SHA-256 digest: 9461870f7cd2406406a119f4d37533c23bb7d1ed850506bad7ea3e6b4bd26d17` (the value in the README and in `release.yml`).
- [ ] For each APK, the `package:` line says `name='com.bloatware.bingblop'`, `versionCode='861'` and `versionName='7.15.0-Pro'`, and `aapt2 dump badging` lists `application-label:'ADB Full System Manager'`.
- [ ] The arm64 file is the first one in the list of assets (an older copy of the app that updates itself takes the first `.apk`; so does the Obtainium configuration's `arm64-v8a` filter).
- [ ] The release text is the changelog section and nothing else (an empty body means the heading `## v7.15.0` was not found).
- [ ] If the digest differs, or the workflow printed the "throwaway key" warning: stop. Do not tell anyone to update; delete the release (or mark it a pre-release) and fix the secrets first. Phones refuse an update over a different certificate.

## After it finished: on a phone

Install the arm64 file over v7.14.1 (an update, not a fresh install). The steps are [PHONE-SCRIPT-v7.15.0.md](PHONE-SCRIPT-v7.15.0.md). In short:

- [ ] The launcher and the app say ADB Full System Manager, About says 7.15.0, and settings, saved lists, keys and pinned tabs are still there (step 1).
- [ ] The header is one colour with only the Android and One UI versions under it, and the mode badge reads well in dark and light (steps 2 and 3).
- [ ] The long-press menus of Apps, Console and Files (steps 6, 18 and 22).
- [ ] Every tab's screen looks as the script says (sections B to F); nothing overlaps or runs off the screen.
- [ ] On Android 16 or newer, the Stop apps Live Update chip (step 26, left over from v7.14.1).

## If something is wrong

- A bad build of the release: mark the release as a pre-release or delete it from the Releases page, say so in the issue tracker, and fix forward with a patch release (never reuse a tag for different files: phones and mirrors cache by version).
- A problem found only on one phone model: open a phone bug report (the issue template asks for the working mode, the One UI version and the checklist step), and add a row to the shared findings backlog in `docs/COPILOT_CLAUDE_HANDOFF.md`.
