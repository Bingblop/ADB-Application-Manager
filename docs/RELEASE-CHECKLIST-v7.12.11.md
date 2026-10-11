# v7.12.11 release: before, during and after

The release is run from the branch `release/v7.12.11`, not from `main`: `main` received direct pushes that are not part of this release (a Content-Security-Policy tag in `assets/index.html` that breaks 13 UI scripts, a rewrite of the ADB Console markup, four deleted `tools/hsinfo` files). The branch starts at the commit of the v7.12.10 release (tag `v7.12.10`). The `release.yml` workflow is started on that branch with the version `7.12.11`, and the branch is merged into `main` afterwards. **Nothing here has been run on a phone by the people and tools that wrote it.**

## The sequence, in order

1. [ ] Every pull request named in the `## v7.12.11-Pro` section of `CHANGELOG.md` is merged into `release/v7.12.11`: the Private DNS card (#152), the ADB Console result line (#151), the App Stores button colours (#153), the install question on every install path and no privileged install in Read-Only mode (#154), and the version bump. The changelog is the list; this page keeps no list of its own.
2. [ ] The branch changes only what the changelog says, the version, the README, the guides and these checklists. Nothing else is added to it before the release.
3. [ ] Repository secrets are set: `KEYSTORE_BASE64` (the release keystore, base64), and `KEYSTORE_PASSWORD` and `KEY_ALIAS` if the keystore does not use the defaults (`password` and `adbmanager`). Without `KEYSTORE_BASE64` the run fails at "Prepare signing key".
4. [ ] Read the `## v7.12.11-Pro (versionCode 853)` section of `CHANGELOG.md` once as the person who will see it: the workflow copies it into the release text, so every sentence must be true of this build.
5. [ ] The whole UI suite and the Java suites are green on the tip of the branch (the `tests` workflow, or `node tests/run.js` and `node tests/java/run.js`).
6. [ ] Start it: Actions, "Build, sign and publish release", Run workflow, branch `release/v7.12.11`, version `7.12.11` (no leading `v`, no `-Pro`), **leave "allow_throwaway_key" unticked**.
7. [ ] Wait for it to finish green. It builds three APKs (`arm64-v8a`, `armeabi-v7a`, `universal`), writes `SHA256SUMS.txt`, checks the signing certificate against the pinned value, and publishes the release with the changelog section as its text.
8. [ ] Verify the published files (next section).
8a. [ ] Merge `release/v7.12.11` into `main`, resolving the conflicts with the direct pushes to `main`, and run the whole UI suite on the result.
9. [ ] Install the arm64 file over the previous version on a phone and run the checks below.

## Verify the published release

Download the four files of the release into an empty folder, then from that folder (needs `apksigner` and `aapt2` from build-tools 35 on the PATH):

```sh
sha256sum -c SHA256SUMS.txt
ls -1 *.apk | wc -l
for f in ADB_Application_Manager_Pro-v7.12.11-*.apk; do
  echo "== $f"
  apksigner verify --print-certs "$f" | grep -E "Number of signers|Signer #1 certificate SHA-256"
  aapt2 dump badging "$f" | head -1
done
```

- [ ] `sha256sum -c` says `OK` for every line, and exactly three `.apk` files are listed.
- [ ] For each APK: `Number of signers: 1`, and `Signer #1 certificate SHA-256 digest: 9461870f7cd2406406a119f4d37533c23bb7d1ed850506bad7ea3e6b4bd26d17` (the value in the README and in `release.yml`).
- [ ] For each APK, the `package:` line says `name='com.bloatware.bingblop'`, `versionCode='853'` and `versionName='7.12.11-Pro'`.
- [ ] The arm64 file is the first one in the list of assets (an older copy of the app that updates itself takes the first `.apk`).
- [ ] The release text is the changelog section and nothing else (an empty body means the heading `## v7.12.11` was not found).
- [ ] If the digest differs, or the workflow printed the "throwaway key" warning: stop. Do not tell anyone to update; delete the release (or mark it a pre-release) and fix the secrets first. Phones refuse an update over a different certificate.

## After it finished: on a phone

Install the arm64 file over the previous version (an update, not a fresh install). The steps are in [DEVICE-TEST-CHECKLIST.md](DEVICE-TEST-CHECKLIST.md): section 20 (installs: the Updater and Installer ask first in adb, Shizuku and root mode; Read-Only mode installs only through the system installer), section 21 (Private DNS) and section 22 (the ADB Console result line). In short:

- [ ] The app opens, the About tab says 7.12.11, and settings, saved lists and keys are still there.
- [ ] Updater, **Update** in adb mode: the app's own dialog appears (the package and version read from the file, the host it came from) and Cancel installs nothing. This was a failed step of the v7.12.10 phone check.
- [ ] Read-Only mode: the Installer's Authorizer drop-down offers only the system installer and an install shows the system installer's question, even while ADB is still connected. This was the other failed step.
- [ ] Settings, **Private DNS**: AdGuard DNS is applied and read back; **Automatic** puts it back.
- [ ] ADB Console: a command's output ends with the "Finished" line; a failing one shows "Looks like a failure".
- [ ] App Stores: the text on the Install buttons is readable in a light and a dark theme.

## If something is wrong

- A bad build of the release: mark the release as a pre-release or delete it from the Releases page, say so in the issue tracker, and fix forward with a patch release (never reuse a tag for different files: phones and mirrors cache by version).
- A problem found only on one phone model: write down the model, Android version, working mode (standard, adb, Shizuku, root) and the exact step, then add a row to the shared findings backlog in `docs/COPILOT_CLAUDE_HANDOFF.md`.
