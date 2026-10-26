# v7.17.0 release: before, during and after

A feature release cut from `main` on top of v7.16.2, with the one change the owner asked for on 2026-10-26, merged as its own pull request (#326): a **Custom Fonts** tab that makes a Samsung (FlipFont) font package from a `.ttf` / `.otf` (a Regular, a Bold from a second file or the same variable font at another weight, colour emoji inside, a name), downloads five emoji fonts, installs, exports and makes the packages again, puts a font on the phone with the **Font changer** (Root with su granted, or Shizuku running as the system user, UID 1000, or root, UID 0), and with root (Root with su, or Shizuku as UID 0) makes a downloaded emoji font the phone's emoji until the next restart (**System emoji**). Making a font needs no working mode. No permission is new and none is removed; package, folders, APK file names and the signing certificate are unchanged. The font engine was checked on a computer against fontTools, HarfBuzz, FreeType, aapt2 and apksigner; not run on a device.

## The sequence, in order

1. [ ] #326 is merged into `main`, and the version bump is on the release pull request, merged into `main` so the tag points at the reviewed commit. The `## v7.17.0-Pro` section of `CHANGELOG.md` is the list of what ships; this page keeps no list of its own.
2. [ ] The bump changes only the version, the changelog, the README, the guides, `docs/LAUNCH.md`, the v7.17.0 section of `docs/COPILOT_CLAUDE_HANDOFF.md`, the device checklist and these checklists. The Help Guide carries the version from `AndroidManifest.xml`: after the bump, run `node docs/guide/build.js` and commit `assets/guide.js`, or the build job fails with "assets/guide.js is out of date".
3. [ ] Repository secrets are set: `KEYSTORE_BASE64` (the release keystore, base64), and `KEYSTORE_PASSWORD` and `KEY_ALIAS` if the keystore does not use the defaults (`password` and `adbmanager`). Without `KEYSTORE_BASE64` the run fails at "Prepare signing key".
4. [ ] Read the `## v7.17.0-Pro (versionCode 865)` section of `CHANGELOG.md` once as the person who will see it: the workflow copies it into the release text, so every sentence must be true of this build.
5. [ ] The whole UI suite and the Java suites are green on the tip of the release pull request (the `tests` workflow, or `node tests/run.js` and `node tests/java/run.js`). Known flaky scripts on the shared runners (each passes alone): t137, t261, t53/t54, t157 (the font-scan hook), t31/t33/t155 and t142/t97/t126/t13/t111 under load; Java `archivecancel`, `extractspace`, `sdmengine`, `ziptool`, `morphehelper` under load. A red job is re-run once; a second failure is read, not waved through.
5a. [ ] Sections 41 to 46 of [DEVICE-TEST-CHECKLIST.md](DEVICE-TEST-CHECKLIST.md), as `AGENTS.md` requires before a release, and their one-page form, [PHONE-SCRIPT-v7.17.0.md](PHONE-SCRIPT-v7.17.0.md), have been offered to the owner.
5b. [ ] **Publishing is already authorised for this release.** The owner asked for the tab on 2026-10-26 and wrote "Once ready, release it but do a thorough bug check."; three rounds of bug checks ran on the pull request before it was merged. As for v7.16.1 and v7.16.2, the release ships without the arm64 APK phone-test gate. Sections A to D of the phone script work on any phone and section E on any Samsung phone; the Font changer (section F) needs Root with su or Shizuku started as the system user or root, and System emoji (section G) root, so on the owner's phone (One UI 8.5, cannot be rooted) they show locked, which is the testable part there. Merge once CI is green.
6. [ ] Merge the release pull request, then start the release: Actions, "Build, sign and publish release", Run workflow, branch `main`, version `7.17.0` (no leading `v`, no `-Pro`), **leave "allow_throwaway_key" unticked**.
7. [ ] Wait for it to finish green. After "Prepare signing key", and before anything is built, it checks that `7.17.0` is the versionName in `AndroidManifest.xml` (with or without `-Pro`) and the version of the newest `CHANGELOG.md` heading, and that this heading's versionCode is the manifest's (865) and above the one on the heading under it (864, v7.16.2-Pro), then builds three APKs (`arm64-v8a`, `armeabi-v7a`, `universal`), writes `SHA256SUMS.txt`, checks the signing certificate against the pinned value, and publishes the release with the changelog section as its text.
8. [ ] Verify the published files (next section).
9. [ ] Install the arm64 file over v7.16.2 on a phone and work through [PHONE-SCRIPT-v7.17.0.md](PHONE-SCRIPT-v7.17.0.md) when a suitable phone is available.

## Verify the published release

Download the four files of the release into an empty folder, then from that folder (needs `apksigner` and `aapt2` from build-tools 35 on the PATH):

```sh
sha256sum -c SHA256SUMS.txt
ls -1 *.apk | wc -l
for f in ADB_Application_Manager_Pro-v7.17.0-*.apk; do
  echo "== $f"
  apksigner verify --verbose --print-certs "$f" | grep -E "Number of signers|Signer #1 certificate SHA-256"
  aapt2 dump badging "$f" | grep -E "^(package|application-label):"
done
```

- [ ] `sha256sum -c` says `OK` for every line, and exactly three `.apk` files are listed.
- [ ] For each APK: `Number of signers: 1`, and `Signer #1 certificate SHA-256 digest: 9461870f7cd2406406a119f4d37533c23bb7d1ed850506bad7ea3e6b4bd26d17` (the value in the README and in `release.yml`).
- [ ] For each APK, the `package:` line says `name='com.bloatware.bingblop'`, `versionCode='865'` and `versionName='7.17.0-Pro'`, and `aapt2 dump badging` lists `application-label:'ADB/Root Full System Toolbox'`.
- [ ] The arm64 file is the first one in the list of assets (an older copy of the app that updates itself takes the first `.apk`; so does the Obtainium configuration's `arm64-v8a` filter).
- [ ] The release text is the changelog section and nothing else (an empty body means the heading `## v7.17.0` was not found).
- [ ] If the digest differs, or the workflow printed the "throwaway key" warning: stop. Do not tell anyone to update; delete the release (or mark it a pre-release) and fix the secrets first. Phones refuse an update over a different certificate.

## After it finished: on a phone

Install the arm64 file over v7.16.2 (an update, not a fresh install). The steps are [PHONE-SCRIPT-v7.17.0.md](PHONE-SCRIPT-v7.17.0.md). In short:

- [ ] About says 7.17.0, and settings, saved lists, keys and pinned tabs are still there.
- [ ] Custom Fonts is under More, right before About. Make a font builds a Regular and Bold font, a variable font at two weights, and one with emoji inside; each is listed in My fonts drawn in itself.
- [ ] Emoji fonts: a download shows its progress, keeps the file only if it matches, and Cancel keeps nothing.
- [ ] My fonts on a Samsung phone: Install with a working mode on (the app's own install question with the recorded SHA-256; without a working mode Android's installer opens and the row says "Sent to Android's installer."), Make again gives one Version higher and Update, Export saves an `.apk`, Delete asks first. On One UI 8.5 and newer, and on 8.0 to 8.4 with a March 2026 or newer security patch (or a patch date the app cannot read), the box above the rows says Samsung's Font style does not apply fonts made on this phone and to use Apply; when the One UI version cannot be read it starts "On this phone, ...". On One UI before 8.0, and on 8.0 to 8.4 with a patch older than March 2026, there is no box, and Samsung's Font style applies the installed font.
- [ ] Font changer on a Samsung phone (on any other phone the tag reads SAMSUNG ONLY and Apply stays greyed out, without the lock, whatever the working mode): locked (LOCKED tag, small lock on Apply) with Shizuku over ADB, ADB TCP or Wireless Debugging; with Root and su or Shizuku as UID 1000 or 0, Apply puts the font on the whole phone (read back from sans.loc and the files) and survives a restart, and Reset to Default puts the phone's font back.
- [ ] System emoji (Root with su, or Shizuku as UID 0): Use as the phone's emoji, Soft reboot keeps it, Undo takes it off, and a normal restart always puts the phone's own emoji back. On a phone whose emoji Google Play services updated, the swap is refused.

## If something is wrong

- A bad build of the release: mark the release as a pre-release or delete it from the Releases page, say so in the issue tracker, and fix forward with a patch release (never reuse a tag for different files: phones and mirrors cache by version).
- A problem found only on one phone model: open a phone bug report (the issue template asks for the working mode, the One UI version and the checklist step), and add a row to the shared findings backlog in `docs/COPILOT_CLAUDE_HANDOFF.md`.
