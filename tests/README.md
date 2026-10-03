# Tests

The checks the app is released with. None of this is part of the APK. There are two kinds:

| | What it is | Needs |
|---|---|---|
| **UI** — `run.js`, `ui/` | 79 headless-Chromium scripts drive the real `assets/index.html` against a mock Android bridge: every tab, theme, filter, action, sheet and dialog | Node 18+, Playwright with its Chromium |
| **Java** — `java/run.js`, `java/src/` | The parts of the app's Java that need no Android classes (settings and overlay rules, file rules, install hints, the archive engine, the Rish shell, the APK signer, the manifest decoder, the store parsers), compiled straight from `src/` and run with the JDK | JDK 8+; some suites also need Node, Python 3 with `zip`, `mksh` + `toybox`, `apksigner`, an APK, or the `org.json` jar |

## Run them

```sh
cd tests
npm install                       # Playwright, once
npx playwright install chromium   # its browser, once
node run.js                       # all UI scripts (about 4 minutes)
node java/run.js                  # all Java suites
```

`npm test` runs both. A script or suite whose prerequisite is missing is skipped with a note that says what to install; that is not a failure
(`--strict` makes it one).

## The UI scripts

Each script starts headless Chromium, loads `assets/index.html` with a mock `window.AndroidBridge` (shared mocks in `ui/lib/`, the rest inline in
the script), drives the page the way a person would, and prints what it finds. The first line of a script says what it covers; `node run.js --list`
prints them all.

```
node run.js                     every script, 3 side by side
node run.js t4 t60              only these
node run.js --jobs 2            how many side by side
node run.js --no-compare        only look for failures, not for changes in what a script prints
node run.js --update t4         record this run's output as the expected output (after a change you meant to make)
node run.js --list              name and description of each script
```

**What passes.** A script passes when it exits with 0, prints no `FAIL …` line or `N FAILED` summary, reports no page errors, and prints what
`ui/expected/<name>.txt` holds. Many scripts print values rather than asserting them, so the expected output is what notices a change: when one
differs, the runner shows the lines, and you either fix the page or, if the change is the point, `--update` that script and review the diff of
its expected file like any other change.

**Why the output is stable.** The scripts run with a fixed clock (it starts at `2026-10-03T04:00:00Z` and then runs normally, in Node and in every
page alike), the `en-US` locale and the UTC time zone, so dates, ages and times do not depend on the day or the machine. Clock readings with
seconds, ISO timestamps, epoch milliseconds and 14-digit file stamps are masked before the comparison; scripts print whether a timing is within
its limit, not the number. Where a script looks at something that is only there for a moment (a progress text, a button that is disabled while a job
runs), the mock keeps its answer until the script lets it go and the script waits for what the page shows, not for a delay. The whole suite was
also run on a busy 4-core machine (`--jobs 4` and `--jobs 6` next to busy loops) until two such runs in a row passed; a very slow machine may
still need a rerun of a script that fails once, and the output of that run (`tests/out/<name>/output.txt`) says which line.

**Where things go.** The full output and every screenshot of a script are kept in `tests/out/<name>/` (not committed): open the PNGs to see the
page as the script saw it.

**Fonts.** Layout numbers a few scripts print (sizes in pixels, how many rows fit) come from Chromium's fonts on the machine. The expected outputs
were recorded on Linux with its default fonts; on another system a few of those lines may differ. `--no-compare` still checks everything that is
asserted, and `--update` re-records.

Settings: `PAGE_URL` runs the scripts against another copy of the page (for example an older `index.html`, to see a script fail), `TEST_NOW`
changes the starting time, `CHROMIUM_PATH` uses a browser you already have, `NODE_PATH` finds a Playwright that is not in `tests/node_modules`,
`TEST_OUT` changes where one script writes.

### Adding a script

Copy a small one (`t69.js` is a good model), keep its first lines (`const { chromium, PAGE } = require('./lib/pw');` gives the browser, the page
and the fixed clock), start with a `// what it covers` line, print what matters (`FAIL …` for something wrong, as `check()` does in the newer
ones), run `node run.js <name> --update`, and commit the script with `ui/expected/<name>.txt`.

Two things made scripts fail only on a busy machine, and what to do instead:

