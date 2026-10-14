# v7.13.3 release: before, during and after

A screen-layout release cut from `main` on top of v7.13.2. It ships the UI audit's usability changes (#216 and #220 to #227: the Apps tab list near the top with the icon as its checkbox, the File Manager options under ⋯ More, readable small text and badge contrast, empty lists, 44 px tap areas) and nothing else. The version is 7.13.3, versionCode 857. The `release.yml` workflow is started on `main` with the version `7.13.3` after the bump pull request is merged. **Nothing in this release has been run on a phone by the people and tools that wrote it.**

## The sequence, in order

1. [ ] The UI pull requests (#216, #220 to #227) are merged into `main` (4dd6272), and the version bump pull request is merged after them, so the tag points at the reviewed commit. The `## v7.13.3-Pro` section of `CHANGELOG.md` is the list of what ships; this page keeps no list of its own.
2. [ ] The bump changes only the version, the changelog, the README, the guides and these checklists. The Help Guide carries the version from `AndroidManifest.xml`: after the bump, run `node docs/guide/build.js` and commit `assets/guide.js`, or the build job fails with "assets/guide.js is out of date".
3. [ ] Repository secrets are set: `KEYSTORE_BASE64` (the release keystore, base64), and `KEYSTORE_PASSWORD` and `KEY_ALIAS` if the keystore does not use the defaults (`password` and `adbmanager`). Without `KEYSTORE_BASE64` the run fails at "Prepare signing key".
4. [ ] Read the `## v7.13.3-Pro (versionCode 857)` section of `CHANGELOG.md` once as the person who will see it: the workflow copies it into the release text, so every sentence must be true of this build.
5. [ ] The whole UI suite and the Java suites are green on the tip of `main` (the `tests` workflow, or `node tests/run.js` and `node tests/java/run.js`). Known flaky scripts on the shared runners (each passes alone): t137, t261, t53/t54, t157 (the font-scan hook), t31/t33/t155 and t142/t97/t126/t13/t111 under load; Java `archivecancel`, `extractspace`, `sdmengine`, `ziptool`, `morphehelper` under load. A red job is re-run once; a second failure is read, not waved through.
5a. [ ] [PHONE-SCRIPT-v7.13.3.md](PHONE-SCRIPT-v7.13.3.md) has been offered to the owner, as `AGENTS.md` requires before a release, and the owner has asked for the release to be cut (standing instruction of 2026-10-14: cut the release when everything is in).
6. [ ] Start it: Actions, "Build, sign and publish release", Run workflow, branch `main`, version `7.13.3` (no leading `v`, no `-Pro`), **leave "allow_throwaway_key" unticked**.
7. [ ] Wait for it to finish green. It builds three APKs (`arm64-v8a`, `armeabi-v7a`, `universal`), writes `SHA256SUMS.txt`, checks the signing certificate against the pinned value, and publishes the release with the changelog section as its text.
8. [ ] Verify the published files (next section).
9. [ ] Install the arm64 file over v7.13.2 on a phone and work through [PHONE-SCRIPT-v7.13.3.md](PHONE-SCRIPT-v7.13.3.md).

## Verify the published release

Download the four files of the release into an empty folder, then from that folder (needs `apksigner` and `aapt2` from build-tools 35 on the PATH):

```sh
sha256sum -c SHA256SUMS.txt
ls -1 *.apk | wc -l
for f in ADB_Application_Manager_Pro-v7.13.3-*.apk; do
  echo "== $f"
  apksigner verify --print-certs "$f" | grep -E "Number of signers|Signer #1 certificate SHA-256"
  aapt2 dump badging "$f" | head -1
done
```

- [ ] `sha256sum -c` says `OK` for every line, and exactly three `.apk` files are listed.
- [ ] For each APK: `Number of signers: 1`, and `Signer #1 certificate SHA-256 digest: 9461870f7cd2406406a119f4d37533c23bb7d1ed850506bad7ea3e6b4bd26d17` (the value in the README and in `release.yml`).
- [ ] For each APK, the `package:` line says `name='com.bloatware.bingblop'`, `versionCode='857'` and `versionName='7.13.3-Pro'`.
- [ ] The arm64 file is the first one in the list of assets (an older copy of the app that updates itself takes the first `.apk`; so does the Obtainium configuration's `arm64-v8a` filter).
- [ ] The release text is the changelog section and nothing else (an empty body means the heading `## v7.13.3` was not found).
- [ ] If the digest differs, or the workflow printed the "throwaway key" warning: stop. Do not tell anyone to update; delete the release (or mark it a pre-release) and fix the secrets first. Phones refuse an update over a different certificate.

## After it finished: on a phone

Install the arm64 file over v7.13.2 (an update, not a fresh install). The steps are [PHONE-SCRIPT-v7.13.3.md](PHONE-SCRIPT-v7.13.3.md). In short:

- [ ] The app opens, About says 7.13.3, and settings, saved lists, keys and pinned tabs are still there.
- [ ] Apps tab: the list starts near the top; tap an app's icon and it is selected; long-press the name and the app menu opens.
- [ ] Files tab: the list starts near the top; ⋯ More opens the options sheet and Show hidden files works.
- [ ] Dark theme: the app menu's Freeze and Uninstall buttons and the USER, SYSTEM, FROZEN and MOD badges read clearly.

## If something is wrong

- A bad build of the release: mark the release as a pre-release or delete it from the Releases page, say so in the issue tracker, and fix forward with a patch release (never reuse a tag for different files: phones and mirrors cache by version).
- A problem found only on one phone model: open a phone bug report (the issue template asks for the working mode, the One UI version and the checklist step), and add a row to the shared findings backlog in `docs/COPILOT_CLAUDE_HANDOFF.md`.
