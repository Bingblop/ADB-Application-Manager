# v7.12.9: what the phone check found

Fill this in while running [PHONE-QUICK-CHECK-v7.12.9.md](PHONE-QUICK-CHECK-v7.12.9.md). It is the input of the v7.12.10 changelog and of the handoff: every FAIL becomes a row in `docs/COPILOT_CLAUDE_HANDOFF.md` (the next free `C-0xx`, with the phone, Android version, working mode and the exact step) and, if it is a bug in the app, one small pull request with a test.

**Phone model:** &nbsp; **Android version / One UI:** &nbsp; **Working mode (standard / adb / Shizuku / root):** &nbsp; **Installed over:** (previous version) &nbsp; **Date:**

| Step | PASS / FAIL / SKIP | What you saw (and the output of a failing command) |
|---|---|---|
| 1. Install over the old version; About says 7.12.9-Pro / 851; settings kept | | |
| 2. Private adb socket (`/proc/net/unix` shows it, port 5042 does not) | | |
| 3. Keys survive a restart (VirusTotal key still accepted) | | |
| 4a. File Manager: Download opens files, Share and Open with work | | |
| 4b. File Manager: the app's own `shared_prefs` / `files` are refused | | |
| 4c. File Manager: patched APK and Helper downloads still work | | |
| 5a. Terminal: `trap '' HUP; exec sleep 600`, close, the process is gone within a second | | |
| 5b. Terminal: 100 KB paste while `yes` runs does not freeze | | |
| 5c. Terminal: Restart three times, `echo ok` answers once | | |
| 6. Connected Devices: `adb -P 5037 devices` and `adb kill-server` are refused | | |
| 7. Installs in your working mode behave as described | | |
| 8. A small zip and a small tar.gz extract with the right sizes, no `.part` left | | |

## Findings to turn into work

| # | Step | Phone / Android / mode | What is wrong | Next free handoff id | PR |
|---|---|---|---|---|---|
| | | | | | |

## After the check

- [ ] Every FAIL has a handoff row.
- [ ] The v7.12.10 `CHANGELOG.md` section is filled from this page (no line starts with TODO).
- [ ] `docs/FULL-GUIDE.md`, `assets/guide.js` (`node docs/guide/build.js`) and the README "what's new" are updated in the release pull request, together with the version already set in `AndroidManifest.xml` (7.12.10-Pro / 852).
- [ ] `docs/RELEASE-CHECKLIST-v7.12.10.md` is copied from the v7.12.9 one with the version numbers changed.
