# v7.18.0 release: before, during and after

A feature release cut from `main` on top of v7.17.0, with the two changes the owner asked for on 2026-10-26, merged as one pull request (#329): a **Search Google Fonts** page that pulls up over the **Custom Fonts** tab, to search the families of Google Fonts, see each one drawn in its own font, download it (into the app's private storage) and put it in Make a font or save it as a `.zip`, and sixteen sample emoji under each of the five fonts of Emoji fonts (four from small sample fonts in the APK, the iOS ones read from the pinned iOS file when asked, never shipped). Nothing on the page or in the samples needs a working mode, root or a Samsung phone; the page needs internet, and talks only to `fonts.google.com`, `fonts.googleapis.com` and `fonts.gstatic.com`, with no key and no account. No permission is new and none is removed; package, APK file names and the signing certificate are unchanged. The Google Fonts code was checked on a computer against Google Fonts' answers captured on 2026-10-26 and 2026-10-27, the iOS sample reader against the real iOS file, and the page in a headless browser with a mock; not run on a device.

## The sequence, in order

1. [ ] #329 is merged into `main`, and the version bump is on the release pull request, merged into `main` so the tag points at the reviewed commit. The `## v7.18.0-Pro` section of `CHANGELOG.md` is the list of what ships; this page keeps no list of its own.
2. [ ] The bump changes only the version, the changelog, the README, the guides, `docs/LAUNCH.md`, the v7.18.0 section of `docs/COPILOT_CLAUDE_HANDOFF.md`, the device checklist and these checklists. The Help Guide carries the version from `AndroidManifest.xml`: after the bump, run `node docs/guide/build.js` and commit `assets/guide.js`, or the build job fails with "assets/guide.js is out of date".
3. [ ] Repository secrets are set: `KEYSTORE_BASE64` (the release keystore, base64), and `KEYSTORE_PASSWORD` and `KEY_ALIAS` if the keystore does not use the defaults (`password` and `adbmanager`). Without `KEYSTORE_BASE64` the run fails at "Prepare signing key".
4. [ ] Read the `## v7.18.0-Pro (versionCode 866)` section of `CHANGELOG.md` once as the person who will see it: the workflow copies it into the release text, so every sentence must be true of this build.
5. [ ] The whole UI suite and the Java suites are green on the tip of the release pull request (the `tests` workflow, or `node tests/run.js` and `node tests/java/run.js`), with the new Java suites `googlefonts` and `emojispecimen` and the new UI scripts t598 to t603 among them. Known flaky scripts on the shared runners (each passes alone): t137, t261, t53/t54, t157 (the font-scan hook), t31/t33/t155 and t142/t97/t126/t13/t111 under load; Java `archivecancel`, `extractspace`, `sdmengine`, `ziptool`, `morphehelper` under load. A red job is re-run once; a second failure is read, not waved through.
5a. [ ] Sections 47 to 54 of [DEVICE-TEST-CHECKLIST.md](DEVICE-TEST-CHECKLIST.md), as `AGENTS.md` requires before a release, and their one-page form, [PHONE-SCRIPT-v7.18.0.md](PHONE-SCRIPT-v7.18.0.md), have been offered to the owner.
5b. [ ] **Publishing is already authorised for this release.** The owner asked for the page on 2026-10-26 and wrote "Bug test it thoroughly and release it."; rounds of bug checks ran on the branch before it was merged, and each confirmed finding was fixed there. As for v7.16.1, v7.16.2 and v7.17.0, the release ships without the arm64 APK phone-test gate. Every step of the phone script works on any phone with internet, including the owner's (One UI 8.5, no root): nothing in this release needs a working mode, root or a Samsung phone. Merge once CI is green.
6. [ ] Merge the release pull request, then start the release: Actions, "Build, sign and publish release", Run workflow, branch `main`, version `7.18.0` (no leading `v`, no `-Pro`), **leave "allow_throwaway_key" unticked**.
7. [ ] Wait for it to finish green. After "Prepare signing key", and before anything is built, it checks that `7.18.0` is the versionName in `AndroidManifest.xml` (with or without `-Pro`) and the version of the newest `CHANGELOG.md` heading, and that this heading's versionCode is the manifest's (866) and above the one on the heading under it (865, v7.17.0-Pro), then builds three APKs (`arm64-v8a`, `armeabi-v7a`, `universal`), writes `SHA256SUMS.txt`, checks the signing certificate against the pinned value, and publishes the release with the changelog section as its text.
8. [ ] Verify the published files (next section).
9. [ ] Install the arm64 file over v7.17.0 on a phone and work through [PHONE-SCRIPT-v7.18.0.md](PHONE-SCRIPT-v7.18.0.md) when a phone is available.

## Verify the published release

Download the four files of the release into an empty folder, then from that folder (needs `apksigner` and `aapt2` from build-tools 35 on the PATH):

```sh
sha256sum -c SHA256SUMS.txt
ls -1 *.apk | wc -l
for f in ADB_Application_Manager_Pro-v7.18.0-*.apk; do
  echo "== $f"
  apksigner verify --verbose --print-certs "$f" | grep -E "Number of signers|Signer #1 certificate SHA-256"
  aapt2 dump badging "$f" | grep -E "^(package|application-label):"
done
```

- [ ] `sha256sum -c` says `OK` for every line, and exactly three `.apk` files are listed.
- [ ] For each APK: `Number of signers: 1`, and `Signer #1 certificate SHA-256 digest: 9461870f7cd2406406a119f4d37533c23bb7d1ed850506bad7ea3e6b4bd26d17` (the value in the README and in `release.yml`).
- [ ] For each APK, the `package:` line says `name='com.bloatware.bingblop'`, `versionCode='866'` and `versionName='7.18.0-Pro'`, and `aapt2 dump badging` lists `application-label:'ADB/Root Full System Toolbox'`.
- [ ] The arm64 file is the first one in the list of assets (an older copy of the app that updates itself takes the first `.apk`; so does the Obtainium configuration's `arm64-v8a` filter).
- [ ] The release text is the changelog section and nothing else (an empty body means the heading `## v7.18.0` was not found).
- [ ] If the digest differs, or the workflow printed the "throwaway key" warning: stop. Do not tell anyone to update; delete the release (or mark it a pre-release) and fix the secrets first. Phones refuse an update over a different certificate.

## After it finished: on a phone

Install the arm64 file over v7.17.0 (an update, not a fresh install). The steps are [PHONE-SCRIPT-v7.18.0.md](PHONE-SCRIPT-v7.18.0.md). In short:

- [ ] About says 7.18.0, and the fonts in My fonts are still there.
- [ ] Custom Fonts' top card has **Search Google Fonts**, and Make a font has **Find one on Google Fonts** under its Regular row while no Regular is chosen; both open the page over the tab, and Back goes back one step at a time.
- [ ] The list comes from Google Fonts once and is kept on the phone; **Search fonts or designers** finds designers too, letter case and accents aside; the menus, the chips and the sort narrow and reorder the cards; each card's line is drawn in the family's own font, never first in the app's font; offline, the previews seen before still draw and the others say "No preview".
- [ ] A family's page: its axes in **Type tester**, its styles, **About this family** with the licence and **Licence text**.
- [ ] **Download** of a variable family (Roboto) and of a static one (Lato); **Cancel** keeps nothing; **Use in Make a font** (with Make a font's Name box emptied first: a Name you typed is kept) fills Make a font's slots and Name; a Reserved Font Name (Lato) keeps the Name empty and warns while the Name holds it, with **Build font** still on; **Save to phone** writes "Lato.zip" with the fonts and `OFL.txt`; **Delete** takes the family off at once.
- [ ] Emoji fonts: sixteen emoji under Noto Color Emoji, Twemoji, Blobmoji and OpenMoji, with no connection; the iOS row's **Show these emoji** reads its sixteen pictures (about 0.3 MB, or from the downloaded iOS emoji with no connection) and keeps them.

## If something is wrong

- A bad build of the release: mark the release as a pre-release or delete it from the Releases page, say so in the issue tracker, and fix forward with a patch release (never reuse a tag for different files: phones and mirrors cache by version).
- Google Fonts changed how it answers (the page says "Google Fonts sent an answer the app cannot read. Try again later."): compare the live answer with the captured ones in `tests/java/fixtures/fonts/googlefonts/` and fix forward with a patch release. The iOS sample saying "The iOS emoji file at its address is not the expected one, so its sample was not read. Update the app." means the pinned file changed at its address: the same.
- A problem found only on one phone model: open a phone bug report (the issue template asks for the working mode, the One UI version and the checklist step), and add a row to the shared findings backlog in `docs/COPILOT_CLAUDE_HANDOFF.md`.