- A mock that answers after a few milliseconds while the script looks at the page in between (a progress text, a button that is disabled while a
  job runs): let the mock keep the answer until the script releases it (`window.__holdCheck` in `t14.js`, `__holdBatch` in `t52.js`) and wait with
  `page.waitForFunction` for what the page shows.
- A "Show more" button next to an `IntersectionObserver` that loads the next page by itself once the button is near the screen (the apps list, Hidden
  Settings): Playwright scrolls the button into view before it clicks, so the page and the click can each load a page. Switch the observer off for the
  click (`window.IntersectionObserver = undefined`, then render again) and test the automatic loading on its own (`t53.js`, `t60.js`).

## The Java suites

`node java/run.js` compiles each test in `java/src/` with the classes it uses from `src/` (javac's source path), runs it in a clean folder
(`out/java/<suite>/`), and passes when it exits with 0 and prints no `FAIL` line.

| Suite | What it checks | Needs |
|---|---|---|
| `settingsdb` | Android settings (Global / Secure / System): names, values, quoting, read-back verdicts, run through a real `sh` with a fake `settings` command | |
| `overlayrules` | Overlays and Material You: `cmd overlay` output, names, the theme value and its merge, every verdict and the Samsung switch, against a fake phone | |
| `parity` | The page and the Java rules give the same answer to the same names, values and colors (about 1,300 inputs) | Node |
| `filerules` | File manager path rules: canonical form, protected folders | |
| `installhints` | What an install failure means | |
| `pure` | Package-file scan output and XAPK data paths | `org.json` |
| `apktrash` | Deleting a found package file with Undo (which paths may be deleted, where a file waits, how it comes back) and the storage search's progress, against a real folder tree | `org.json` |
| `splitinfo` | What a split APK's manifest says about it (split name, the feature module it configures, the feature flag) | |
| `fdroid` | F-Droid index v1 / v2 parsing against real indexes | `org.json` |
| `komi` | GitHub catalog (Komi) feeds: mapping, de-duplication, dates | `org.json` |
| `ziptool` | The archive engine: read, edit and rewrite zips (bad, truncated, encrypted, huge, zip64, streamed), alignment, diff | Python 3, `zip`; an APK is optional |
| `zipreview` | Archive engine, review findings: odd names, duplicates, prepended data, extra fields, AES, symlinks | Python 3 |
| `rish` | The Rish shell: persistent shell, `cd` / `export`, output, STOP, restart | |
| `rishreview` | The Rish shell against a real `mksh` with `toybox` applets (Android's shell) | `mksh`, `toybox` |
| `signer` | The in-app APK signer: v2 signatures checked with `apksigner`, RSA and EC keys, re-signing, edited and big APKs, tampering | an APK, `apksigner`, `keytool` |
| `signmismatch` | The signer refuses a key that does not match its certificate | an APK, `keytool` |
| `axml` | The manifest decoder reads every compiled XML file of a real APK | an APK |

- `org.json`: Android's copy is only stubs, so pass a real one: `ORG_JSON_JAR=/path/json-20240303.jar`
  (`curl -L -o .cache/org-json.jar https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar` is found by itself),
  or any jar that contains it.
- An APK: `TEST_APK=/path/app.apk`, by default `bin/ADB_Application_Manager_Pro.apk` from `./build.sh`.
- `apksigner`: `APKSIGNER=/path/apksigner`, or on `PATH`, or in `ANDROID_HOME/build-tools`. The test keystores are made with `keytool` on first use.
- The zip fixtures are made on first use by `java/zip/mkzip1.py` and `mkzip2.py` into `out/java/`.

```
node java/run.js                run everything
node java/run.js ziptool rish   some suites
node java/run.js --list         what each covers and needs
```

## What this does not cover

Everything that only exists on a phone: the ADB, Wireless Debugging, Shizuku and Root backends, the Quick Settings tiles, the widget, the share
sheet, the boot notification, the real `settings` and `cmd overlay` commands and the Material You engine on your Android version. Those are
checked by compilation, review and these simulations, not on a device.

## The UI scripts, one by one

<details><summary>79 scripts</summary>

| Script | Covers |
|---|---|
| `t2` | Working Modes: IP:port typed in the wireless, pairing and ADB TCP fields is split into host and port |
| `t3` | App inspector: permission toggle/filter, App Ops modes/custom op, manifest search/copy/save, frozen app menu |
| `t4` | App inspector Components: exported filter, search, activity launch (dialog only if refused), read-only guard |
| `t5` | Colors view: palette preset order, cards sit directly in the grid, picking Material 3 sets the accent colour |
| `t6` | Palette cards: after picking Material 3 and tapping away, prints each card's selected class, border, focus |
| `t7` | Light/dark themes: follow phone, forced modes, per-mode tweaks, Material You, light contrast, prefs migration |
| `t8` | ADB Console: runs a shell command, then prints the terminal box background colour in light and dark mode |
| `t9` | Appearance schedule (incl. past midnight), pure-black dark mode, ADB key card, prefs restored on relaunch |
| `t10` | Suspended apps: badge, filter and count, menu Suspend/Unsuspend, batch suspend (confirm) and batch unsuspend |
| `t11` | Debloater (UAD-NG): list download, filters, needed-by warning, uninstall review, restore, save to list |
| `t12` | Debloater brand filter: phone's brand first, counts follow other filters, switching brands, brand-aware search |
| `t13` | Saved filters, history (undo, copy log) and Updates tab (check, update one/all, error), kept on relaunch |
| `t14` | Updates tab: progress, update rows, Update All (one signature failure), Obtainium, set source, GitHub token |
| `t15` | App menu header: version line for normal, uninstalled, N/A-version and bad-data apps, dark and light themes |
| `t16` | Versions in app list and menu: update arrows, one bridge read, menu dates and Update hint, remembered toggle |
| `t17` | Apps list: sort by name/updated/installed/size/updates, Recent filter, CSV export, menu sizes, saved sort |
| `t18` | Text selection: names, packages, menu version, terminal selectable; buttons, tabs, pills not; row tap selects |
| `t19` | Copy/share and find: menu chips, no Share APK button, manifest/terminal find, CSV, package lists, profiles |
| `t20` | What's new shown only after an upgrade, watched-profile drift banner, and Quick list toggle on Saved Lists |
| `t21` | Backup and restore: create with progress, restore preview, share, delete, file picker; app data needs Root |
| `t22` | Profiles: apply plan per app state, refused saves rolled back; APK extraction one at a time, no share sheet |
| `t23` | Backups in Read-Only mode: Create backup is blocked with the privilege modal and never reaches the bridge |
| `t24` | Profiles: deleting an unwatched profile leaves the native watch setting alone; the watched one clears it |
| `t25` | Quick list sync: editing or deleting the active list updates the native side; profile import dedupes packages |
| `t26` | Component launch: name-only activities default to unexported, privilege guard blocks them, real flags kept |
| `t27` | Batch selection: floating button vs expanded sheet, collapse, clear, reset after an action or list recall |
| `t28` | Installer: split-APK cards, install flags per authorizer, split selection, install call, no-privilege mode |
| `t29` | Inspector Components tab: four sections, enable/disable, exported filter; single and batch dex optimization |
| `t30` | File manager: browse, up, send APK to installer, view, rename, delete, new folder; Logcat load, filter, clear |
| `t31` | File manager: ls parsing (toybox, busybox, fallback), path joins, access hint; Logcat play/pause polling |
| `t32` | Self-update card: auto-check, Update/Release-notes buttons, progress, up-to-date, error, hidden if unsupported |
| `t33` | Tab bar order, header Colors icon, tab highlighting, and main update check also running self-update check |
| `t34` | ADB Console: no auto-capitalize, Cheat Sheet search and tap-to-insert, full reference loaded from a gist |
| `t35` | Wireless Debugging: Pair via Notification button calls the bridge, shows a toast, falls back when missing |
| `t36` | ShizuStore tab: catalog load, search, sort, detail, install. |
| `t37` | Installer VirusTotal card: key persistence, scan, clean/malicious verdicts, upload offer. |
| `t38` | App list: patched/modified detection badge, "Patched" filter, and inspector breakdown. |
| `t39` | Unexported-activity launch: passes exported=false to the bridge and renders the assistant-method result. |
| `t40` | v5.7 Store: GitHub (renamed from Komi) full catalog + live search + owner/repo; F-Droid repo picker with the whole catalog; Orion; ShizuStore categories; a category dropdown in every store; progressive chunks; install progress. |
| `t41` | v5.7 logcat: one row per entry, level colors, grouped stack traces, tappable color key, copy fidelity. |
| `t42` | v5.7 installer: XAPK + OBB data, no default-installer button (text kept), automatic storage scan list. |
| `t43` | v5.7 terminal: "adb devices" button replaced by Rish mode (switch to Shizuku + persistent Rish shell). |
| `t44` | v5.7 file manager: View on an .apk / .zip / package file opens its contents (no extracting) with preview, extract and in-place edit. |
| `t45` | v5.7: debloater checkmark fill (same as Apps tab), no Force Stop button in app rows, press-and-hold the checkmark FAB to clear every selection. |
| `t46` | v5.8: Android Back handling (sheets, selection, archive browser, tab history, press-twice-to-exit) + HTML-injection hardening of lists. |
| `t47` | v5.8 terminal: command history (arrow keys + sheet), saved commands / scripts, pinned chips, persistence, Rish routing. |
| `t48` | v5.8 archive browser extras: install an APK from inside an archive, open nested archives, compare two archives (+ line diff), remembered extract folder. |
| `t49` | v5.8 in-app APK signing UI: Sign… in the file manager, the toolbar button + "edited" banner in the archive browser, the sign sheet. |
| `t50` | v5.8 logcat: log of one app (picker + inspector "Logs" button), save and share a bug-report text. |
| `t51` | v5.8 Rish UI robustness: stale results after leaving, busy handling, revived shell note, late output, bridge errors, trim, line starts. |
| `t52` | v5.8 file manager multi-select: long-press / Select, select all, delete, copy / move through a clipboard, Paste here, conflicts, failures, Back. |
| `t53` | v5.8 performance: the apps list is drawn a page at a time, search typing on a big list is debounced, logcat draws a tail and skips unchanged polls. |
| `t54` | v5.8 async bridge calls: the normal terminal and logcat no longer block the page while adb / shell works. |
| `t55` | v5.8 installs from the file manager: always privileged (ADB / Wireless Debugging / Shizuku / Root), the app's own signature gates off, no silent fallback to the system installer, a signed-by-someone-else app is replaced only after asking. |
| `t56` | v5.8 Back must not leave the app by accident: a double tap is not a decision, a late press re-warns, nothing is left mid-way, and a running job asks first. |
| `t57` | v5.8 About tab: last tab, developer + repo link, "Buy me a coffee" ($1) and a custom amount through PayPal, build / signature info, debug info. |
| `t58` | v5.8 final front-end review fixes: compare edit target, terminal row width, failure sheet on top, store search failure note, terminal busy / other tab, compare / nested state, late sign result, bridge throwing, logcat clear, extract default folder, long-press touch, history filter box. |
| `t59` | v5.8 review round 2 (page side): a second tap while an install runs is refused, and the "uninstall first?" question names the package. |
| `t60` | v5.9 Settings tab: placement, loading, sub-tabs, search / filters / sort, long-press and switch toggles, editor, create, delete, undo, failures, journal, Back, risky-key confirmations, escaping. |
| `t61` | v5.9 Settings tab, part 2: history of changes and revert, the "Edited" filter, Back, a change in flight, text that looks like markup, the tools sheet, risky delete / create, a mode change while the tab is open, keyboard use, narrow screens. |
| `t62` | v5.9 Settings tab, part 3: switch detection rules, keyboard use, state that survives a restart, big tables stay quick. |
| `t63` | v6.0 Overlays tab: placement, gate, the colours in use, the colour / preset / style editor, applying / resetting / undoing the theme, the overlay list (groups, filters, search, switches, long press, details), failures, Back, resuming after a restart, escaping. |
| `t64` | Settings tab: the independent review's findings (journal Revert by id, phantom null rows, long unbroken text, touch long press with the row redrawn, failed refresh, late answers, stale tables, creating over an unseen name, choice settings are not switches, refused Save keeps the editor, disabled looks, focus, names like "constructor", unknown outcomes, the real value in the editor, small things). |
| `t65` | File manager: press and hold with a touch screen starts selecting (the list is redrawn under the finger); a hold of any length must leave exactly that row picked, not toggle it again or open it. |
| `t66` | Overlays tab, second pass (the lessons of the Settings tab's reviews): touch press and hold with the row redrawn, answers that come late or never, keyboard focus, refresh failures, disabled looks, the sheet and Back with the preset list. |
| `t67` | Overlays tab, review round: what the independent reviews found (journal overwrite, the restart record, editor state, unknown styles, Android 11, focus and keyboard, "constructor" targets, a dead Back, a stale list, an empty list after a change, the switch warning). |
| `t68` | v6.0.1: the Settings tab is now "Hidden Settings" (🛠️). The name, the card and the menu say so, the order of the tabs is the same, and what an earlier version saved (the last table, filter and sort; the log of changes) is still used. |
| `t69` | v6.0.3: the single-app sheet (the ⋯ menu of an app) stands a little taller than the other sheets (93% of the screen, not 85%), so the lists under its buttons get more room. Nothing else changes: the other sheets keep their height, a strip above stays tappable, the end of a long list can still be reached, and the sheet follows a shorter window (on-screen keyboard). |
| `t70` | v6.1 Application Manager top: the header button (the colors palette then, the settings gear now) is bigger, the big stat buttons (Total installed, Running, ...) light up for the filter the list shows, and a tip under Export / Share CSV says the filters scroll sideways (and goes once they have been). |
| `t71` | v6.1 Installer, splits: the base and the splits that fit this phone (CPU, screen density, language) are ticked and every other one is left off; "Select all splits by default" is off by default; "Match this phone", "Select all" and "Only base" work. |
| `t72` | v6.1 Installer: a small ▾ button at the end of the installer source (-i) box and of the requester / originating URI box opens a list of common values; choosing one fills the box. |
| `t73` | v6.1 Installer: when an install worked, the result dialog has "Launch Application" and, under it, "Application Settings", above "Done". The dialog stands taller for them and the output box keeps the height it has without them. |
| `t74` | v6.1 Installer, finding and picking packages: a progress bar while "Find APKs on this device" runs; press and hold a found file to delete it from the device (with a tip that says so, and an Undo bar); the page swipes down to the next box once a package is picked. |
| `t75` | v6.1 Permissions: the first time the app opens it offers the three accesses that have no dialog of their own (All files access, Usage access, Display over other apps); the same sheet is under About; and an action that fails for want of file access asks for it on the spot, then carries on by itself once the access is there (file manager, storage search, reading a package). |
| `t76` | v7.0 Tabs: the tab bar is built from one registry (new names, two-line labels, new order); the header gear opens Settings; the Feature List (switches, arrows, Reset to Default) with Application Manager and About fixed; the choice is kept, a saved choice from an older version is made to fit, a tab that is off is not opened by a link or by Back (and the links do nothing else: no download starts, no sheet closes; an APK opened from outside brings APK Installer back); the keyboard focus and the moved row stay where they were; the bars that stick under the header follow its real height at three widths (and without ResizeObserver). |
| `t77` | v7.0 Application Manager search: the bar sits under all the filters with a menu at its right end (names, package names, regex), all on at the start, kept between launches, one of names and packages always on, an invalid pattern or one that could freeze the list (3,000 apps) searched as text, exact matches with regex off; the menu opens in view with the batch button out of the way (five screens, with and without a selection), closes by tap, Escape, Back, typing, going into the box and leaving the tab but not by a scroll; the note is readable on the light page and set once. |
| `t78` | v7.0: no emoji in the sources (page, changelog, layouts, Java) or in anything drawn on any tab or sheet; only the two settings gears remain. |
| `t79` | v7.0 review of the emoji removal: a line that says something failed says so in words and color (store, installer, search, VirusTotal), the buttons that were told apart by a picture (danger, main action, pinned command, install-time permission, shell launch) are told apart again, the terminal find box at three widths, sub-tab sizes, the new tab names in sentences, no symbol written as an escape. |
| `test` | Working Modes (switching, Shizuku, IP:port entry, auto-detect) and Material 3 / Material You color presets |

</details>
