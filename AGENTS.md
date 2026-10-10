# Notes for AI agents and contributors

This file is for anything that changes this repository: Claude, Copilot, Gemini, other coding agents and people. It supplements
`docs/COPILOT_CLAUDE_HANDOFF.md` (the shared review backlog) and `tests/README.md` (the test harness). It does not replace them.

## What the app is

A single-activity Android app: the UI is a WebView page (`assets/index.html`, strings in `assets/lang/*.js`, 13 languages), the work is
done in Java (`src/com/bloatware/bingblop/`). There is no Gradle project for the main app; `./build.sh` compiles, dexes, packages and signs it.
`engine/` is a separate Gradle project (`engine/build-engine.sh`) that builds the Morphe patcher engine. The app is **one product with one UI**. Do not replace the WebView page
with another UI stack in a pull request; add features to it (see "Pull requests" below).

## Build and test

| What | Command | Notes |
|---|---|---|
| Build and sign the APK | `./build.sh` | needs `ANDROID_JAR` (android-35 or newer) and aapt2/d8 (`D8_JAR` can point to r8.jar) on `PATH`; output `bin/ADB_Application_Manager_Pro.apk` |
| Java suites | `cd tests && ORG_JSON_JAR=<json.jar> node java/run.js [suite ...]` | `node java/run.js --list` names every suite and what it needs; a missing tool is a skip, not a failure |
| UI scripts | `cd tests && node run.js [tNN ...]` | headless Chromium against the real `assets/index.html` with a mock Android bridge; `node run.js --update tNN` re-records `tests/ui/expected/tNN.txt` — review the diff before committing it |
| Help Guide | `node docs/guide/build.js` | rebuild when `docs/guide` changes |

CI (`.github/workflows/`) runs the build, the Java suites and the UI scripts on every pull request. Chain commands with `&&` so a failed build
does not go on to push.

None of this has ever been run on a device by an agent. Say so in the pull request ("Not run: on a device") instead of implying otherwise.

## Pull requests

- One finding or one feature per pull request, small enough to review. No broad refactors mixed with fixes.
- A fix comes with a test where the code can be tested without Android classes (`tests/java/src`, registered in `tests/java/run.js`);
  UI behavior gets a UI script. A change to the page or to `assets/lang/*.js` must keep all 13 languages complete (the i18n checks in the
  UI scripts catch missing keys).
- Fix findings from `docs/COPILOT_CLAUDE_HANDOFF.md` by id (for example `C-006`) and update the row in the same pull request: status, the
  commit SHA, what was validated, and what was **not** run. Table rows have exactly six cells.
- Only evidence-backed claims. Label hypotheses as hypotheses. Never write that something was verified on a device unless it was.
- Version, `CHANGELOG.md`, `assets/changelog.md` (must match) and the README "what's new" change together, in the release pull request only.
- A pull request that deletes or replaces large parts of the app, commits build output (`build/`, `*.class`, `*.dex`) or removes
  the root `AndroidManifest.xml` is not mergeable. Propose a bigger direction in an issue first.
- Review bot findings are claims to check: trace a realistic path to the failure, fix it if it is real and the fix is in proportion, and
  answer the thread with the reason if not.

## Code conventions that matter here

- **Network:** open URLs through `HttpSafe.open(...)` (manual redirects, no https→http, no hop from an outside address to loopback, credentials
  dropped on a host change). Do not call `HttpURLConnection` with automatic redirects for anything that carries a key or downloads a file.
- **Shell commands:** build them with the quoting helpers; package, file and setting names are untrusted input. Success is decided by reading the
  state back, not by the command's exit text.
- **Installs:** go through `InstallGuards` (published SHA-256, signer comparison); failing closed is the point.
- **Secrets:** API keys and tokens belong in the Keystore vault, not in `SharedPreferences`, logs, exports or pull request text. Release builds
  must not log command output.
- **Executors and threads:** anything a `MainActivity` field starts is shut down in `onDestroy`.
- **File modes:** `build.sh`, `tests/**/*.sh` and similar scripts keep their executable bit; check `git diff --summary` for mode changes.

## What needs a human

Do not change these without the owner's explicit approval, and do not read general autonomy as permission to weaken them: release policy
(`release.yml`, the pinned signing certificate, `tools/check-signing-cert.sh`), workflow permissions and secrets, the app's permissions in
`AndroidManifest.xml`, and anything that publishes outside the repository. A new check that only makes CI stricter is fine.

## Releasing

A release is a pull request (version, changelog, README, goldens) → CI green → merge → `release.yml` with the version → verify the three
APKs, their checksums and the signing certificate. Do not publish a release without the on-device checklist in `docs/DEVICE-TEST-CHECKLIST.md`
being offered to the owner.
