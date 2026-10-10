# Changelog

## v7.12.9-Pro (versionCode 851)

A security and stability release: no new tabs. Two small additions (below) came in with it; everything else hardens what was already there.

- **The bundled adb prefers a private socket.** The app starts its own adb server on a unix socket in its own folder that only the app can reach. Only when the phone refuses such a socket does it fall back to the old loopback port 5042, as in earlier versions; on such a phone the old exposure is unchanged. (Which path a phone takes has not been checked on a device.)
- **Downloads are safer, in the clients this release changed.** The updater, F-Droid index, store-detail, UAD list, Morphe patch/bundle download clients and the Morphe Helper's in-app browser downloader follow redirects by hand and refuse an `https` to `http` downgrade or a hop from an outside address to a local one; credentials are dropped when the host changes (the browser downloader works out its Cookie header again for each hop). The Morphe patch and bundle downloads stop at their size cap before writing the chunk that would pass it. Requests that carry an API key refuse redirects altogether in the AI provider clients and the VirusTotal lookup and upload; the Morphe VirusTotal client follows only safe redirects within the same site and sends no key or body to a different one. A test now fails the build if new code opens a connection without redirect handling.
- **Installs fail closed.** A store install checks the published SHA-256 (when the repository publishes one) and that the download is the package asked for; app updates and the app's own update also compare the signers of the installed and the downloaded app (a store install does not). A value that cannot be read or verified now stops the install instead of being skipped.
- **VirusTotal uploads only go to VirusTotal.** The upload address is checked against the host before the file is sent.
- **Keys live in the Keystore vault.** The GitHub token and the VirusTotal key (and its approval) are sealed with a Keystore key instead of sitting in plain preferences; older plain copies are moved on first use. If the Keystore refuses, the app says "could not be stored securely" and does not write the secret in the clear.
- **Three groups of the page's calls into the app are checked.** Install options, app-op names and modes, and dex compile modes (and the package names that go with them) are matched against allow-lists in the app before any command is built, and a bad package name gets its own message. The rest of the app's page-to-app calls are not covered yet.
- **A crafted name in the Morphe tab can no longer run a command.** The Morphe tab built its tap handlers from package names, versions and repositories that come from a third-party patch bundle and the community list; a name such as `x');AndroidBridge.executeShell('id');//` ended the quoted argument and ran a shell command when the card was tapped. Values now go into the handlers as JSON strings (27 handlers in the Morphe tab, plus the VirusTotal link, the Task Manager kill button and the profile buttons), and a test fails if a handler builds a string argument the old way again. (Shown with a stand-in for the app, not on a phone.)
- **The ADB cheat sheet opens in the browser.** The button used to load the gist into the page as a script (JSONP) and put its HTML into the page, so whoever controls that gist, or anything on the way, could run code with the app's shell, file and install access. It now opens the gist in the browser and adds nothing to the page.
- **Connected Devices: adb options that move adb to another server are refused.** The `adb` command of that tab passed up to 80 arguments on; options such as `-P`, `-H`, `-L`, `-a`, `--server-socket` and `--one-device-server`, and the commands `kill-server`, `start-server`, `server`, `nodaemon`, `fork-server` and `reconnect-server`, are refused in the app (also behind `-s <device>` and behind `wait-for-device`). Everything a person types for a device is unchanged.
- **The full-screen terminal: restarting a session no longer breaks the new one.** Closing a session and starting another under the same name used to let the old session's late end remove the new one from the app's list (the terminal could not be typed into, resized or closed, and the new shell kept running unseen). Starts are now numbered; a late end, late output or a slow older start can no longer touch a newer session, and the page drops notices of an older start.
- **The full-screen terminal: a big paste no longer freezes it.** Pasting about 100 KB while a program floods its output (`yes`, say) could block the paste, the resize behind it and the acknowledgement that lets the output continue. Input and resizes are now queued (up to 1 MiB; a larger paste is refused, not cut) and a separate writer sends them to the program in order; input for a program that has ended is refused. Checked against the real helper on a computer, not on a phone.
- **Five of the page's file calls refuse the app's own private files.** Sharing a stored file, opening it with another app, uploading it to VirusTotal, sending it by Bluetooth and deleting a backup used to take any path the page gave them, including the app's settings, the adb key and the logs. These five calls now refuse the app's data folder (allowed: the Morphe Helper's downloads and the cache folders for files you picked or downloaded; a link put in place of an allowed folder, or a hard link to a private file, is refused too), and a backup is deleted only if the app lists it. Files in shared storage and patched APKs still work. This is not a complete barrier: the File Manager's own routes can still copy a private file into a folder these calls accept, and the page can still run commands (C-016 in the handoff, and the known gaps below). Not run on a device.
- **Termux link: silent connections no longer hold up the real one.** Another program on the phone could open the link's loopback port and say nothing; each such connection held the one accepting thread for up to 3 seconds, so a few of them kept Termux's real connection waiting until the timeout. The link now polls the first message of all pending connections in turn without blocking: no thread per connection, at most 32 sockets held at once (the oldest is closed when more arrive), and a connection that speaks is looked at within a short wait however many silent ones are queued. This is not protection against a flood: the port still has a finite backlog, and any program on the phone can connect before the token is checked.
- **RAR archives:** a solid RAR5 entry that cannot start no longer ends the whole extraction with an internal error, and a RAR5 entry whose unpacked size is unknown no longer skips every size guard.
- **Signing and zip alignment:** a failed write while signing no longer leaves a truncated APK and keeps the real error; a stored entry with an unknown extra field close to 64 KB stays 4-byte aligned (it used to lose its padding).
- **Smaller fixes:** launching an app through the assistant slot now saves your assistant setting first and puts it back at the next start if the app was killed halfway; ZIP extraction stops at an entry's declared size and a zip's entry count is capped; a line break in a file name can no longer inject phantom entries into SD Maid's stat-based scans (records carry their own framing and end marker) or into the storage search for package files (the search skips such names); SD Maid's plain file listing still splits such a name (C-005 in the handoff); a command that finishes without its exit marker (timeout, start failure, unauthorised Shizuku) is no longer reported as done; release builds log only the mode and sizes of a command, not its text; background workers (SD Maid, Connected Devices, trackers, the Task Manager poll) are stopped when the app closes.
- **Dex optimization: two more modes.** The Dex optimization sheet (single app and batch) now offers **space** and **reset**. Reset puts the app's dex optimization back to the state Android uses after an install (`pm compile --reset`); the exact result depends on the Android version, and the sheet says so.
- **Standby bucket.** A **Standby** button in the app menu shows the bucket the phone has an app in (`am get-standby-bucket`) and sets it to active, working set, frequent, rare or restricted. It reads the bucket back and only reports success when the phone shows the one you asked for.
- **For people changing the code:** AGENTS.md describes how to build, test and send changes (one finding per pull request, tests, the human-approval list).
- **Tests and docs:** `docs/DEVICE-TEST-SCRIPT.md` is a step-by-step script of adb commands for the phone (it touches no protected file); `node tests/docs/check-shell-blocks.js` checks that every shell block in the Markdown docs parses with `bash -n` and has no bare `<placeholder>`; new suites check that a hung command is killed: the SD Maid shell wrapper (the shell itself is killed and the call returns at its deadline; a child it started is not part of that promise) and Rish (the command and its whole process tree).
- **Not run on a device:** none of the above has been run on a phone by the people and tools that wrote it. The device checklist has a new section (19) for selected items (the private socket, the vault upgrade, download and install checks, dex `space` / `reset`, the standby bucket), `docs/DEVICE-TEST-SCRIPT.md` turns it into adb commands and covers the terminal, and `docs/RELEASE-CHECKLIST-v7.12.9.md` lists what to try after installing the build. The Termux link, RAR, signing and zip-alignment fixes have tests on a computer but no on-device step.
- **Known gaps, left for a later release:** in ADB, Shizuku and root mode a store or source install (`storeInstall` / `storeSourceInstall`) still installs the https APK the page names without a confirmation from the app itself (the native confirmation, #109, is not in this release; the signer and hash checks above still apply); the page can still ask the app to read and write the app's own files through the File Manager's routes (C-016 in the handoff), and a file swapped for a link between the check and the open of a path is not caught (it needs something already running with the app's own file access); the VirusTotal key is still passed from the page with each scan (it is stored sealed, but the page holds it in memory); there is no content security policy yet; a program that ignores the terminal's hang-up survives closing it (needs the native helper rebuilt); a few small RAR findings and the free-space check of `ZipTool.extractTree` are listed in `docs/COPILOT_CLAUDE_HANDOFF.md`.

## v7.12.8-Pro (versionCode 850)

- **The APK Installer tab is now APK Installer/Updater** (tab bar: Installer/ over Updater). Two boxes sit at its top, like the Terminal and the ADB Console: **Application Installer** on the left (the old APK Installer, shown every time you tap the tab) and **Application Updater** on the right (this app's own update, Galaxy Store and open-source updates, the Play Store card, and the two new cards below). The **App Stores** tab keeps its name and holds only the stores. There is no App Updater tab; the update count shows on the tab and on the Application Updater box, and everything that used to open the App Updater (an app's Update button, About, Download) opens that box. A saved tab choice from an older version is made to fit. Test: `t150`.
- **Find and download an app** (first card of the Application Updater box): search by app name or package name (a name is looked up in the installed apps, then Aptoide's public search, only to get the package name) and download the file from APKMirror (default), Uptodown, Aptoide, F-Droid or IzzyOnDroid (the last two through the sites' public package API; F-Droid's file is F-Droid's own signed build, and the check is that it is a real package of the asked name and version); Google Play only opens its page. Options: a Version box (empty is the newest), Allow split APK, Exact version only, Allow closest nearby version, Skip alpha and beta builds, VirusTotal scan, Install on download, Use the Default Ask Agent to search as well; save to the app's own storage, Downloads/App Updater or a folder you choose. Each result has Download, Versions and Open on the web; a browser check opens in the app's own browser. The file must be a real Android package, for the asked package, in the asked version. Tests: `t150`, Java suite `reposources`.
- **KeyStore card in the Application Updater box**: Export KeyStore (to Downloads/Morphe Patcher/morphe.keystore), Import KeyStore and New key (asks first). It is the key that signs apps patched in the Morphe Patcher tab, now reachable without opening that tab. Test: `t150`.
- **The Default agent is now the Default Ask Agent**, and it is a research agent: it reads the web and works out an answer; it answers every Ask agent button and the Application Updater search, and never changes anything. Perplexity is the default until you choose (the built-in web lookup answers until its key is added); the list is grouped (No default, Web research with Perplexity, Exa, Browser Use and Crawl4AI, Chat models with an API key, Free Open-Source) and no longer holds the coding agents (Cursor, Copilot, OpenCode). Choosing No default is remembered. The test button reads Test the Default Ask Agent. Test: `t151`.
- **Exa is a new research agent** for the Ask agent buttons (in the Web research group, right after Perplexity): connect it with a key from dashboard.exa.ai/api-keys (new accounts get free credits; the key is kept encrypted and sent only to api.exa.ai; testing it makes one small search); an Ask agent button sends one question to Exa's answer service and shows the answer with its sources. It is not a chat in the Command-Line Interface tab. Tests: `t152`, Java suite `agentrules`.
- **Authorization Manager** (About tab): the app has its own authorization code of 25 letters and digits in five groups of five, made on the phone and kept in the app's private storage (it never leaves the phone). The About tab shows it with a copy button and a circular refresh button (it asks first, then replaces the code; the old one is gone). Nothing accepts the code yet: it is kept for later. Tests: Java suite `authmanager` (shape, kept, replaced, repaired when damaged), UI test `t151`.
- **Engine addition for the above**: Morphe Helper's engine can search by app name (`helperFind`), save to Downloads/App Updater and copy the file into a folder you choose. Test: `t150`. For F-Droid and IzzyOnDroid, "newest" is the repository's own suggested (stable) build, and a downloaded build whose native libraries are not for this phone's CPU is deleted with a message to pick another from Versions (the repositories' API does not say which CPU a build is for). Unpinning an answer in History now cuts the others to the 12-entry limit at once.
- **History in the log-entry and Hidden Settings windows too**, and **pins**: a **Pin** next to an answer keeps it when newer ones push the others out (up to 30 pinned beside the last 12); **Clear history** keeps the pinned ones. Test: `t149`.
- **A Test button in every agent's Connect sheet** (Command-Line Interface tab): it asks the agent the same sample question as the Settings test and says whether it works, with the progress bar and Stop. Not on explained entries or tools that sign in through Termux. Test: `t149`.
- **Connected Devices: Disable is blue and Uninstall red**, with Enable and Reinstall green, like the app menu. Test: `t149`.

## v7.12.7-Pro (versionCode 849)

- **Test the default agent.** The Default agent card in Settings has a button that asks the chosen agent one sample question (whether the Calculator package can be disabled; nothing else is sent) and says whether it works and how long it took, or why it did not. It has the same progress bar and Stop as the Ask agent window. Test: `t149`.
- **A short history in the Ask agent window.** A **History** button lists the last 12 answers (newest first, with the date and the agent); tap one to read it again with its question without asking anew, **Ask again** gets a fresh one. Asking about the same thing again replaces the old entry; **Clear history** removes them all. Kept on this phone only. Test: `t149`.
- **The log-entry and Hidden Settings windows have the progress bar and Stop too** (the same piece as the Ask agent window; closing the window stops the request). Test: `t149`.
- **Enable and Reinstall are green in the Connected Devices tab** (each row and the batch bar), like the app menu. Test: `t149`.

## v7.12.6-Pro (versionCode 848)

- **Ask agent works with no agent and no API key.** With no default agent chosen (or one that is not connected) every Ask agent button now answers by itself with a built-in **web lookup**: it searches the web (Bing, then Wikipedia if Bing gives nothing) for a few words (for example `android com.example.app safe to disable`), reads the first result pages and shows the passages that mention the app, setting or permission, with the sites as buttons. Only those search words leave the phone and the box says so. It is not an AI answer (choose an agent for that) and not the Browser Use or Crawl4AI projects, which need their own computer or key. Nothing changes when an agent is connected. Tests: `t144`, `t145`.
- **Crawl4AI and Browser Use are real Ask-agent sources.** Both are now agents in the Command-Line Interface tab (Free Open-Source) and in the Default agent choice: paste a free-tier key from their hosted cloud (Crawl4AI: api.crawl4ai.com; Browser Use: cloud.browser-use.com), it is tested, kept encrypted and sent only to that service's own address. A key that works becomes the default agent when none is chosen. **Crawl4AI** answers through its answer service (it searches, reads pages and writes the answer, with its sources); **Browser Use** starts a short browser task that looks the question up and shows its output (a minute or two). They answer the Ask agent buttons only, not the Terminal chat. Also: an error in the Ask agent window now reads as a sentence (before, some showed as an object). Tests: `t147`, `t144`, `AgentRulesTest`.
- **A Row tints slider** (Settings: Off, Subtle, Normal; Subtle is the new default). The app menu's **Enable** and **Reinstall** buttons are tinted green, like Freeze (blue) and Uninstall (red). Test: `t148`.
- **Progress bar and Stop in the Ask agent window.** While an answer is on its way a bar shows it (by steps for a Browser Use task, moving for the rest) and **Stop** ends it at once (it cancels the request, asks Browser Use to stop the task, and also works for the web lookup); closing the window stops it too. Test: `t148`.
- **Own server (OpenAI-compatible)** joins Jan.ai, AnythingLLM and Ollama in the Free Open-Source list and in the Default agent choice: any server on your computer or network that speaks the OpenAI chat API (LM Studio, llama.cpp, vLLM, Open WebUI...). Its address is kept with the key and the key goes only there. Ollama's sheet now says it works on a computer too. Tests: `t148`, `AgentRulesTest`.
- **Row tints are now very subtle** (the whole-row green, blue and red of running, frozen and uninstalled apps are about a third of what they were; the boxes at the top keep their colours). Test: `t133`.

## v7.12.5-Pro (versionCode 847)

- **"Total Installed" is now "Installed", and it no longer counts the uninstalled apps.** Tap it to see only the installed apps. Tap **Installed** and **Bloatware (Uninstalled)** together to see every app on the phone. The list still opens on every app with no box lit (the **All Apps** pill, which also clears the filters, is lit). A new **Installed** pill sits next to All Apps. Tests: `t70`, `t104`.
- **The app menu's buttons.** **App Info** and **Uninstall** swapped places (App Info after Clear Data, Uninstall after Rem Updates); **Uninstall** is tinted red and **Freeze** blue. The **Ask agent** chip stays under Share, a little to the left, and the star symbol is gone from every Ask agent button. Tests: `t144`.
- **First launch: the phone's language and a Language drop-down.** A fresh install starts in the language of the phone when the app has it (a German phone: Deutsch; Portuguese of Portugal: Português (Brasil); a language the app does not have: English). The first-launch permission sheet has a **Language** drop-down with every language to change it at once; an app that is in use already, or one where a language was chosen, is left alone. Test: `t146`.
- **A free source without any setup.** With no agent chosen (or one that is not connected) an Ask agent button no longer jumps to Settings: its window opens with an **Open Settings** link (v7.12.6 adds a built-in web lookup that answers there). Tests: `t144`, `t145`.
- **Browser Use and Crawl4AI** join the free/open-source list of the Command-Line Interface tab as explained entries (what they are, what they need, Website and Source buttons). (v7.12.6 turns them into real Ask-agent sources, with a free-tier key.)
- **Running and uninstalled rows are tinted too.** In the Apps tab the whole row of a running app has a green tint and the whole row of an uninstalled app a red one, like the frozen (blue) rows; the Running and Bloatware (Uninstalled) boxes at the top use the same green and red. A selected row keeps the selection colours. Test: `t133`.
- **A frozen app's whole row has a blue tint** in the Apps tab (the same blue as the Frozen box), not only its FROZEN badge. A selected row keeps the selection colours. Test: `t133`.

## v7.12.3-Pro (versionCode 845)

- **The Apps tab boxes have their own colours.** Frozen is **blue** (the box, its number, and the FROZEN badge on the rows), Enabled is a **more vivid green**, and System is a clearer **purple** (box, number and SYSTEM badge). They no longer follow the theme, so Material You or a palette cannot turn them into other shades. Test: `t133`.

## v7.12.2-Pro (versionCode 844)

- **Clear Data and Remove updates in the app menu no longer ask first either.** No button of the single-app menu asks a question now: Uninstall (since v7.12.1), Clear Data and Remove updates run at once. Clear Data still cannot be undone, and Remove updates puts a system app back to its shipped version. Test: `t115`.

## v7.12.1-Pro (versionCode 843)

- **Uninstall in the app menu no longer asks first.** The button just uninstalls (a system app stays on the phone and **Reinstall** brings it back). **Clear Data** and **Remove updates**, which cannot be taken back, still ask. Test: `t115`.

## v7.12.0-Pro (versionCode 842)

- **Logcat keeps its lines.** Each live poll used to replace the whole view with what the last 300 lines of the device's log held, and `logcat -t N` counts lines of the whole buffer *before* the level filter on many Android versions: at Error level the view was usually empty and what had shown vanished a second later. Now every poll is merged into the lines already kept (`lcMerge`: a line is added once; identical lines inside one fetch, such as a recursion's stack frames, are all kept; up to 8,000 lines), a poll that finds nothing or fails leaves the screen alone, a new level / filter / app starts a new view and answers for an old one are dropped, and `logcatImpl` reads 3,000 to 10,000 lines back whenever a level, app or text filter is set and cuts the tail itself. Test: `t41`, `t145`.
- **Record the log to a file, and look at it as a still picture.** **Record to file** in the Logcat tab writes the view's log (level, filter, app) to `files/logs/logcat_<date>_<time>.log` every two seconds, in the background (`LogRecorder`: only lines not yet written, header lines, 25 MB limit, UTF-8; Java test `logrecorder`). **Recordings** lists them; stopping a recording (or tapping one) opens the **still picture**: search with highlights, the level key, pages of 500 entries, the entry window, **Copy shown**, **Save**, **Share**, **Delete**. Bridge: `logRecStart`, `logRecStop`, `logRecStatus`, `logRecList`, `logRecRead`, `logRecDelete`.
- **Default agent.** Settings has a **Default agent** card right under Language: the Coding Agent that answers every **Ask agent** button (it keeps its key and model from the Command-Line Interface tab, but is a separate choice from the agent the terminal opens with). With none chosen, or one that is not connected, the button opens Settings on that card instead of sending anything.
- **Ask agent buttons everywhere something may need explaining** (`agentBtnHtml`, `askAgentAbout`, one window that shows exactly what is sent and asks at once): every row of Hidden Settings (a setting without a description says *No description yet*), the app menu's permissions, app ops, activities, services, receivers, providers, features and libraries, overlays, Task Manager processes, UAD-NG Debloater rows without a description, trackers, what SD Maid found, and log entries. The Logcat entry and Hidden Settings editor buttons are now **Ask agent** too.
- **App menu tags.** The classification chip reads only the level (no "UAD-NG" text), and for an app the list does not know an **Ask agent** button takes the chip's place.
- **SD Maid.** *Clear Data from Uninstalled Apps* and *Trim Caches* now come after the four SD Maid tools.
- Tests: `t145` (new), `t144`, `t133`, `t41`, `t76`, Java `logrecorder`.

## v7.11.2-Pro (versionCode 841)

- **Ask the agent about an app the UAD-NG list does not know.** In the app menu, where the UAD-NG chip is missing, a chip **UAD-NG Not listed · Ask** opens **Is it safe to disable?**: a button asks the Coding Agent chosen in the Command-Line Interface tab what the package is, what stops working without it and whether it is Safe, Caution or Do not touch (`appAskRun`, same plumbing as the log-entry and Hidden Settings explanations). Only the package name, the app's name, system or user app and its state are sent. **Web search** works without an agent. Test: `t144`.
- **Translations.** The 1,237 strings added since v7.10 (Morphe Patcher, SD Maid, Hidden Settings, Trackers, Connected Devices, ...) are now translated into German, Spanish, French, Italian, Portuguese (Brazil), Indonesian, Turkish, Russian, Arabic, Hindi, Japanese, Korean and Chinese (Simplified).

## v7.11.1-Pro (versionCode 840)

- **Hidden Settings: back up and restore.** The **Changes** sheet has **Back up changes** (shares `hidden_settings_<date>.json`: for each setting changed with the app, its table, name, current value and
  the value it had before) and **Restore…** (picks such a file, checks every name and value, compares with the phone, asks, then puts the values back one after the other with one report at the end; a null value
  deletes the setting). Every restored setting goes into the history again, so it can be reverted. `sdbBackupData`, `sdbRestoreParse`, `sdbRestoreRun`; `sdbPut` / `sdbDelete` got a `silent` option. Test: `t143`.
- **Trackers: Update the list.** A button in the Trackers box downloads the current Exodus Privacy list (`TrackerUpdate`: a plain GET of the public API, checked for at least 300 trackers with code names before it
  replaces anything, cut down to what the app uses) into `files/trackers_user.json`, which then wins over the copy in the app; the app's results are read again with it. Java test `trackerupdate`, UI test `t142`.
- **Morphe Helper: today's VirusTotal number is VirusTotal's.** In the Helper's settings the "Today: N of 500" bar uses the key's counter from VirusTotal when the key is approved (so scans made in the Installer
  count too) and says "(counted by VirusTotal)"; "This minute" stays this app's own count. Test: `t119`.

## v7.11.0-Pro (versionCode 839)

- **Trackers.** A **Trackers** pill in the Apps tab (apps with at least one known tracker library) and a **TRACKERS** chip in the app menu, under the UAD-NG chip: green 0, orange N, grey ? when the code cannot be read.
  Tap the chip for the list (name, kinds, web address) with the Exodus credit and the limits of the method. The app reads the class names in each app's dex files (base and split apks; a class must be *defined*
  there, a mere reference does not count) and matches them against the bundled Exodus Privacy list (`assets/trackers.json`, 428 detectable trackers copied 2026-10-03, ODbL 1.0) with an Aho-Corasick automaton.
  Offline, no working mode, nothing sent. Results are kept per app in `files/trackers_cache.json` until the app's version, update time, number of splits or the list changes; the first tap on the pill scans the
  installed apps once in the background with the count growing on the pill. `Trackers`, `TrackerDb`, `TrackerMatcher`, `DexTypeScanner`; bridge `trackerCached`, `trackerInfo`, `trackerScan`, `trackerScanStop`.
  Tests: Java `trackers` (hand-built dex files: defined vs referenced classes, stored and deflated entries, splits, damaged files), UI `t142`.
- **Command results.** A move done through the shell printed `OK` but was judged by the `FMOK` marker, so a move that worked was reported as failed. Every `OK` / `FMOK` check (File Manager, backup, APK
  staging) now goes through `FileRules.okLine`, a whole line, so a path with the letters inside (`/sdcard/BOOKS/...`) is no success. The quick list's force stop reads failures at the start of a line, like the launch scans.
- **Tests.** `t109` no longer assumes the last call was its own (the Connected Devices tab refreshes its device list now and then; it turned `main` red on a slow runner), and the runner shows up to 500 characters of a
  failing check.
- **Docs.** The on-device checklist now covers v7.1 to v7.4 (language, font, File Manager, search, archives). Not done, on purpose: the "Write Secure Settings / assistant" workaround for unexported activities.

## v7.10.18-Pro (versionCode 838)

- **Select All / Clear All on the other list headers.** The Selected apps list (Show Applications) gets outlined **Select All** (adds every app the Apps list shows now) and **Clear All** (empties the selection and
  closes the list); the Clear Data from Uninstalled Apps sheet's buttons are outlined too and **Select None** is now **Clear All**; **New List** in Saved Applications has the same outline.
- **One app's leftover data from its row.** In the Uninstalled filter every row has a bin button: it asks, names the size in Root mode (or says the app has no data left), and clears just that app with the
  read-back batch (`clear_removed_data`). Test: `t141`.
- **The VirusTotal card shows today's quota.** Once the key is approved the card (and the Installer's VirusTotal card) says *377 of 500 lookups left today (123 used)* and when the UTC day ends, from VirusTotal's
  own counters (`GET /users/current`, then `/users/<key>/overall_quotas`; the user's counter wins over the group's), so the Installer's and Morphe Helper's scans count together. If VirusTotal gives no counter
  the app's own count is shown and labelled. Refresh button; read at most once a minute and after each scan. `MorpheVirusTotal.accountQuota`, `parseQuotas`; Java test `morphevt`, UI test `t141`.
- Not changed: v7.10.9 to v7.10.16 were never released on their own; they went out inside v7.10.17.

## v7.10.17-Pro (versionCode 837)

- **Force stop is read back.** After `am force-stop` the app lists the running processes (`ps -A -o NAME`, or `pidof` where that is missing) and says **Stopped** or **Still running** (with the reason an app can be
  up again at once: the system, a service, another app); single app and batch. `BatchVerify.State.running`.
- **Clear data is read back in ADB and Shizuku modes too.** The private folder cannot be read there, so the app counts the files and size of the app's folder on the shared storage (`/sdcard/Android/data/<package>`)
  before and after, and once more after 1.5 s to see **files that were written again**: **Data cleared**, **Cleared, new files written**, **Partly cleared**, **Not cleared**. An empty folder before proves
  nothing: the command's answer stands, with a note. Root mode still counts the private folder (and now also looks for recreated files). `BatchVerify.applyClear(…, after2, internal, running)`.
- **A spinner on the row.** While an app is changed and checked, its row (Apps list, Debloater list, Connected Devices list) shows a small spinner and **Working…** then **Checking…** in its corner, whatever the
  Progress messages switch says; a batch marks every selected row. Cleared when the result is in (or after 2 minutes). Test: `t140`.
- **Debloater list: a Removal Levels button.** A row opened by tapping it has a **Removal Levels** button that opens the sheet with the four levels (the same as the classification sheet's). Test: `t140`.
- **Morphe Helper and APKMirror's split files, checked against the live site.** The release page's variants table (APK and BUNDLE rows, architecture, DPI, build number) is read correctly on real pages
  (YouTube, Photos). Fixed: the variant's type is now the APK/BUNDLE badge wherever it stands among the others (a NEW or signature badge in front no longer hides a bundle), and when APKMirror's browser
  check stands in front of the variant page the in-app browser is sent to **that variant's page** (the bundle for the phone) instead of the whole release. Test: `MorpheHelperTest`.

## v7.10.16-Pro (versionCode 836)

- **SD Maid > Clear Data from Uninstalled Apps.** A button in a card above Trim Caches, with the description "Clears the data of apps that were uninstalled from this device while the `DONT_DELETE_FLAG` flag was
  active. This usually applies to third-party apps, and it can fix common problems when you install them again." It opens a sheet of the uninstalled apps (in Root mode only those that still have a data
  folder, with its size; `MainActivity.leftoverData`), you tick the ones to clear and confirm, and the batch runner does the rest (new action `clear_removed_data`): `pm clear`, else in Root the folders are deleted,
  else the app is brought back with `pm install-existing` and removed again without keeping its data. In Root mode the result is counted before and after (`BatchVerify.applyClear`). Test: `t139`.
- **Batch menu.** The grab handle in the middle at the top, like the other sheets, with the same gestures: pull up = taller, pull down = back, pull down again = closes into the checkmark button (apps stay selected).
  **Select All** and **Clear All** (it was "Clear") are outlined buttons. **Keep selection after running** is on every time (each new selection starts with it on; the switch is no longer remembered).
  **Share List** and **Show Apps** changed places, and Show Apps is **Show Applications**.
- **Package names** in all app lists (Apps, Debloater, Saved, Show Applications, results, Connected Devices) use `--pkg-color`, the theme's accent mixed into the muted grey, so they stand apart from the names.
- **Leaving the Apps tab drops the selection** at once (and the checkmark button and the batch menu, without their slide).
- **Running** turns **Uninstalled** off in the Apps tab filters.
- **One VirusTotal API key for the whole app**, in Settings > VirusTotal API key, with **Test key** (the key is "approved" once the test worked; changing it takes the approval away). The Installer's scan and Morphe
  Helper no longer have key boxes: when the key is missing or not approved they say so and have a button that opens the Settings card. Keys entered in older versions are taken over. Tests: `t139`, `t37`, `t119`.

## v7.10.15-Pro (versionCode 835)

- **UAD-NG classification sheet: the meaning of the level moved behind a button.** Tapping the UAD-NG tag on an app (in the Apps list, the Debloater or the app menu) no longer starts with the line that says what
  "Recommended", "Advanced", "Expert" or "Unsafe" means. The sheet now ends with **UAD-NG Wiki**, **Removal Levels** and **Close**; **Removal Levels** opens another sheet with exactly the lines of the Removal Levels
  list in the Debloater tab (they are copied from there: one text), and its Close returns to the classification. Test: `t118`.

## v7.10.14-Pro (versionCode 834)

- **Connected Devices: the apps are read back from the device.** After Enable, Disable, Uninstall and Reinstall (one app, or a batch) the app lists the device's apps again and reports what it found
  (**Disabled: Fit** when it worked; otherwise the sheet *That did not work* starts with *Checked afterwards: still installed / not disabled / not installed*); the app records follow the device, a command that printed an error for an app that is gone counts
  as done (with a note), and one that said "worked" for an app that did not change is a failure. A batch looks at the device once for all its apps (once more after 0.9 s when something is not yet as wanted)
  and the failure sheet names only the apps that are not as wanted. **Checking the device…** shows while it looks. Tests: `t138`, `t109`.
- **Settings > Progress messages.** One switch, **Say what is going on**, on by default: turns off "Working on it…", "Checking the phone…" and "Checking the device…". The result of the change and every problem are
  always shown. Kept as `progress_toasts`. Test: `t138`.
- **Clear data is read back in Root mode.** Root can read an app's data folder, so before and after the clear the app counts the regular files and the size in `/data/user/0/<package>`
  (`BatchVerify.dataStatCmd`, `applyClear`) and says **Data cleared** (12 files, 3.4 MB before, 0 after), **Partly cleared** (the app may have started again and written new files), **Not cleared** or
  **Nothing to clear**; for one app and for a batch (with the *Checking the phone…* step). In ADB and Shizuku modes the folder is not readable and nothing changes. Tests: `BatchVerifyTest`, `t138`.

## v7.10.13-Pro (versionCode 833)

- **One app at a time is read back too.** From the app menu (and the action button on a row), Uninstall, Reinstall, Freeze, Enable, Suspend and Unsuspend now run natively off the page's thread
  (`appActionChecked`), then ask the phone what became of the app and say so in the message ("Alpha: Uninstalled", "Bravo: Still installed"), showing the result window with the finding
  only when it did not work. Unreadable states fall back to the command's answer. Test: `t137`.
- **Suspend and Unsuspend are read back** (single and batch): the app's own flag is read from `dumpsys package <pkg>` (the User 0 line); an app whose flag cannot be read keeps the command's answer.
  Tag words: **Suspended / Not suspended / Still suspended**. `BatchVerify.State`, `BatchVerifyTest`. **Clear data, Force stop, Batch Ops and Command are not read back**: the phone gives a shell
  user nothing to read their result from, so they are still judged by the command (said in the Help Guide).
- **"Checking the phone…" on the batch progress sheet.** After the last command the sheet says **Checking the phone… (N apps)** with a full bar (Stop is off meanwhile) until the results open.
- **Command suggestions in the Connected Devices console.** The same grey line as in the Terminal and the ADB Console, for the other device: your earlier lines, common commands, the package names
  of the device (once its Apps sub-tab has read them), and adb's own commands in adb mode or after `adb ` (`adb shell ...` completes the device's shell). Right arrow or a tap accepts it.

## v7.10.12-Pro (versionCode 832)

- **Batch results come from the phone, not from the command.** A run of uninstalls often ended with "batch commands failed" although every app was gone. After Uninstall, Uninstall (keep data),
  Reinstall, Freeze and Unfreeze the app now asks the package manager what became of each app (`pm list packages --user 0`, and `-d` for frozen ones; once more after 0.9 s if an app is not yet in its
  new state) and reports that: the tag of each result is **Uninstalled** / **Still installed**, **Installed** / **Not installed**, **Frozen** / **Not frozen**, **Enabled** / **Still frozen**, the counts,
  the history and "Run again on the ones that failed" follow it, and a card says "Checked afterwards: uninstalled. The command reported a failure, but the phone says it worked." when the two disagree.
  A phone that cannot be asked keeps the command's own answer. Profiles that run these actions are checked the same way. Native: new `BatchVerify` (pure logic) used by `appActionBatch`. Tests: `BatchVerifyTest`, `t136`.
- **Contact the developer** now has its forwarding alias (bingblop.coral666@simplelogin.fr), shown in the window; the developer's own address is still nowhere in the app. Test: `t135`.

## v7.10.11-Pro (versionCode 831)

- **RRO/Monet > Overlays: enabled first, then disabled, then not changeable.** The list is in three parts with counts, **Enabled**, **Disabled** and **Installed, not changeable**; inside each
  part the overlays stay grouped under their target. Test: `t135`.
- **A very short description on every overlay** (for example "Gesture navigation: swipes instead of buttons", "Shape of app icons"). A best guess from the overlay's name and target
  (rule table `OVL_WHY`, first match wins, a generic line when nothing matches).
- **Command suggestions in the Terminal and the ADB Console.** A grey completion line above the input offers the rest of what you are typing: your earlier commands first, then the packages on the
  phone (after `pm`, `am`, `dumpsys` and the like), the Hidden Settings keys (after `settings get/put`) and a list of common commands. **Right arrow** with the cursor at the end accepts
  it; because phone keyboards often have no right arrow, **tapping the line** accepts it too. It never runs anything. Test: `t135`.
- **About: Issues and Contact the developer.** Two buttons at the top of the About card. **Issues** opens the project's GitHub issue page. **Contact the developer** opens a sheet to write a
  message (optionally with the app version, Android version and phone model) and opens the email app with the subject **ADB App Manager**. The message is addressed to a **contact alias**
  that forwards to the developer, so the developer's own address is not in the app or in this repository (test `t135` checks that it is not in the page). The alias is one constant
  (`CONTACT_ALIAS`); until it is set the sheet says the contact address is not set up yet and points to Issues. Native: bridge `composeEmail` (a `mailto:` intent with the subject and body).

## v7.10.10-Pro (versionCode 830)

- **Haptic feedback on every tap, with a switch.** Every tap on a button, a tab, a switch, a menu or a list row ticks (a short vibration), from one place, so nothing is missed and
  nothing ticks twice (ticks are at least 70 ms apart; only real taps count, not clicks the page makes by itself). **Settings > Haptic feedback > Vibrate on taps**, **on by default**;
  turning it on gives one tick as proof. Test: `t134`.
- **App Stores: a + tab for stores of your own.** The last tab is a **+**. Paste an F-Droid style repository (its index is read for its name and apps, with the same checks as the
  F-Droid tab), a GitHub or Codeberg **project** (a tab with that project, installing its latest release) or a GitHub or Codeberg **user or organization** (all their projects that are
  not archived or forks, by stars). The tab is **named by you** or filled in from the address (the repository's own name, the project's or user's name); Rename and **Remove this store**
  on its card; up to 12; kept across restarts. Native: `FdroidIndex.probe`, `StoreDetail.githubOwner/githubRepo/codebergOwner/codebergRepo`, bridge `storeCustomProbe`. Tests: `t134`,
  `StoreDetailTest`, `FdroidTest`.
- **App Stores: the details and screenshots of an app in every store.** Tapping a row in GitHub, F-Droid, Orion and your own tabs opens the detail sheet that only ShizuStore had: the
  list's data at once, then the full description, screenshots (tap to enlarge), license, stars and forks, topics, Website and Source, and Install. F-Droid style repositories supply
  their own description and screenshots (kept in a side file while the catalog is read, `StoreDetail.detailLine`); GitHub and Codeberg projects the README (readable text) and
  the fastlane screenshots or, failing that, the pictures of the README (badges, logos and buttons left out). A failure keeps what the list knows and says why. Native:
  `FdroidIndex.Details`, `StoreDetail`, bridge `storeSourceDetail`. Tests: `t134`, `StoreDetailTest`, `FdroidTest`.
- **Task Manager > Processes: Apps first.** A button next to the sort buttons puts the processes of apps above the system and kernel processes, each group keeping the sort order. Remembered.
- **Settings: the list of fonts found is emptied** when Settings is left (and when you tap the new **Clear this list**); a search that ends after you left is thrown away.
- **The gear in the header wears the app's colours** (it had the green of the mode badge).
- **SD Maid** is the tab's name now (it was SD Maid SE; the credits still say it is a port of SD Maid SE by darken). **Trim Caches in All Applications moved from Settings to this tab.**
- **SD Maid > AppCleaner uses the accessibility service to clear the remaining caches.** Found and fixed: the service switched itself off when it was turned on before the consent
  was given, so AppCleaner found no service and skipped the caches that need it; it now stays connected and only acts with the consent. The page no longer decides from a state that
  may be minutes old: Delete asks the phone first (and says what is missing), and always asks for the automation, which the phone allows or not. New button **Clear the rest with
  accessibility** on the AppCleaner card: clears only the caches no file access reaches, or says what is missing and offers to set the service up. Test: `t134`.

## v7.10.9-Pro (versionCode 829)

- **About: "Handy to know" now sits under Device Specs** (it was above it). Help Guide and test `t99` follow.
- **Connected Devices: Saved devices, with Delete.** A new **Saved devices (N)** line under the device picker opens the list of every device that was connected (up to 20 now, it
  was 8), newest first, with how it was connected and when. A device that is not connected has **Connect** and **Delete**; a connected one has Delete. Delete asks first; a device
  that is connected at that moment stays connected and is not saved again until you connect it again. **Delete all**, and a **Remember the devices I connect** switch (off: nothing
  new is saved and a dropped device is not reconnected by itself). Test: `t133`.
- **Task Manager, CPU tab: temperature unit.** The CPU tab shows the Units row with the **°F / °C** menu (the same choice as the Battery tab, which keeps its current-unit menu
  to itself); the CPU's temperature follows it. Test: `t87`.
- **Task Manager, GPU tab: the renderer is on top.** The renderer drop-down moved from the bottom to a card at the very top, above the graph, with the renderer in use (Default,
  OpenGL or Vulkan) in large letters. Test: `t87`.
- **Logcat: tap an entry to open it.** Every entry is a tappable row that opens in a window of its own with the level, tag, time, ids and the whole message, and the buttons
  **Copy**, **More info** (the Coding Agent chosen in the Command-Line Interface tab explains the entry: what it means, the likely cause, whether it matters, what to try; streamed
  into the window, with Copy the answer; with no agent the window says so and opens the place to set one up) and **Web search** (the tag and the first line, long numbers left
  out). Selecting text with a long press does not open it. Nothing is sent to an agent until More info is tapped. Test: `t133`.
- **Hidden Settings: more information.** 150 more settings are described by hand (global 100, secure 50) from the AOSP documentation: power button actions, Wi-Fi band, Bluetooth LE
  scan modes, DropBox limits, captive portal servers, GPU debug layers and ANGLE, the time zone / SELinux / APN / certificate update addresses, accessibility and lock screen
  options and more, with the values they take. (Android's own source, `Settings.java`, was read again: it holds no setting that the list did not already cover, so the rest comes
  from vendor settings, which no public documentation covers; those still get a guess from their name.) The editor of every setting has **Web search** and **Explain (AI)** (asks
  the Coding Agent; sends the setting's name, table and current value, only when tapped, and says it may be a careful guess for settings that belong to the phone maker). Test: `t133`, `t123`.
- **Apps tab: the boxes wear their colours.** Each box at the top (Running, Bloatware, Enabled, Frozen, 3rd Party, System) has a faint tint of its own colour and, while its
  filter is on, a strong shade with a ring in that colour, so the one in use is easy to see; a split box takes the colour of the side that is on.
- **Apps tab: Uninstalled starts a clean list.** Turning the Bloatware (Uninstalled) box, or the Uninstalled pill, on turns every other filter off first; filters added afterwards
  still combine. Test: `t104`.
- **Apps tab: the "Sort" text is gone** from the row (the menu is read as "Sort apps by" by screen readers) and the row starts at the left edge. Test: `t118`.

## v7.10.8-Pro (versionCode 828)

- **App Stores: tap a screenshot to see it full screen.** The screenshots in an app's detail sheet (ShizuStore) are now tappable and open in a picture viewer: shown to fit the screen on
  black, counted ("2 / 8"), with **pinch** zoom (up to 6 times), the **+ / − / Fit** buttons, **double tap** (2.5 times and back), **drag** of a zoomed picture (it cannot leave the
  screen), **swipe** or arrows to the next and previous picture (the neighbours are loaded ahead), and **Back**, the ✕, Escape or a tap beside the picture to close it, leaving
  the sheet as it was. A picture that cannot be loaded says so; only http and https addresses are shown. Test: `t132`.
- **Saved lists: back them up to a folder of your choice and restore them from a file.** A new card, **Back up and restore**, in the Saved Applications tab. **Choose folder…**
  opens Android's folder picker (the phone, an SD card, a USB drive, a cloud provider) and the app keeps the permission across restarts; with no folder chosen, backups go to
  Download/ADB App Manager. **Back up now** writes `saved-lists-YYYYMMDD-HHMM.json`; **Share…** sends the same file anywhere; **Back up automatically** keeps the folder's
  `saved-lists-latest.json` up to date a few seconds after any change (never with an empty set of lists, so deleting everything cannot wipe the last backup). **Restore from a
  file…** (the file chooser) and **Restore from the folder…** (its backups, newest first) check the file, then **Add them to my lists** (identical lists skipped, a clash of names
  comes in as "Name (imported)", a clash of ids gets a new id) or **Replace my lists with them** (the Quick list follows the backup). A stranger's JSON, a newer format, a file
  with no usable list or over 1 MB is refused in words; inside a good file, bad entries are skipped and package names, name and description lengths and the number of lists and apps
  are limited. Bridge: `pickBackupFolder`, `treeWriteText`, `treeListFiles`, `readTextUri` (content addresses only, 1 MB at most). Test: `t131`.

## v7.10.7-Pro (versionCode 827)

- **Morphe Helper downloads keep going in the background.** What the in-app browser downloads is now held by a foreground service (`DownloadService`, type dataSync): leave the
  app or let the screen go off and the downloads carry on. A notification shows how many files, how far (a bar and a percentage when every size is known), how fast and how
  long is left, with **Pause all** (every download that is going or waiting is paused and stays in the list to Resume) and **Cancel** (they stop and the unfinished parts are
  removed). The processor is kept awake (a partial wake lock, renewed while something downloads and given back the moment nothing does). When the last download is over
  the notification gives way to a short note of how it went ("keyboard.apkm is saved", "2 downloads finished", "1 finished, 1 stopped. Open the app to retry") that opens the app
  when tapped. The service starts with the first running download and stops itself with the last; on Android 15, whose six-hour limit for data-sync services ends it, what is
  going is paused rather than lost. A switch under the download list, **Keep downloading in the background** (on by default), turns the service off for people who want the
  old behaviour. A phone that refuses the foreground start only loses the notification: the downloads still run. Tests: `DownloadNoticeTest` (21 checks: the counts, the
  percentage, the smoothed speed and time left, the unknown size, waiting and paused jobs, how it ended), `HelperDownloadsTest` (pause all, cancel all, the active count) and `t119`
  (the switch).
- **My Themes: save and switch your own looks.** A new **My Themes** card in Settings (under Palette Style) saves the look in use under a name (up to 40 characters, up to 30
  themes, unique without regard to case): the palette or palette style, the source color, your color tweaks for light and dark, and Pure black. Tapping a theme wears it at once
  (Appearance, light or dark or system, is not part of a theme and stays as it is); each theme shows four swatches in the mode in use, says what it is ("Vibrant · #E91E63",
  "Midnight Slate · tweaked · pure black"), is highlighted while it is the look in use, and can be **Renamed**, **Updated** with the look in use now or **Deleted** (each asked
  first where it replaces or removes something). Saving under a name that exists asks before replacing it. The saved list is checked every time it is read: entries that are not
  themes, unknown palettes or styles, bad colors and repeated names are dropped or put right. Test: `t130`.

## v7.10.6-Pro (versionCode 826)

- **Palette Style in the Theme menu.** Settings has a new **Palette Style** card above the curated palettes: one **source color** turned into a whole theme (accent,
  background, cards, text, in light and in dark) in one of nine styles: **Tonal Spot, Vibrant, Fidelity, Content, Neutral, Expressive, Fruit Salad, Rainbow** and
  **Monotone**. Tap a style and the app is rebuilt from the source color at once; the tiles preview each style for the source color and the mode in use. The source
  color is automatic (the wallpaper's main color on Android 12+, otherwise Material purple) or picked with the color popup; an **Automatic** button goes back. The
  colors are worked out in the HCT color space with tonal palettes, the way Google's Material Color Utilities (Apache-2.0) does it, written again in a few lines
  (checked against known HCT values; Tonal Spot of Material purple gives the Material 3 baseline). Text keeps 7:1 and the accent 4.5:1 against the background for any
  source color, light and dark; Running stays green and Bloatware red in every style. Fidelity's and Content's third accent is an approximation of Google's. The
  style, source color and palette are saved with the theme and restored (a damaged value falls back to Tonal Spot / automatic); a color tweak still works on top, and
  choosing a style clears tweaks like choosing a palette does. Test: `t129`.
- **Connected Devices: Reconnect all and your own retry timings.** With two or more devices not connected a line above the cards offers **Reconnect all**, which tries
  each in turn and counts the result ("Reconnected 1 of 2"; a device that could not be reached keeps its card with the reason). Settings has a new **Reconnecting
  devices** card with the **Reconnect on its own** switch (the same one as in the Devices tab) and **Retry timings**: the seconds to wait before each attempt, such as
  `0, 8, 25` (the default), up to 8 attempts of 0 to 600 seconds; a box that is not valid says why and keeps the timings in use, and **Reset** goes back to the default.
  Test: `t128`.

## v7.10.5-Pro (versionCode 825)

- **Connected Devices: a device that dropped can be brought back.** Devices are remembered while they are connected. When one is gone (Wi-Fi asleep, cable out, Wireless
  debugging switched off, restart) a toast says so and a **Not connected** card stays under the picker with its name, how it was connected and when it was lost, and two
  buttons: **Reconnect** and **Forget**. Reconnect does what the kind of connection needs: an address is disconnected and connected again, and when that fails the same
  address is searched by its Wireless debugging announcement (the port changes whenever it is switched on; the new port is used and the device keeps its place); a device
  known by its service name is looked up by name; USB gets `adb reconnect offline` and advice (cable, unlock, USB debugging); the Bluetooth link is set up again through this
  phone's own ADB. A failure is told in words under the card. When a device drops while the tab is open, three attempts are also made on their own (at once, after 8 and after
  25 seconds; not for USB); the line under the card switches that off. **Reconnect** is also the first item of the device menu. **Disconnect** in the menu is a decision, not a
  drop. Test: `t128`.

## v7.10.4-Pro (versionCode 824)

- **Morphe Helper: a download list with Pause, Resume, Retry and Cancel.** What the in-app browser downloads is now a job of its own, not tied to the browser
  window. Close the browser and it goes on; start several (three run at a time, the others wait). Each job shows its size, speed and time left, and offers
  **Pause** (the bytes stay), **Resume**, **Retry** after a failure, **Cancel** (the part is removed), **Use this file** when it is saved and **Remove**; **Clear
  finished** tidies the list. Resuming asks the site for the rest only (HTTP Range with If-Range, so a file that changed starts again; a site that cannot do
  ranges, a 416 or a leftover part with no validator also start over, and the result is checked against the size the site named). Retry goes first to the
  address the download had reached (a storage address often outlives the page link that led to it) and says plainly when the link has expired (open the page
  again in the browser). The list is kept in a journal: a paused or broken download is still there after the app was closed or killed, and one that was running
  then comes back as Paused. The journal is not trusted: only the Helper's two folders, only web addresses, and a name is never a path. A broken connection is
  reported with how far it got and that Retry goes on from there. Tests: `BrowserDownloadTest` (41 checks, resuming against a real local server) and
  `HelperDownloadsTest` (29 checks), `t119` for the list.

## v7.10.3-Pro (versionCode 823)

- **System UI Tuner is gone.** Its tab, its code (SysUiOps, SysUiRules, the Battery, Clock and Demo mode Quick Settings tiles, their manifest entries and
  the Demo toggle), its tests, its section of the Help Guide, its entries in README, FULL-GUIDE, the device checklist and NOTICE (Tweaker's MIT notice)
  are removed. The Working mode and Stop apps tiles, the widget and everything else stay.
- **App Stores: a README reads as text.** A GitHub app's description is Markdown (Mihon showed `# Mihon [App](#) ![badge](...)`). Headings, badges and
  images (dropped), links (their text), bold, italics, code, lists, tables and quotes are now turned into plain text with its line breaks, and long
  addresses wrap instead of running off the sheet.
- **Bottom sheets work from their handle.** The small bar at the top of every sheet: pull it up for more room, pull it down to make a pulled-up sheet
  small again and, from normal size, to close it (a short pull springs back).
- **Morphe Helper: a version named with its build flavour is found.** Gboard's own version name is `18.0.3.954559732-release-arm64-v8a`, APKMirror lists
  `18.0.3.954559732`, so "does not list version ..." appeared although the release (an APKM) exists. CPU and `release` words inside a version name no
  longer make a difference when versions are compared, for every source.
- **Morphe Helper setting: Try the other sources automatically.** Off by default. On, This version, Newest and All versions go on with the next source
  when the chosen one does not have the version or does not work.
- **Morphe community finder: icons.** An app's icon was drawn beside its first letter and stretched; it now covers the letter, which is shown only until the
  picture is there.
- **Apps tab: the Action Button menu.** No label before it; the menu sits where the label was and shows the current choice. Tapping it opens a sheet with a title,
  a few words about what it edits and a line under every choice.
- **Terminal: Sync with Termux says what it did.** It starts the Termux shell again and prints what it took over (bash version, home, package folder, aliases,
  functions, phone storage). Aliases now work (they are off in a shell that has no keyboard until asked for), and a `~/.bashrc` that begins with the usual
  "only for interactive shells" line is read anyway. Termux itself no longer opens on every sync: **Set up storage in Termux** does that, and only when
  phone storage is not set up (before, `termux-setup-storage` asked to rebuild `~/storage` each time).
- **Terminal: Full screen.** A button below the screen makes the Terminal cover the whole app, with the screen taking the room above the input and the extra
  keys, following the on-screen keyboard. Back, the same button, or leaving the tab comes out of it.
- **Terminal: a real terminal, full screen.** The new **Real terminal** button opens a full-screen terminal on a genuine pseudo-terminal: vim, nano, top,
  htop, less, ssh, colours, the arrow keys, Ctrl-C and resizing work, with a Termux-style row of extra keys (ESC, TAB, sticky CTRL and ALT, arrows with repeat,
  HOME/END, PGUP/PGDN, symbols, F1 to F12), text size, Copy and Paste. Shell: this app's sandbox, the working mode (ADB, Shizuku or Root) or Termux. Android
  gives an app no way to open a pty, so the app carries a 4 KB helper (`native/pty/ptyexec.c`, built for arm64, 32-bit ARM and x86_64, no C library) that opens
  the pty, runs the program and relays bytes; the screen is xterm.js (MIT). Closing the screen keeps the session. The helper is tested on a computer and,
  for the phone's two architectures, under emulation (`native/pty/test.sh`); `PtyShellTest` and `t126` run the real thing.
- **Morphe Helper: a browser inside the app.** APKMirror's browser check stops a plain download, and the file then ended up in another app. **Open it here, in
  the app** (and **Open the site**) now open a full-screen browser in the app that presents itself as Chrome, so the check passes as it does in a browser.
  Whatever the page hands over as a file (.apkm, .apks, .xapk, .apk) is saved by the app itself, with the web view's cookies, straight into Helper's folder
  and read like any other download (`BrowserDownload`: file name from the headers, redirects followed by hand, a page that is not a file is refused,
  written to a `.part` file and renamed when complete). The pages get no way into the app (no JavaScript bridge, only http and https). New setting
  **Open the sources in**: This app, or The phone's browser. Not tested against the live site (it blocks the build machine): see the checklist.
- **Terminal: one-tap Termux setup.** In **Termux setup**, **Set up Termux now** updates the package lists, installs vim, nano, git, python, openssh, curl, wget
  and htop with `pkg`, and writes a starter `~/.bashrc` (colour prompt with the git branch, history, aliases, `mkcd`, `extract`, `serve`), `~/.vimrc` and
  `~/.nanorc`; **Choose tools** picks others (tmux, ripgrep, jq, zip, unzip, rsync, nodejs, clang, make, man) and which starter files. Only a marked block is
  written: your own lines stay and a second run replaces the block. A `~/.bash_profile` is made only when there is none, so login shells read `~/.bashrc`.
  The starter files are run for real in the tests (`t127`).
- Translation work is on hold until the feature set is finished.
- **Morphe Patcher: a chain of failed patches is one error.** When a patch raises an exception, every patch that depends on it fails with
  "depends on ..., which raised an exception" (Gboard showed 41 of them). The result card now leads with the first error, details open, and
  folds the dependent ones into **N more failed only because a patch they need failed** (Show names them). A bundle that asks the patcher for
  something it does not have (`NoSuchMethodException`, `NoSuchFieldException`) gets its own advice, where the word "dex" inside
  `NoSuchMethodException` used to make it say the bundle was damaged.
- **Morphe Helper: older APKMirror versions are found.** APKMirror's app page lists only the latest releases, so a version such as Gboard 18.0.3
  was "not listed". Helper now follows the page's **See more uploads** link and reads up to eight pages of it, for All versions and for This
  version when the version is not on the app page yet; it stops when the version is found, a page adds nothing, or a page cannot be read
  (APKMirror's bot check keeps what was found). Not tested against the live site (it blocks the build machine): see the checklist.

## v7.10.2-Pro (versionCode 822)

- **Morphe Patcher: patches that reflect on the patcher's internals work again (Gboard: 41 of 43 failed).** The Gboard bundle failed with
  `NoSuchMethodException: app.morphe.patcher.patch.BytecodePatchContext.getPatchClasses$morphe_patcher []`, and every patch that depended on
  the one that raised it failed with it. The bundle's extension step reads an `internal` member of the patcher by reflection. Kotlin puts the
  *module name* into the name of an `internal` member (`getPatchClasses$morphe_patcher`), and the engine was built as module
  `com_bloatware_bingblop_morphe_engine`, so the name the bundle looked for did not exist. The engine is now compiled as module
  `morphe-patcher`, as Morphe's own build is, and `engine/build-engine.sh` and the `enginezip` test refuse an engine without those names.
- **Morphe Helper finds and installs bundles (APKM, APKS, XAPK) as well as APKs.** New **Find in Downloads** button: it lists the APK / APKM /
  APKS / XAPK files in Downloads (and one folder level inside it) that hold the package, newest first, with version, format and number of
  parts; the package is read from inside each file, so the file name does not matter. **Use** takes a file (a copy goes into the Helper's
  folder). After **Open the site** the list appears by itself when you come back to the app. The result says *APKM, 5 parts* and offers
  **Install all parts**; without a working mode the parts are installed in one Android installer session (the system asks once), where the
  system installer used to be handed a bundle it cannot open. Texts say "app" or "bundle" where they said "APK".

## v7.10.1-Pro (versionCode 821)

- **Hidden Settings: every setting says what it does and which values it takes.** Under each name the list shows a short description and a
  **Values:** line (for example `0 = off; 1 = priority interruptions only; 2 = total silence; 3 = alarms only`), with the value the setting
  holds now in bold. The editor has an **About** box with the same, one value to a line and a **Now:** line that follows what you type; the
  documented values are buttons (`2 · total silence`). The app now knows about 1,100 settings of Android's Global, Secure and System tables
  (the common ones written out in plain words, the rest from Android's own documentation). A setting nobody describes gets a guess from its
  name and value (*What it probably is*: a switch, a time in milliseconds, an app's own setting, a Samsung one). A setting that takes more than
  on and off (`wifi_on`, `zen_mode`) is never flipped by a press and hold. The search also looks in the values text. A link, **How to read this
  list**, opens a short explanation of the three tables, a row and the kinds of value. Connected Devices > Hidden Settings shows the same text.
  The text is in `assets/hsinfo.js`, made by `tools/hsinfo/build.py`.
- **Morphe Patcher: bundles are read again.** Every bundle (the official Morphe Patches, Gboard and the others) showed "Could not be read" with
  `KotlinReflectionInternalError: Unresolved class: class java.lang.String`. The patcher reads the patches of a bundle with Kotlin
  reflection, which needs Kotlin's built-in metadata (`kotlin/*.kotlin_builtins`) and its service files at run time. The dex conversion
  had dropped them, and a computer's Java finds them on the class path, so only a phone failed. They are now part of the engine and of the
  APK (together with ARSCLib's framework files for resource patches), and `build.sh` refuses to build an engine without them.
  The same bundle that failed now lists 166 patches in the engine.

## v7.10.0-Pro (versionCode 820)

- **Permissions sheet**: **Allow Restricted Settings** now runs `appops set com.bloatware.bingblop ACCESS_RESTRICTED_SETTINGS allow` through the
  working mode (counted as allowed when it passes) and sits above Usage access, which needs it. Usage access allowed by hand also gets
  `pm grant ... PACKAGE_USAGE_STATS` in the background. **Allow all** grants everything that has a command through the working mode and
  starts the app again at once; closing the first-launch sheet after something was allowed starts the app again too.
- **App Stores**: descriptions that come as HTML are shown as readable text (paragraphs, lists, no tags). The UAD-NG chip on a row of the
  Apps list reads just the level (ADVANCED, RECOMMENDED ...).
- **A new tab: Morphe Patcher.** Patch apps with Morphe on the phone: the engine is the Morphe project's own morphe-patcher, built into the
  app, running in a process of its own (the big heap a patch needs stays out of the app), with a notification and Cancel. It follows
  Morphe Manager (MorpheApp, GPL-3.0) and Helper for Morphe (rushiranpise, GPL-3.0); the credits are in the tab, About, the Help Guide, the
  README and NOTICE.
  - **Sources**: the official Morphe Patches bundle and any more you add (a GitHub or GitLab address, a patches-bundle.json, a .mpp file),
    with update checks, pre-releases, rename, turn off, delete.
  - **Apps** and **Community**: the apps your sources patch, with **Installed** in a green glow for the ones on this phone, a Categories
    drop-down, "find patches for my installed apps", and the community patch finder of morphe-patches.software with the same lists.
  - **Morphe Helper**: finds and downloads the exact APK version a patch needs from ten sources (APKMirror, Uptodown, APKPure, APKCombo,
    Aptoide, Evozi and more), with Fast mode and an optional VirusTotal scan with your API key (its rate limits are kept).
  - **Simple** patching picks the patches for what is installed; **Advanced** lets you choose, with the universal patches and the ones that
    fit the app, and their options. **Patch**, with **Install when finished** (on, silent through the working mode) and **Delete the APK
    after installing** (off). A live log shows the progress the way Morphe Manager does, and a failed run shows its details.
  - **Patched APKs** lists every APK you patched, with its log: install, share, export, delete.
- **A new tab: SD Maid SE.** SystemCleaner, AppCleaner, CorpseFinder and Deduplicator, ported to Java from SD Maid SE by darken (d4rken-org,
  GPL-3.0; the buttons in the tab open it on GitHub and Google Play).
  - Each tool: Scan, a live status (step, bar, counts, path) that stays at the top, Cancel, **Details** to review the results and untick
    what to keep, **Delete**, and a **1-tap scan and delete** checkbox that is off by default. At most two tools work at the same time; the
    others say "In queue".
  - An **exclusion manager** (apps, paths, segments, per tool, with the stock defaults), **Exclude** with Undo from the results,
    **History** of everything deleted (paths kept for 7 days, reports for 30), and the settings of SD Maid for every tool.
  - **AppCleaner** clears caches through the working mode and, with an accessibility service you enable and consent to, one app after the
    other for the caches nothing else can reach. The folders the tools read follow the working mode (ADB, Shizuku and Root read
    Android/data and, with Root, private app data).
- **A new tab: System UI Tuner.** Demo Mode (the status bar in a fixed state, for screenshots), Battery, Clock and Demo mode Quick Settings
  tiles, and a set of System UI tools: the shade and the bar flags, a notification lab, system actions, a battery simulator, navigation
  mode, density, size and window options, and read-only reports. Demo Mode and the tiles follow Tweaker by Zachary Wander (MIT); the settings
  that Tweaker writes (Global, Secure, System) are not repeated, the Hidden Settings tab does that.
- **A new tab: Connected Devices.** Manage another Android device from this phone, a Wear OS watch first (a tablet or another
  phone works too), through the adb this app already carries. It sits after Task Manager (move it in Settings, Feature List).
  - **Add device**: pair with the code (Wi-Fi), connect to an address and port, scan the network for devices that offer
    wireless debugging, or link a Wear OS watch over Bluetooth (through this phone's own ADB and the Wear OS app's
    adb-hub). Recent addresses are kept. A device that has not allowed this phone yet, or is offline, says what to do.
  - **Apps**: the main list's look, simpler: filters (3rd Party, System, Enabled, Disabled, Uninstalled, with counts and
    Clear filters), search, Enable / Disable / Uninstall / Reinstall on one app or a selection, Open, Force stop, Clear
    data, Pull APK, Details. Names and icons come from this phone when it has the same app.
  - **Send**: install APK, APKS, APKM and XAPK files on the device, or an app this phone already has (with its splits); the
    splits that fit the device are chosen (its ABIs, density and language), XAPK game data is copied, there is a progress
    bar with Stop and a result for every package. Bluetooth sharing is there too, for devices that accept files that way.
  - **Console**: a small shell for the device, or adb itself (a line that starts with "adb "), with history, command chips,
    Copy, Share and Stop. Commands that would stop this app's own adb are not sent.
  - **Logcat**: the device's log, drawn like the Logcat Viewer, with level, filter, Play, Clear, Copy and Share.
  - **Files**: browse the device, view a text file, pull a file or folder to this phone (Download/ADB App Manager/Devices),
    send a file here, rename, delete, new folder.
  - **Hidden Settings**: the device's global, secure and system settings: search, edit, add, delete.
  - **Display**: a density drop-down from 120 to 640 in steps of 5, minus and plus, your own number, Reset; a screen size box.
    A change comes with Keep / Put it back and goes back on its own after 15 seconds, so a screen that cannot be read is not stuck.
- **Three APKs for every release.** arm64-v8a (64-bit phones, the same as before), armeabi-v7a (32-bit phones) and a universal
  one that holds both. The self-update picks the one that fits the phone. The 32-bit build carries a 32-bit adb and its libraries
  (see native/armeabi-v7a/README.md); it has not been tried on every 32-bit phone.
- **A Help Guide** (About tab, next to GitHub): a complete guide for someone who has never used ADB, with a table of contents, a
  search box, a topic for every tab and setting, step-by-step recipes, safety advice, troubleshooting, a glossary and an FAQ. It
  is English only and opens without a network. The search lists the topics that hold your words, with a snippet, best first;
  the Working Modes sheet links straight to the topic that explains the modes.
- **The header** has no icon any more and starts with the title at the left edge, "Full ADB/Root System Manager", in the logo's
  two lines and colours; the settings gear and the working-mode badge stay where they were (the badge wraps on a narrow phone).
- **The Read-Only banner can be put away** with the X in its top right corner. It is not remembered: it comes back the next time
  the app starts in read-only mode.
- **Small refinements across the app**: thin scrollbars, visible keyboard / switch focus, figures that keep their width, a soft
  edge on cards, a grab handle on bottom sheets and a short fade when a tab opens. Nothing was moved, hidden or made smaller.
- **The app menu has ten tabs.** Activities now have a tab of their own (Launch, Enable / Disable, the exported filter); Components
  keeps the receivers, services (with Stop) and providers. Four tabs are new, at the end, and read-only (they work in every mode):
  **Features** (the uses-feature list, each marked required or optional and "on this phone" or not, with a filter for the ones this
  phone lacks), **Configurations** (touch screen, keyboard, navigation, OpenGL ES, screen sizes, minimum / target / compile Android
  version, hardware acceleration, this phone's CPU types), **Signatures** (the v1, v2, v3 and v3.1 schemes of the APK, and for every
  signer the subject, issuer, serial number, dates, algorithm, key and the MD5, SHA-1 and SHA-256 fingerprints, with earlier
  certificates after a key change; tap a fingerprint to copy it) and **Libraries** (the libraries the manifest asks for, the shared
  libraries linked in, and the native .so files of the APK by CPU type). The "Copy version" chip is gone.
- **Trim Caches in All Applications**, the last card of Settings: runs `pm trim-caches 128G` through the working mode (the same as
  `adb shell pm trim-caches 128G`), asks first, runs without freezing the app and reports how much space was freed. An explanation
  under the button says what is trimmed (only caches) and what is not (apps, their data, files). It needs a working mode.
- **Uninstalling a system app on a connected device** works the way it does on this phone: after `pm uninstall --user 0` is refused
  ("only root can delete system app for a particular user"), a small helper is sent to the device, run there with app_process to
  call the package manager directly, and removed again. The helper (assets/uninstall_runner.jar, about 6 KB) is built with the app.
- **Asking before the three buttons that cannot be undone**: Uninstall, Clear Data and Rem Updates in the app menu now ask first,
  in words that say what is lost. The batch Freeze question says Freeze (it said Disable).
- **Permissions**: a new row, "Install unknown apps", in the permissions sheet (granted through a working mode, or Android's own
  screen for this app).
- **Root counts as ready only when it was granted.** With Root chosen, the app asks su for `id` and needs uid=0; an su file that the
  root manager refused now shows DENIED and "not ready" (it showed ready). Nothing asks for root unless Root is the chosen mode.
- **A small "?" on the first card of most tabs** opens the Help Guide at that tab's topic.
- **The header**: the title is as large as the room next to the buttons allows, flush left on two lines, with two small lines of
  capitals under it ("SAMSUNG SM-S948U1" and "ANDROID 17 • ONE UI 9.0", the One UI version for Samsung phones). The settings gear
  is a little bigger and the same height as the working-mode badge.
- **The color pickers** (Settings, Granular Color Pickers) open a popup: a grid of colors, or Custom (hue, saturation, brightness),
  and a hex box with the # already in front of it that takes capitals only (type 00DFFF; a pasted #00dfff works too). The box is
  in both views and they follow each other; Apply needs six characters.
- **Application Manager tab**: the Export button is gone (Share CSV stays) and the button row ends with an Action Button menu, the
  same setting as in Settings (they follow each other). The Versions pill and the versions in the rows are gone. A row shows the
  UAD-NG level of its package when the list has it; tapping it opens the same description box as the app menu's chip.
- **Fixes**: the Coding Agents status now also reports the keys of Perplexity, Grok, Muse and Deepseek; the manifest declares
  REQUEST_INSTALL_PACKAGES, which an install without a working mode (and the app's own update without one) needs.

## v7.9.23-Pro (versionCode 813)

- **The lock on everything that cannot be changed right now.** Without a working mode (ADB, Wireless Debugging, Shizuku or
  Root), the buttons that would change something show the lock and look locked: the permission toggles, the App Ops modes
  (the lock is on the mode that is set), and Enable / Disable, Stop and Launch (of an unexported activity) for the
  activities, services, receivers and providers, in the app's menu and in the Activity Launcher and Permission Manager
  sheets. Tapping one still explains what is needed. With a working mode only the install-time permissions stay locked.
- **The UAD-NG classification in an app's menu.** If the UAD-NG project lists the app, a chip under Share shows its level
  (Recommended, Advanced, Expert or Unsafe). Tap it for a prompt with the project's full description, what the level
  means, the category, and what the package needs or is needed by, with a link to the UAD-NG wiki. Apps the project
  does not list show no chip. It needs the UAD-NG list to be downloaded once (Debloater tab).

## v7.9.22-Pro (versionCode 812)

- **Frozen and Uninstalled are kept apart.** An uninstalled app counts only as Uninstalled: it is no longer in the Frozen
  box, the Frozen pill or the Frozen filter. When it is installed again and is still disabled, it counts as Frozen
  again. With Frozen and Uninstalled both on, you see the uninstalled apps that were frozen.
- **File Manager search has filters that combine.** Under the results there are pills for the kind of file (Images,
  Video, Audio, Documents, APKs, Archives, Folders - any of the ones on), size (Over 10 MB or Over 100 MB) and age (Last
  7 days or Last 30 days), one of each at most; they all have to fit. With two or more on, a Clear filters button
  turns them off, and the count says how many are shown.
- **Remember my filters** (Settings, Lists): off by default (the Application Manager still opens on Total Installed);
  when on, the filters of the Application Manager, the Debloater and Saved Applications come back after the app was
  closed.
- **content: search works in added storage.** Looking for words inside text files now also reads files on an SD card,
  USB drive or another app's folder (text files up to 4 MB, as on the phone's own storage). archive: is still skipped there.
- **The lock is back** on the button of a permission that cannot be toggled (an install-time permission), in the app's
  Permissions list and in the Permission Manager of the Action Button, so it reads as locked at a glance.
- **"3rd Party" can be translated**: the translation tool now sees labels that start with a number.

## v7.9.21-Pro (versionCode 811)

- **Split boxes at the top of the Application Manager tab.** Enabled | Frozen and 3rd Party | System are now two boxes of
  two buttons each (each pair is one filter: one on, or none), next to Total installed, Running and Bloatware
  (Uninstalled). The Enabled button has its own count, and the boxes combine with each other and with the pills.
- **A "Clear filters" button** appears under the Sort row whenever more than one filter is on, next to the line that
  names what is combined. One tap turns them all off. The Debloater and Saved Applications filter rows have the same button,
  and the new labels are translated.

## v7.9.20-Pro (versionCode 810)

- **An Enabled filter, and filters that combine.** A new Enabled pill in the Application Manager tab (installed apps that
  are not disabled; a suspended app is still enabled), and the pills and the big boxes at the top can now be used
  together: tap more than one and the list shows the apps that match all of them, such as Running + 3rd Party for your
  running user apps or Enabled + System for your enabled system apps. A tap on a filter that is on turns it off, All
  Apps (or the Total installed box) turns them all off, a line under the buttons names what is combined, and 3rd
  Party / System, and Enabled / Frozen, are each one filter with two values: one on, or none. The same combining
  pills are in the other lists too: the Debloater has an "Also show only" row (Updated 7d, Running, 3rd Party, System,
  Suspended, Patched) that works together with its own Removal, vendor and state rows, and Saved Applications has the
  full set of pills, applied to the apps inside each list (a list shows how many of its apps match, lists with no match
  are hidden, and Recall takes only the matching apps).
- **A small gap** between the checkbox and the ... button on each Apps list row.

## v7.9.19-Pro (versionCode 809)

- **Presets and saved commands can be exported and imported.** Batch Ops has Export presets and Import..., Command has
  Export saved commands and Import...: an export is a small .json file you can share or keep, and Import... takes
  such a file from Android's file chooser (what is already here is not added twice, a different item with the same name
  comes in as "... (imported)", anything that is not valid is skipped).
- **Rename** for saved presets and saved commands (the pencil next to the name; the built-in presets can be duplicated
  and then renamed).
- **An on-device test checklist** for the v7.9.14 to v7.9.19 features is in docs/DEVICE-TEST-CHECKLIST.md.

## v7.9.18-Pro (versionCode 808)

- **Added storage (SD card, USB drive, another app's folder) does more.** Copy and Move now work there: pick them from a
  file's menu or select several items, then open the target folder and tap Paste - in, out of, or between added
  storages, with the usual "name already taken" choices and a Stop button. Multi-select is on in added storage too.
  Pictures, PDFs, fonts and archives open in their viewers, and Open with, Share and Extract work: the file is copied
  once to the app's cache first (up to 300 MB) and reused until the original changes. Search works there as well, by name,
  type, size and date (content: and archive: look inside files, so they are skipped in added storage).
- **Icon packs now dress apps they do not cover.** When the pack ships a background, mask or overlay for apps it has no
  icon for, such an app's own icon is shown on that background, scaled the way the pack asks, so the list looks uniform.
  Settings, Icon pack also has a "Clear icon cache" button.
- **Action Button setting.** A new "Action Button" card in Settings, right after Icon pack, chooses what the extra
  button on each Apps list row does: App Settings (as before), Force Stop, App Launcher, Enable / Disable, Install /
  Uninstall, Unsuspend / Suspend, Permission Manager, Activity Launcher or None (no button). It is always an icon, and the three
  Enable / Disable, Install / Uninstall and Unsuspend / Suspend choices change their icon and action to match the
  app's current state. Permission Manager and Activity Launcher open a sheet like the app menu, listing every
  permission (with its label, description, group, protection level, whether it is granted and whether you can change it)
  or every activity (exported or not, enabled, the permission it needs, with Launch and Enable / Disable), each with a
  search box and filters. The Permission Manager can also grant or revoke, in one go, every changeable permission shown
  by the current filter and search (it asks first and lists any that failed). The button also appears on the
  Debloater rows, and holding it opens a sheet with every action for that app, whichever one is chosen in Settings
  (Settings, Action Button, Hold menu chooses which actions that sheet lists and in what order). The Permission Manager
  has an App Ops chip too (every app op with Allow / FG / Ignore / Deny / Reset, and a permission shows its app op), and
  in the Activity Launcher you can tick several activities and Enable or Disable them at once.
- **More in the batch menu.** The batch menu is taller and has a fourth row: **Batch Ops** lists every app op, lets you
  tick the ones to change and pick a value for each (Allow, FG, Ignore, Deny or Reset), and sets them all on every
  selected app; **Show Apps** lists the selected apps and lets you remove any of them from the selection; **Command**
  runs a shell command of your own on every selected app, one after another, replacing `$package` with each app's real
  package name (the instructions, a live preview for the first app and a few examples are on the sheet; it runs
  exactly as typed and has no undo). Batch Ops has presets (Privacy lockdown, No location, Stop background activity,
  Silence notifications and an Undo for the lockdown) and lets you save your own sets of app ops by name. Command can
  save commands under a name, run on the apps of a saved list instead of the selection (the ones not on the phone are
  skipped and counted), and every batch result dialog has a Share CSV button (app, package, OK or failed, the
  command's output, the time and the command that was run). Presets and saved commands can be duplicated (the copy
  is named "... (copy)"), Command has a Dry run switch that lists what would run for each app without running anything
  (with Run it for real and Share CSV), and the batch results dialog has Run again, and Run again on the ones that
  failed. The batch menu now has a ✕ at the top right, like the app menu's, that minimizes it.
- **Press and hold guide.** A new card at the end of Settings lists everything that can be pressed and held (an app row,
  an app's icon, the Action Button, the selection checkmark, file rows, found APKs, Hidden Settings and overlay rows) and
  what each one does.
- **A tidier start and buttons.** The app now always opens on the Application Manager tab with "Total Installed"
  selected, even if a filter was left on last time. In the Apps list the checkbox sits to the right of the "⋯" menu,
  and its top-bar buttons share the File Manager's new outlined look (the File Manager's Up button is gone, the ".."
  row does that) with a haptic tap on every button.
- **Press and hold an app row to open its menu.** Holding anywhere on a row (the icon keeps saving itself, the buttons and
  checkbox are left alone) opens that app's single-app menu. App rows no longer start a text selection.
- **Fewer false "failed" results.** An app launch or app action is only reported as failed when the output says so at the
  start of a line ("Error: ...", "Error type 3", an exception, "No activities found"), so an app whose name or package
  contains "error" or "failed" no longer shows as a failure. The file manager's own commands now confirm success with a
  marker on a line of its own instead of searching the output for "OK".

## v7.9.17-Pro (versionCode 807)

- **Icon pack setting.** A new "Icon pack" card in Settings, right under Font, lists the icon packs installed on the
  phone (the ADW, Nova, Apex and Go theme convention). Pick one and the list's icons are drawn from it; an app the
  pack does not cover keeps its own icon, "Default icons" goes back, and each pack's icons are cached separately
  (refreshed when the pack or the app updates). Press and hold still saves the icon shown.

## v7.9.16-Pro (versionCode 806)

- **App icons in the list.** Every row now shows the app's own icon to the left of its name, package and badges, and the
  letter box is gone, which gives the text more room. Icons are drawn off the page's thread and cached on the phone
  (re-drawn only when an app updates), so later launches show them straight away. Press and hold an icon to save it as a
  PNG to Download/ADB App Manager/Icons.

## v7.9.15-Pro (versionCode 805)

- **Expressive Animations.** A new "Motion" card in Settings, right below the theme cards, holds one switch (on by
  default): Material 3 Expressive's springier, overshooting motion for the app's own transitions - modals, sheets,
  buttons, switches and the like - instead of a plain fade or slide. The few width-based progress fills (backup,
  storage scans, the self-update check) are left at a plain ease, where an overshoot would visibly pass 100% before
  settling back, and the whole thing steps aside automatically when the phone's own reduced-motion setting is on,
  whatever this switch says.

## v7.9.14-Pro (versionCode 804)

- **Terminal: sync the Termux shell with your real Termux.** Two new pieces under Terminal settings' Shell
  section (and in the Termux setup checklist): a "Match my Termux environment" switch, on by default, that
  sources ~/.bashrc when a Termux shell starts here too - so aliases, functions and PATH additions you keep
  there work the same way in this app, not just the PATH/profile the existing "Use my Termux login profile"
  switch already read; and a "Sync with Termux" button that turns that switch on if it was off, restarts any
  already-running Termux session so the change applies right away, and opens Termux to run
  termux-setup-storage - so granting it access to shared/phone storage is one tap instead of a command typed
  by hand.

## v7.9.13-Pro (versionCode 803)

- **Saved Applications: the "Saved" tag no longer crowds the title.** Each saved list's name and app count now have
  the card's full header to themselves; the "Saved" badge moved down to sit right above its own action-button row
  (Recall, Quick list, Edit, Copy, Delete), the same "identity on its own row, controls clustered together"
  layout the Apps list just got, adapted to how this card is actually built (no checkbox or icon to move).

## v7.9.12-Pro (versionCode 802)

- **Apps list: a cleaner row layout.** The app's name, package and badges now start flush against the left edge
  instead of sharing space with the checkbox and icon; the checkbox, the letter icon, the gear and ⋯ buttons all
  sit together on the right instead. No change to what anything does - Debloater/UAD-NG rows are unaffected.

## v7.9.11-Pro (versionCode 801)

- **Coding Agents: "Always allow" for file changes, not just commands.** A proposed `<run>` command already had
  an "Always allow in this chat" option next to Run and Skip; a proposed file write or edit only ever offered
  Apply and Skip, so every single change needed its own tap, however many steps a task took. The same "Always
  allow" button is now on that card too - once pressed, the rest of the chat's file changes go through with no
  further asking (reading outside the working folder and running commands are unaffected and still need their
  own say-so, unless those were separately allowed too).

## v7.9.10-Pro (versionCode 800)

- **Terminal: no more "Shell Command Result" popup after every command.** It hardcoded success regardless of
  what the command actually did, and duplicated output already printed right there in the terminal pane one
  line above it - per the command-result audit's own nuisance finding, the single most disruptive, lowest-value
  prompt in the app. Removed outright; a command run from another tab still gets a toast when it finishes.

## v7.9.9-Pro (versionCode 799)

- **Dex optimization no longer freezes the page.** Recompiling an app (or several) ran a `pm compile` call
  straight from the page's own thread, exactly the freeze batch actions had before v7.9.6 - now it runs off the
  page's thread with a live progress bar and a Stop button, for one app or many alike.
- **Fewer redundant "it worked" popups.** A single-app ⋯ menu action and a single app's Dex optimization used to
  show a toast *and* a full result dialog for a routine, successful action - now, like every other action in the
  app (toggling a component, a file-manager operation, launching an activity...), the dialog only shows up when
  something needs explaining; a failure still gets it.
- **Applying a Profile** now runs the same way batch actions do - off the page's thread with the progress bar
  above, instead of freezing the page for however long the whole plan took - and the Profiles sheet stays open
  behind the result dialog afterwards instead of closing first, matching every other flow in the app.
- Single-app menu: the Copy package / Copy version / Copy name row no longer wraps to a second line on a narrow
  phone; Share sits further from the ✕ so a slightly careless tap doesn't land on the wrong one.
- Terminal: the Ctrl+C key now just reads "CTRL" (still stops whatever is running).
- Credits: added a mention of [RohitKushvaha01/TaskManager](https://github.com/RohitKushvaha01/TaskManager),
  which the Task Manager tab is modeled on.

## v7.9.8-Pro (versionCode 798)

- **File Manager: Add storage.** Bring in an SD card, a USB drive, or a folder another app is willing to share,
  through Android's own document-tree picker - no working mode, root or All-files access needed, since the OS
  grants this app that one tree directly. Added roots show as quick-switch buttons above the file list and are
  remembered across launches; removing one only drops this app's access, nothing on the storage itself. Inside
  one: full browsing (with its own Up, since this kind of storage has no parent folder to look up - an added
  root's own breadcrumb trail is kept instead), new file/new folder, rename, delete, and viewing/editing a text
  file in place all work the same as internal storage. Copy, move, compress, Open with, Share and multi-select
  are not there yet for this kind of storage and are hidden or refused with a clear message rather than silently
  failing - they're next.

## v7.9.7-Pro (versionCode 797)

- **About tab: Device Specs**, under Handy to know - hardware (model, chipset, CPU cores and clock range, RAM,
  Vulkan/OpenGL ES, screen, storage), software (Android version, security patch, build ID), system (uptime,
  locale, timezone), battery, network, camera and sensors, all in one place. Refreshed automatically when the
  About tab opens, plus its own Refresh button. Every reading is a public, context-free one (BatteryManager,
  world-readable /proc and /sys nodes, CameraManager/SensorManager characteristics) - no working mode needed.

## v7.9.6-Pro (versionCode 796)

- **Batch actions no longer freeze the page while they run.** The live progress added in v7.9.5 still blocked the
  page for the whole loop - each app's action is a synchronous call into the native side, and that call was still
  being made from the page's own thread, which is also the WebView's. The batch now runs through a dedicated
  native batch runner instead, off the page's thread entirely, so the page can actually repaint and respond while
  it works; the Stop button and the live per-app progress behave exactly as before.
- **Apps list: version numbers are off by default.** A less cluttered list row - the "Versions" filter pill above
  the list still turns them back on, and the single-app menu and command results always show the version either
  way.
- **Logcat Viewer starts playing on its own.** Opening the tab used to need a tap on Play; it now scrolls to the
  top and starts live-tailing immediately.
- **Terminal: a larger input box**, and a Shift key on the extra-keys row that swaps in a second row of symbols
  (backtick, dollar, semicolon, colon, quotes, backslash, braces, parentheses, asterisk, ampersand, hash) a
  phone keyboard usually buries behind its own symbols layer.
- **Terminal: a Cheat Sheet button**, with a different set of commands depending on which of the three shells is
  picked - common bash for Termux, file/text basics for this app's own sandbox shell, and the existing ADB
  reference for the privileged working mode.
- **Single-app menu: Share moved to the top-right corner** of the sheet, next to ✕ - the Copy row underneath now
  fits on one line, and everything below it shifts up.

## v7.9.5-Pro (versionCode 795)

- **Live progress for batch actions.** Running a command (Freeze, Force Stop, Uninstall, ...) across several
  selected apps used to just show a spinner with no detail until it was all done. The batch panel now shows which
  app is currently being processed (name and package), a fill bar for how many are done, and a Stop button that
  ends the run after whichever app is already in flight finishes - nothing past that point is touched. Closing the
  sheet (✕ or tapping outside) while a run is active does the same thing, instead of silently hiding a job that
  would otherwise keep running unseen.
- **More command-result prompts now reflect what actually happened**, instead of assuming success or scanning
  output for a fixed set of failure words: Task Manager's "Kill" and GPU renderer switch, stopping a component's
  service, granting/revoking a permission, and changing an app op all now read the real result (exit code, or the
  native call's own outcome) the same way the rest of the app's actions already did. Optimize (Dex compile) also
  picks up a keyword gap ("failed") the old scan was missing.
- **Apps tab: the Sort/Export/Share CSV/Profiles/Backups row** no longer wraps "Backups" onto its own near-empty
  line on a phone-width screen - the row scrolls sideways like the filter pills above it instead, with everything
  below it shifted up to fill the gap.

## v7.9.4-Pro (versionCode 794)

- **Fixed the systemless-uninstall fallback actually reporting failure after it succeeded.** It passed a plain
  Java dynamic proxy as the callback Android calls back into with the real result; a proxy has no native Binder
  behind it, so that callback could never be delivered - the app removal went through, but this app's own process
  timed out waiting for a result it was never going to receive, and showed the uninstall as failed. The callback
  is now backed by a real `android.os.Binder`, which the system can actually call back into.

## v7.9.3-Pro (versionCode 793)

- **"Keep selection after running"** in the Apps tab's batch panel. Running a batch command (Freeze, Suspend,
  Uninstall, ...) used to always clear the selection once it finished, so running a second command on the same
  apps meant picking every one of them again. Ticking this new switch leaves the selection as it is - the panel
  sits behind the result dialog the same way the single-app sheet does - so another command can run on the same
  apps right away; it's remembered between launches, and the existing "✕ Clear" button and long-press on the
  floating checkmark still clear the selection by hand at any time.

## v7.9.2-Pro (versionCode 792)

- **Systemless uninstall for system apps.** When `pm uninstall --user 0` is refused with Android's own "only root
  can delete system app for a particular user" line, the app now automatically retries through a direct Binder
  call to `IPackageManager.deletePackageAsUser` - run as a standalone `app_process` under whichever privileged
  shell (ADB or Shizuku) is already active - the same technique App Manager and Canta use for the same refusal.
  This removes the app for the current user; it stays in the system partition, the same result a normal
  uninstall gives for a non-system app, and only runs when the active mode isn't already Root (which removes it
  directly, no fallback needed).
- **Removed the "Uninstall (System)" button.** It handed the removal to Android's own uninstall dialog, which on
  many phones did nothing useful for a preloaded system app - the automatic fallback above actually works instead,
  so the button (and the single-app sheet's own line it used to take up) is gone.
- **Several command results that said "Success" had actually failed.** Freeze/Enable, Suspend/Unsuspend, Force
  Stop, Clear Data, Uninstall and Remove Updates (single app, batch, Debloater, Undo and Profiles alike) used to
  decide success by scanning the command's own output for words like "error" or "failed" - an empty answer, or an
  OEM shell's own reworded refusal, read as success either way. They now read the shell's real exit status instead,
  which no amount of rewording or silence can hide.
- **Three more permissions on first launch, once a working mode is active**: Read/Write External Storage, Write
  Secure Settings and Access Restricted Settings (AppOps) - granted straight through the privileged shell, since
  none of them has an Android settings screen of its own. They join the existing All files access / Usage access /
  Display over other apps sheet, and (like those three) are also under About → Permissions.
- **The single-app sheet stays open behind its result dialog.** Running an action from the app menu (⋯) used to
  close the sheet outright; it now stays open underneath the result dialog, the same way the Installer and Signer
  sheets already behave, and its Freeze/Suspend/Uninstall buttons catch up once the result is dismissed.

## v7.9.1-Pro (versionCode 791)

- **App Stores: a successful install now offers Launch Application and Application Settings**, the same result sheet
  the APK Installer tab already shows - for every catalog (ShizuStore, GitHub, F-Droid, Orion, and a typed owner/repo).
  Before, a store install only ever showed a plain "Installed" toast with no way to open the app or its settings from
  there. The real, installed package name (read from the downloaded APK itself, not the catalog's own tracking key -
  a GitHub repository has no package name until its APK is read) is what the two buttons act on, so they are correct
  even when the catalog's listing didn't know the package name ahead of time.

## v7.9-Pro (versionCode 790)

- **The tab is now "Command-Line Interface"** (still Terminal first, ADB Console next to it, same big switch and the same
  two panes as v7.8 - only the tab's own name changed).
- **Three new API-key agents**: **Grok (SpaceX)**, **Muse (Meta)** and **Deepseek**, each with its own address, its own key
  page, and its own masked key hint. **Kilo Code** is listed too, in Free Open-Source - it is an editor extension with no
  confirmed phone-terminal CLI, so it is explained (website and source) rather than wired up to chat here, the same way
  DroidMind and Leon.ai already are. OpenCode was already here and stays as it was.
- **Effort**, next to Model: **Low, Balanced or High**, for how hard an agent is asked to think. Balanced is the first
  choice, the same way the Model list's own first (recommended) entry already balances speed and cost. It actually changes
  the request for the agents whose own API has that knob - Claude's extended-thinking budget, ChatGPT and Grok's
  reasoning_effort, Gemini's thinking budget - and is simply kept, with no effect, for the others (Deepseek, Muse, the
  official sign-in tools in Termux). Settings gets a matching Effort column next to each agent's default model.
- **The AI agents can help in the ADB Console too**, not just the Terminal: a new **$ / AI** button next to its input
  switches it to ask the Coding Agent picked in the Terminal (the same agent, model and effort - no separate picker here)
  for ADB/shell syntax and code help. The agent answers inline in the console's own output, and may propose **one command**
  at a time, shown with **Run** / **Skip** (or run straight away when "Ask before running commands" is off) through the
  console's own execution path - never a parallel shell of its own. Its own small memory is separate from the Terminal's.
- **Translations**: the new agents, the Effort words and the console's AI help are still English-only in the other 12
  languages for now (to translate later); everything already translated is unaffected by the tab rename.

## v7.8-Pro (versionCode 780)

- **The ADB Console tab is now "Terminal / ADB Console"**, with a big switch at the top between the two. **Terminal** comes
  first and the tab remembers which one you used last; **ADB Console** is the console you know (Rish mode, cheat sheet,
  saved commands), unchanged.
- **Terminal: a Termux-style command line.** A black screen with colours, Termux's row of extra keys (ESC, TAB, CTRL-C,
  arrows, HOME, END, | / - ~), command history, TAB completion of file names, progress bars that redraw in place, and
  **three shells**, each with its own screen that stays open while you use other tabs (cd and export carry over):
  - **This app (sandbox)**: always there, no setup.
  - **Working mode**: the shell user through ADB or Shizuku, or root with Root.
  - **Termux**: your own Termux, with bash and everything installed with pkg. A **Termux setup** checklist walks you through
    the one-time steps (install from F-Droid or GitHub, `allow-external-apps=true`, the "Run commands in Termux environment"
    permission, a connection test). Full-screen programs (nano, vim, top, a Python prompt) open in a real Termux window.
    **Once Termux is set up, the Terminal opens on it by default** (bash, more compatible with the coding agents' own CLI
    tools than the sandbox or Working mode); "Automatic" in Settings explains the rule.
- **Coding Agents.** A drop-down with **None** first, then **API Key Required** (Gemini, Claude, ChatGPT, Cursor, Copilot,
  Perplexity) and **Free Open-Source** (DroidMind, OpenCode, Leon.ai, Jan.ai, AnythingLLM, and Ollama on the phone itself),
  plus a **Model**
  drop-down. Choosing an agent asks for what it needs and **tests it first**: a refused key is explained in plain words with
  the provider's own message. Chat in the Terminal: the agent answers as it types, and it can **run commands, read files,
  write files and edit parts of files** in the current shell, **each step shown first** with Run / Apply, Skip, or Always
  allow for the rest of the chat. Edits show a line-by-line diff, `/undo` takes a change back, STOP (or CTRL-C) ends it all.
  **Switch agents or models in the middle**: the conversation carries over. Slash commands: /help, /new, /agent, /model,
  /models, /shell, /chat, /undo, /stop, /login, /settings; `!command` runs a command from the chat.
- **API keys stay safe**: sealed with a key that never leaves the phone's secure hardware, sent only to their own provider's
  address (the app adds them; the page never sees them again), and never shown to an agent (keys in output are masked).
- **Subscriptions (website sign-in)** go through the providers' **official command-line tools** in Termux, because Anthropic
  and Google do not allow other apps to use a Claude or Google login: Claude Code, Gemini CLI, Codex (ChatGPT), Copilot CLI
  and Cursor CLI. The Connect sheet installs the tool (into a Debian container in Termux when it has no Android build),
  opens its own sign-in in Termux, and the Terminal then chats through it.
- **Cursor** connects to Cursor's Cloud Agents with an API key (they work in Cursor's cloud, optionally on a Git repository
  you name); **Copilot** runs through GitHub's Copilot CLI, since GitHub retired GitHub Models in July 2026.
- **Perplexity** connects with an API key from its own website (its Sonar models; no official sign-in tool, so it is an
  API key only, with no "Sign in with subscription" tab).
- **Free options**: **Jan.ai** and **Ollama** are found on the phone by themselves when they run there (or enter a computer's
  address); **AnythingLLM** asks for its server's key; **Ollama** can be installed into Termux with a small coding model in
  one go; **OpenCode** is installed in Termux. **DroidMind** and **Leon.ai** are explained (tools for other agents and a
  computer assistant) with their install or website.
- **Terminal settings** (the gear in the Terminal): your keys by their hint only, the agent and model to start with, the
  default shell, whether agents must ask before running commands or changing files, and whether the conversation is kept.
  **Help** explains everything, with a link to every agent's website.
- **MCP button**: a new sheet (next to Help) to add a Model Context Protocol connector - a preset (filesystem, fetch,
  memory, GitHub, and more, each with its own note) or a custom one (name, stdio command or remote URL, extra environment
  or header) - to any of the six CLI-based agents (Claude Code, Gemini CLI, Codex, Copilot CLI, Cursor CLI, OpenCode),
  **two or more agents at once**, so the same server reaches all of them. Written into each agent's own config file in its
  own shape (`.claude.json`, `.gemini/settings.json`, `~/.codex/config.toml`, and so on); a remote server's token is kept
  as an environment variable for the agents that want it that way, never written into the file itself. Already-added
  servers are listed with which agents they went to, and can be forgotten (removed from all of them at once).

- **Safety**: "Always allow in this chat" applies only to the shell and user it was given in (a grant in the sandbox does not let
  commands run as root); a read through a link that leads out of the working folder (Termux's `~/storage`) is asked about;
  files are read for edits between nonce markers with a size check; edit markers are whole lines; a file that is not UTF-8
  text is not edited; edited files keep their permissions; an answer cut off mid-stream is reported as an error; a key with a
  control character is refused and keys are masked in any error that goes back to the page.
- **Translations**: the agent and model drop-downs and the Shell options are translated (agent and model names stay as they
  are); optgroup headings are translated app-wide (the Compress dialog's "One file only" too); the File Manager search help
  showed `size:&gt;10mb` in every language and now shows `size:>10mb`. About 70 of the Terminal's own status lines are still
  in English in the other languages.

## v7.7-Pro (versionCode 770)

- **Task Manager → GPU: a Renderer switch.** A dropdown showing the HWUI backend currently in use (Vulkan, OpenGL, or
  "Default" when nothing has overridden it) - only offering Vulkan when the device actually supports it. Picking a
  different one runs `setprop debug.hwui.renderer skiavk`/`skiagl` and then restarts System UI (`am crash
  com.android.systemui`, which Android relaunches immediately) so the new renderer actually takes effect - a quick
  flicker of the status and navigation bars is expected and not a bug. Needs a working mode; reads and writes the
  property only on request (opening the tab, switching to it, or picking a value), never on the auto-refresh tick, so
  it can't rebuild itself out from under an open selection.

## v7.6-Pro (versionCode 760)

- **Uninstall (System).** A second button next to Uninstall on the app sheet, for a system app that needs root the direct
  way: it hands the removal to Android's own uninstall screen instead of a shell command. That runs with the system's
  own privilege rather than shell's, so it can go through Android's standard no-root soft-removal for a preloaded app
  (shows as **Not installed** in Settings afterward) in cases where the direct Uninstall button's `pm uninstall --user 0`
  hits the hard "only root" wall. No working mode needed to open it; the system's own confirmation dialog decides the
  rest. The failed-uninstall note now points at this button by name.
- **Fixed:** an app that's uninstalled-for-this-user (shows under the Uninstalled filter) was listed by its bare package
  name instead of its real name. It now reads the name off the system-app stub that's still on the device, the same way
  Android itself would, falling back to the package name only when nothing more is left to read.
- **Task Manager: a Information section on every tab**, reusing data the phone already exposes, no new permission on any
  of it:
  - **CPU** — Processor Information (SoC, architecture, ABI, scaling governor, an estimated temperature) and System
    Statistics (process and thread counts, uptime), plus a card per CPU cluster with its core count and min/current/max
    clock speed. Thread counts and process counts need a working mode, same as the Processes list; everything else reads
    straight from `/sys` and needs nothing.
  - **RAM** — Free, Buffers, Cached, Swap free, Swap cached and the kernel's page size, alongside the existing Used /
    Available / Swap figures.
  - **GPU** — Vulkan support and API version, and the OpenGL ES version, from plain `PackageManager` /
    `ActivityManager` queries (no GL context is created, so this never touches the GPU driver).
  - **Battery** — Technology, current power source, charge in mAh, an estimated capacity and an estimated time left
    while discharging, charge cycle count (Android 14+, else "N/A"), and the actual wattage (voltage × current,
    correctly converted from the raw µA/mV the phone reports).
  - **Network** — total bytes received and sent since boot, alongside the existing live ↓/↑ rates.

## v7.5.1-Pro (versionCode 751)

- **A plain-language note on a failed Uninstall / Uninstall (keep data).** Removing a system app for one user has always
  needed actual **root** — plain ADB or Shizuku run without root were never enough, on any Android version or phone
  brand, regardless of what the raw `pm` output says. When that is why it failed, the result now says so plainly and
  points at **Freeze** as a no-root alternative (the app disappears from the launcher and stops running; its file stays
  on the device either way, since `/system` is read-only regardless of which one is used). A device policy or a user
  restriction blocking removal is also explained instead of shown as a raw `DELETE_FAILED_...` line.
- **Fixed:** a failed Freeze / Suspend / Uninstall / Clear Data / Force Stop could show its result as "Success" when the
  raw `pm` answer was `Failure [...]` — the check only looked for the word "failed", not "failure".

## v7.5-Pro (versionCode 750)

A new **Task Manager** tab, after Logcat Viewer: processes, CPU, RAM, GPU, battery and network, live, with a graph for each.

- **Processes.** A sortable list (by CPU or memory) of what is running, each row with its RSS and CPU%; a resolved app shows its real name.
  **✕** force-stops it right away, with no confirmation — the same one-tap `force_stop` already used everywhere else in the app. Needs a
  working mode (ADB / Wireless Debugging / Shizuku / Root): listing processes is a shell `ps`.
- **CPU, RAM and Network work with no working mode at all.** They read `/proc/stat`, `/proc/meminfo` and `/proc/net/dev` directly — those
  are world-readable — so the graphs, the per-core breakdown, and the used/available/swap and ↓/↑ figures all show up even on a phone with
  no ADB, Shizuku or Root connected.
- **Battery needs nothing either.** Percent, temperature, voltage, current, charge state and health come straight from `BatteryManager` and
  the system's own battery broadcast — no shell, no special permission. Temperature and current have a unit switch (°F/°C, mA/µA).
- **GPU is best-effort.** Busy-time and utilization sysfs paths are tried first (Adreno, Mali-style); failing those, a clock-speed ratio is
  shown as an approximation, labelled as such. Needs a working mode; on a phone that exposes none of these paths, it says so plainly instead
  of guessing.
- **Two independent settings.** **Auto-refresh** (off, 1–10 s) is how often a fresh reading is fetched — the expensive part, a shell round
  trip when privileged. **Graph speed** (150–1000 ms) is only how smoothly the already-fetched readings are redrawn from a small in-memory
  buffer — cheap, no extra reading. **Refresh now** asks for one reading right away. All of this, plus the chosen units and process sort,
  is remembered between visits to the tab.
- **For developers:** five small, pure-Java engines with no `android.*` imports (`CpuStats`, `MemStats`, `NetStats`, `ProcStats`, `GpuStats`),
  each with its own suite (`cpustats`, `memstats`, `netstats`, `procstats`, `gpustats`) run on a desktop JVM. The page side has
  `tests/ui/t87.js`.
- **Not in this release:** the tab only reads while it is open — there is no background monitoring, no alerts (high CPU, low battery, …),
  and the graphs start over empty each time the tab is reopened (nothing is kept across visits or an app restart).

## v7.4-Pro (versionCode 740)

Archive formats: 7z, rar and the tar family join zip, with passwords, and a Compress dialog to make new archives. The file manager's
editor, viewer and archive preview now show code in colour.

- **7z, rar and the tar family open, browse and extract like a zip.** The archive browser, **Extract…**, search's `archive:` and Find
  APKs' duplicate check now work on `.7z`, `.rar`, `.tar`, `.tar.gz`, `.tar.bz2`, `.tar.xz`, `.tar.zst`, `.tar.lz4`, and on a bare `.gz`,
  `.bz2`, `.xz`, `.zst` or `.lz4` (shown as the one file inside). A solid 7z or rar, and a compressed tar, say so in the header; extracting
  one unpacks it in a single pass instead of reopening the file per entry. RAR is read only (browse, preview, extract); 7z and the tar
  family can also be edited in place (delete, rename, replace, add, new folder) the same way a zip can.
- **Passwords.** Opening a 7z or rar whose names themselves are encrypted, or reading an entry that is encrypted while its name is not
  (a zip, or a 7z with content-only encryption), asks for a password in a dialog of its own; a wrong one says so and asks again. The
  password is kept only for that open archive, only in memory, and is asked again next time. Zip supports the traditional ZipCrypto and
  WinZip AES (128/192/256); 7z supports its own AES-256 (and encrypts the file names too, not only the data); rar's passwords are for
  reading only, since rar cannot be created here.
- **A Compress dialog.** **Compress…** on a file's sheet, or **Compress** on a selection, makes a new archive: zip, 7z, a tar format, or
  (one file only) a bare gz/bz2/xz/zst/lz4. A name, a packing level where the format has one, where to save, and what to do if that name
  is already there (keep both or replace). **A password is optional** — leave it empty for none. Zip offers AES-256 (default), AES-128 or
  the weaker-but-everywhere ZipCrypto; 7z is always AES-256 (and hides the file names); tar and the single-file formats have no password
  of their own.
- **Code and markup shown in colour.** The file manager's text editor, the plain file viewer, and an archive's text/XML preview now colour
  comments, strings, numbers, keywords and tags by default, for the usual languages (C-family, Java/Kotlin, JavaScript/TypeScript, Python,
  shell, PHP, Ruby, Perl, Go, Rust, Swift, Dart, Lua, R, SQL, batch, Gradle, smali) and markup (XML/HTML with nested `<script>`/`<style>`,
  JSON, YAML, TOML, INI, Markdown, diff); a file it does not recognise, or one over 250,000 characters, is shown plain. **Word wrap is on
  by default** in the editor; a **Colors** switch sits next to **Wrap lines**, and both choices are remembered for next time.
- **For developers:** `ArchiveIo` (7z and the tar family, plain Java, no Android classes) and `RarReader` (rar, read only) are the new
  engines, each with its own suite (`archiveio`, `rarreader`, checked against 7-Zip, py7zr, tar/gzip/bzip2/xz/zstd/lz4, and real RAR4/RAR5
  archives from the `rarfile` and `libarchive` projects' own test suites); `ZipCrypt` (zip's own passwords) and `ZipWriter` (new zips) have
  theirs too (`zipcrypt`, checked against Info-ZIP and pyzipper; `zipwriter`). The page side has `tests/ui/t85.js` (syntax colours and word
  wrap) and `t86.js` (the password and Compress dialogs).
- **Not in this release:** rar cannot be created or edited (no free, well-tested way to write it); multi-volume rar is not supported, nor
  is strong (PKWARE) zip encryption; the Hidden Settings backup/restore and the Task Manager tab are still ahead.
- **Known limits.** A `.gz`'s exact size is known only below 4 GB (bzip2 never gives one; zstd/lz4 only when the file itself carries it).
  Zstd decoding uses a third-party pure-Java decoder; if it cannot run on a given device (an unusual JVM that refuses `sun.misc.Unsafe`),
  `.zst`/`.tar.zst` are reported as unsupported there rather than failing partway through — nothing else is affected. 7z's own key
  stretching, and reading a very large archive's list, can take a noticeable moment. A password is asked for only once reading reaches an
  entry that needs it (not up front for the whole archive), so on a mixed archive a retry after the password re-starts the job; files
  already written by then are handled by the conflict choice (Replace, Skip or Keep both) like any other extraction.

## v7.3-Pro (versionCode 730)

File manager, part two: search, the extract dialog and long jobs that keep going; and a cleanup for the package files the Installer finds.

- **Search in the file manager.** A search bar under the path: type a name, or use the filters. **Where to search** is a drop-down: this folder,
  Internal storage, Downloads, Camera and photos, Pictures, Documents, Music, Movies, an SD card or USB drive, or the whole phone. Two boxes
  next to it: **Include subfolders** (off: only what is directly in the place chosen) and **Search inside archives** (zip, apk, jar, docx and
  the like). The answer is a list that takes the place of the folder until **Back to the folder**: the name, the folder it is in, size and
  date, the line that matched for `content:`, and for an archive entry the archive and the entry. Tap a result to open it (a file gets its
  usual sheet, a folder opens, an archive entry opens the archive and filters it to that name); the arrow button shows it in its folder.
  While it runs a line shows the folder, how many files were looked at and how many were found, and the button says **Stop**. Search help
  folds out under the bar. **Show hidden files** applies to the search too.
  - words in a name (all must match; `*` and `?` are wildcards; `-word` leaves a name out; `"two words"` in quotes);
  - `ext:jpg,png` or `.pdf`; `type:image|video|audio|text|doc|archive|apk|font|folder|file`;
  - `size:>10mb`, `size:<1k`, `size:1k..5m` (b, k, m, g, t); `date:today|yesterday|week|month|year|7d|2w|3m`, `date:2025-03-01`,
    `date:2025-03-01..2025-03-31`, `date:<2024-01-01`, `date:>=2025-06-01` (the phone's own time zone);
  - `content:word` looks inside text files up to 4 MB (every word given must be in the file; the first matching line is shown);
  - `archive:name` looks at the names of the entries inside archives (the whole path in the archive, wildcards allowed).
  What could not be understood is said, and the rest of the query still works. The walk stops at 1,000 results, 400,000 files or 90 seconds,
  never follows a link to a folder, and skips pipes and devices; the result line says when a limit stopped it. It reads what the app can read
  (All-files access); folders only a working mode reaches are not searched.
- **An extract dialog.** **Extract…** on a zip-format file in the file manager (and **Extract** inside the archive browser, for the whole
  archive, a folder or a file) opens a dialog: **Extract into** this folder, a new folder named after the archive (`data.tar.gz` gives
  `data`), or another folder you type; **If a file is already there**: Replace, Skip or Keep both (`name (1).ext`; the old extraction
  simply overwrote); and, for a whole archive on storage, **Delete the archive afterwards** (asked again before it starts; the archive
  goes only when every file was written, with nothing skipped or left alone). The choices are remembered. The result says how many files,
  how many were left as they were, and that the archive was deleted.
- **Long jobs keep going and say how they are doing.** A copy, move, delete or extraction shows **percent, speed and time left** (the speed
  is smoothed, so the time does not jump about), on the page and in a notification of its own; the job runs in a foreground service, so it
  continues when you leave the app, and the notification has **Cancel**. If nothing has moved for 20 seconds (a slow cloud file, a drive
  that stopped answering) the line and the notification say so and that Cancel stops it. Cancel also works from the page: copy, move,
  delete and extraction have a Cancel button.
- **Find APKs: duplicates and older versions.** After a search the app reads the package name and version of each `.apk` and compares the
  contents of files of the same size: a second copy is flagged **Duplicate** (and says which file it is the same as; the newest is kept,
  then the one with the shorter path), and a version below another one of the same package is flagged **Older version** (and says which
  file is newer). The package and version are shown on each row.
- **Delete several at once, with one Undo.** **Select** in the Find APKs list shows boxes; **All**, or **Select duplicates and older
  versions** to pick exactly the flagged files; **Delete selected (N)** asks once (count and size), moves every file to the hidden trash
  folder and gives **one Undo bar for the whole batch** (the files go for good when the bar does).
- **After an install**, the result sheet offers **Delete the installer file** (a file on the phone's storage; not one picked through
  Android's chooser, not after a failed install, not when "Auto-delete package after install" already did it). It goes to the trash with an
  Undo. A new switch in the install options, **Offer to delete the file afterwards**, turns the offer off.
- **A workflow that runs the tests on every pull request** (`.github/workflows/tests.yml`): the UI scripts in headless Chromium and the
  Java suites (with a real Android shell and the framework classes) as two jobs; the output of a failed UI run is kept as an artifact.
- **For developers:** `FileSearch`, `ProgressMeter`, `JobTicker` and `ApkFlags` are plain Java with their own suites (`filesearch`,
  `progressmeter`, `jobticker`, `apkflags`), `fileops` also covers extraction with the three rules; the page side has `tests/ui/t83.js`
  (search and extract dialog) and `t84.js` (Find APKs cleanup and the offer after an install).
- **Not in this release:** archive formats other than zip-family (7z, rar, tar and its family, with passwords) are the next release.
  Search reads only folders the app itself can read.
- **Behaviour worth knowing.** In Find APKs the selection follows the filter (files hidden by a filter are un-picked and the count and the
  button follow what is shown), flags are recomputed after deletes and Undo, and an older version is compared only against the same app
  with the same signer and ABIs. Several long jobs at once share one notification.
- **Known limits.** A single-file extraction cannot be cancelled mid-file; **Delete the archive afterwards** deletes permanently (not to the
  trash) and ignores empty-folder entries; replacing a folder through a working mode (shell) can differ slightly from the direct route;
  a few place names and native problem messages are still English.

## v7.2-Pro (versionCode 720)

File manager, the first part of its overhaul.

- **A ".." row at the top of every list.** A folder drawn in CSS and "..": tap it to go up one folder. It is there in an empty folder
  and when a folder cannot be listed, so there is always a way out; the **Up** button stays. At the root of the phone there is
  nothing above, so no row. Every row now has a picture in front of it: a folder, a page, or the small picture of an image or video.
- **＋ File** next to **＋ Folder**: asks for a name (no slash, not "." or "..", not one that is taken, hidden files included),
  creates an empty file and opens the editor on it when it is a text file.
- **Show hidden files**: a box under the title (off at the start, kept between launches). Names that begin with a dot are left out
  until it is ticked, and the list says how many it is not showing. They are dimmed when shown.
- **Thumbnails, cached.** Images and videos show a small picture in place of the page. The app makes each one once (a 96 px JPEG),
  keeps it in its cache keyed by path, size and modified time, and trims the cache to 48 MB by removing the least recently used.
  **Show thumbnails** switches it off; **Clear thumbnail cache** empties it and says how much was freed.
- **When a name is already taken** (Paste here), a sheet offers **Replace** (a folder is merged with the one there), **Skip** (what is
  there stays; a folder still receives the files that are new) or **Keep both** (the new one becomes `name (1).ext`, `archive (1).tar.gz`,
  `folder (1)`). A copy pasted into its own folder offers it too, so it makes a duplicate. A move onto the same folder does nothing.
  The toast counts what was copied, skipped and failed.
- **Cancel** on a running copy, move or delete: it stops after the item it is on. A copy is written to a temporary name and renamed
  when whole, so a stopped or failed copy never leaves half a file and never costs the file it was going to replace.
- **No working mode needed for files the app can reach.** With All-files access, copy, move, delete, rename, ＋ Folder and ＋ File
  run in the app itself; ADB / Shizuku / Root are used only for what the app cannot reach (and for naming rules there, one command
  per item). The batch operations and the single ones both do this.
- **Open with…** and **Share** on any file: a copy (up to 400 MB) is handed to the app you choose through the app's share folder.
  **View** on a video or a sound does the same.
- **A text editor.** **Edit** on a text file (or one with an ending the app does not know; a file that is not text, or over 2 MB, is
  refused with the reason): the whole text, Wrap lines, a line and column, Save, and a question before closing with unsaved
  changes. A file changed by something else since it was opened is not overwritten unasked. Saving writes a temporary file and
  renames it, so a failed save keeps the old text; a file the app cannot write opens read only.
- **Viewers.** **View** on a picture shows it (up to 1600 px, Fit / actual size), on a **PDF** shows its pages one at a time with
  Previous and Next (Android's own renderer), on a **.ttf / .otf** shows a sample set in that font.
- **For developers:** `FileOps` (copy, move and delete with the three rules, links, cancel, atomic writes, and the same rules as an
  `sh` script for the shell route) and `ThumbCache` are plain Java with their own suite (`node java/run.js fileops`, 64 checks); the
  page side has `tests/ui/t82.js` with a small virtual file system (`tests/ui/lib/fm_mock.js`).
- **Not in this release** (next ones): archives other than zip (7z, rar, tar and its family, with passwords), search by name, date,
  size and type with `content:` and `archive:`, the extract dialog with a destination chooser, and the foreground service with progress,
  speed and time left. Video and sound are not played inside the app; **Open with…** hands them to a player.

## v7.1-Pro (versionCode 710)

- **A language setting.** **Settings** (the gear at the top) now starts with **Language**, a drop-down with English (the default) and
  thirteen more, each named in its own letters: Español, Français, Deutsch, Português (Brasil), Italiano, Русский, 简体中文, 日本語,
  한국어, العربية, हिन्दी, Türkçe and Bahasa Indonesia. The language changes at once, with no restart: tabs, buttons, filters,
  sheets, messages, the text in sentences with bold words or links, tool tips, input hints, and the dialogs of the browser. It is
  kept between launches and the app starts in it. Names of apps, packages, files, folders, settings and colors, paths, and the
  output of commands, logs and the terminal are left as they are, so an app called "Settings" keeps its name. Dates and numbers
  follow the language. Arabic runs from right to left. A language whose file cannot be loaded changes nothing and says so. The
  words of the notifications, the quick tiles and the home-screen widget are still English.
- **A font for the app.** **Settings > Font** (after the colors): **Use system font** or a font of your own, only for the text of this
  app (the rest of the phone is not touched). **Find fonts on this phone** searches storage for .ttf and .otf files with a progress
  bar and lists them with their real names (read from the font file, also for a variable font); **Choose a file…** opens Android's
  file chooser instead and needs no access to storage. Tap a font to see it on a sheet (a sample set in it) and then **Use this
  font**. A refused file says why (not a font, a collection, over 12 MB). The font is kept by the app and loaded again at each
  start; if it cannot be loaded the system font stays and the choice is dropped. Names, paths and commands in code-style boxes keep
  their own monospace font. Variable fonts need a WebView of Chrome 62 or newer. Known limits: a very large list can take a
  moment to translate, and the saved font is read at start-up.
- **Saved App Lists is now Saved Applications** (the tab, its first card and the quick-list tile text).
- **A new app icon:** the lightning bolt of the header on the same cyan-to-violet tile, as an adaptive icon.
- **For people who translate:** `tests/i18n/` holds the list of every string of the page (`keys.json`), the translator's brief,
  and the tools that cut a language into files, check them (changing parts, tags, line breaks, script) and put them together;
  `node i18n/check.js` checks all dictionaries in `assets/lang/`. A second tool (`audit.js`) finds names or paths that a word of
  a dictionary would have translated.
- **Tests.** Two new UI scripts: `t80` (the font: search, progress, list, sheet, use, system font, next launch, a font that cannot be
  loaded, the chooser, no access, an empty list, a variable font) and `t81` (the language: the drop-down, switching at once and back,
  kept between launches, later texts, dialogs, names left alone, right to left, a dictionary that is missing); a Java suite for the font
  search (`fontscan`: which files are fonts, what a font calls itself in every encoding, the bounded walk, the checked copy). 81 UI
  scripts and 18 Java suites in all.

## v7.0-Pro (versionCode 700)

- **New tab names, order and two-line labels.** The tab bar now reads Application Manager, Saved App Lists, UAD-NG Debloater, APK
  Installer, File Manager, ADB Console, Hidden Settings, RRO/Monet Customization, App Updater, App Stores, Logcat Viewer and About.
  App Updater moved to just before App Stores and Logcat Viewer to just before About. Names that are long break into two lines
  (Application / Manager) so more of the bar shows at once; ADB Console and About stay on one. The title of each tab's first card
  follows the new name.
- **A settings gear instead of the colors button.** The button at the top right now opens **Settings**: Appearance, the theme
  palettes and color pickers (what the colors button used to open) and, after them, the new Feature List. The gear lights up while
  Settings is open.
- **Feature List.** In Settings, every tab except Application Manager and About has a switch and two arrows. A switch off takes
  the tab out of the tab bar (a link inside the app that points at it says that it is off and where to turn it on, and does
  nothing else: no sheet closes, no download starts; only an APK opened from outside brings APK Installer back). The arrows
  move it up or down, and the bar follows at once; the row stays under your thumb and the keyboard focus stays on the control
  you used. **Reset to Default** switches them all on and puts them back in the first order. Application Manager always stays
  first and About last. The choice is kept between launches; tabs a later version adds are placed after the tab that precedes
  them by default, so a saved choice keeps working.
- **No emoji.** Tabs, filters, buttons, sheets, toasts, the home-screen widget and this changelog no longer carry any. The only
  ones left are the two settings gears: the one in the header (the app's settings) and the one on every app in the list (that
  app's settings). The text in the tabs and in the filters is a little larger to make up for it. Buttons that were only a
  picture now say what they do (Copy, Share, History, Saved, Run, Pin, Edit, Delete, Refresh, Install); the copy chips in an
  app's menu read Copy package / Copy version / Copy name; folders in the file manager and in the archive browser end in a
  slash; a store card says "10k stars" and "500k downloads". The terminal's History and Saved buttons moved under the command
  box, so the box keeps its width.
- **The Application Manager search bar sits under all the filters,** directly above the list, with a **menu (three dots) at its
  right end**. Its three options, all on at the start: **Include application names**, **Include package names** and **Use regex
  matching** (a line under it says that, with it off, applications and packages are matched exactly: the whole name has to be
  typed). The choices are kept between searches and launches. One of names and packages is always on: turning off the one that is
  left turns the other on. A pattern that is not valid (for example an open bracket), or that could freeze the list (a repeat inside
  a repeat, like (a+)+), is searched as plain text and a note says so. The box says what it searches, and a change redraws the
  list at once. The menu opens in view and the batch button steps aside while it is open; it closes when you tap elsewhere, type,
  go into the box or leave the tab (a scroll leaves it open). The bars that stick under the header follow its real height, which
  wraps on a narrow screen.
- **The Aurora sub-tab of App Stores is gone.** The Play Store card on the Updates tab still opens Aurora Store.
- **Error lines say that they are errors.** A failed store load, install, search for packages, package read or VirusTotal scan now
  starts with words ("Could not load: ...", "Install failed: ...", "The search failed: ...") and is in the failed color; before,
  the warning sign was the only thing that said so. The buttons the pictures used to tell apart are told apart again: Uninstall
  and Delete are in the failed color, the main action (Install, Restore, Reinstall) is tinted, a pinned command in the terminal
  has the accent color, a permission that cannot be changed (install-time) has a "locked" badge, and a component that is launched through the
  shell says LAUNCH (SHELL). The terminal's find box keeps room for its hint at 320 px, the sub-tabs of Hidden Settings and App
  Stores and the log level chips are a little larger, the filled accent buttons are readable in the dark theme, and sentences
  that named Files or Applications now name File Manager and Application Manager.
- **Tests.** Four new UI scripts: `t76` (the tab registry, the Feature List, saved choices from older versions, links into a tab
  that is off), `t77` (the search bar and its menu, the freeze guard), `t78` (no emoji in the sources or in anything the page draws,
  also when written as an escape) and `t79` (the findings of the review of the emoji removal); the others are updated for the new
  names. 79 scripts in all.

## v6.1-Pro (versionCode 610)

- **A bigger theme button.** The button at the top of the screen (it opens Colors & Themes) is now 44 × 36 px with a bigger
  icon, where it was about 40 × 25 px, so it is easier to hit.
- **The big counters show the list you are looking at.** On the Applications tab, the cards at the top (Total installed, Running,
  Frozen / disabled, User apps, System apps, Bloatware) light up with a colored border and glow for the filter that is on. They
  follow the filter pills below them (and the pills follow them), and the last filter is lit again when the app opens. A filter
  with no card of its own (Suspended, Updated 7d, Patched …) lights none. The cards also work from a keyboard and for screen
  readers: each is a button with a pressed state.
- **A tip under Export and Share CSV.** It says to scroll the filter pills sideways for more filters. It goes as soon as you
  scroll them (or tap its ✕) and stays gone.
- **Permissions on first launch.** The first time the app opens, a sheet offers the three accesses Android keeps in its
  settings: **All files access**, **Usage access** and **Display over other apps**. **Allow** opens that screen of Android's
  settings; **Allow all** walks through the ones still missing; with ADB, Shizuku or Root the app switches the last two on by
  itself, with no screen. Skip any of them with **Not now**: they are also under **About → Permissions**. After this update
  the sheet comes once, if one of the three is missing. It waits for What's new to be closed first.
- **File access is asked for when an action needs it.** When opening, editing, saving, adding or deleting a file, reading a
  package from storage, listing a storage folder you chose, or searching storage fails for want of All-files access, a sheet
  says what was being done and has an **Allow** button. When the access arrives the sheet closes and the action carries on by
  itself: the folder is listed again, the search starts again, the package is read again (an action asked for more than five
  minutes ago is left alone). **Not now** cancels it. The failures that follow straight away (one action, many files) do not ask
  again, but a button you press yourself (Go in the file manager, Find APKs) asks every time. The Files tab and the search the
  Installer starts when it opens only show a note with a button, not a sheet. With ADB, Shizuku or Root the storage is read
  without the access, so nothing is asked. A failure on a path the access cannot help with (system folders, other apps' data)
  asks for nothing. On Android 10 the app now also asks Android for the old file access (`requestLegacyExternalStorage`), which
  its storage permission needs there to reach files at all.
- **Launch Application and Application Settings after an install.** When the Installer reports a success, its result
  dialog has two more buttons above **Done**: **Launch Application** opens the app that was just installed (a toast says it
  went through; only a failure says why) and, under it, **Application Settings** opens Android's page for it. The dialog is
  taller to make room, and the output box keeps its height. On a short screen the dialog scrolls instead. Both buttons act on
  the package that was installed, even if you opened another one while the install ran.
- **The Installer scrolls to the package.** After you pick a file, also from the Find APKs list, the page glides down to the
  box with the package's name, version and signature (it does not move if you have left the tab, and it does not animate when
  your phone asks for reduced motion).
- **A progress bar for Find APKs on this device.** A bar under the status line shows how far the search is with the number of
  files found so far. Where the search cannot tell (the one shell command of a working mode) the bar sweeps instead.
- **Delete a found package from the phone, with Undo.** Press and hold a file in the Find APKs list and confirm: the file is
  removed from storage and an **Undo** bar stays for eight seconds. A tip under the list says so. While the bar is there the
  file waits in a hidden folder on the same storage; it is removed for good when the bar goes, when you leave the Installer or
  at the next start (if the app cannot reach storage then, at the one after). If a file with the same name has appeared in the
  meantime, Undo restores yours as "name (2)" rather than replacing it, and an Undo that fails is offered again. Only regular
  package files can be deleted this way, never a folder, and two files deleted at once cannot replace each other in the trash.
- **The splits that fit this phone are ticked.** Opening a package with splits ticks the base, the one CPU split the phone runs
  best, the screen-density split Android itself would pick for the phone's density and the phone's languages. The rest
  stay off, each with a note (another CPU, another language, a feature module …) so you decide whether to add it. A **Match this
  phone** link ticks them again after you changed things.
- **▾ Common installers and requesters.** The **installer source (-i)** box and the **requester / originating URI** box each have
  a small ▾ button at their end. It lists common values and fills the box when you pick one: Google Play, F-Droid, Aurora Store,
  Amazon Appstore, Samsung Galaxy Store, Huawei AppGallery, Xiaomi, OPPO / realme, vivo and HONOR stores, APKMirror Installer,
  Obtainium, Droid-ify, Neo Store, Accrescent, Aptoide, APKPure, Uptodown, itch.io and more for the first; Play, F-Droid,
  APKMirror, GitHub, itch.io and other addresses (with the package filled in) for the second. A **Clear this box** entry empties it.
- **Keyboard and screen readers.** The found files (Enter loads one, Delete opens its delete sheet), the ✕ of the tip, the
  colors button, the three Allow buttons (each named for what it allows) and the two ▾ lists work from a keyboard and say what they are.
  Pressing and holding a found file no longer starts Android's own text selection, and the permission prompt is painted over
  every other sheet, so it cannot hide under the one that raised it.
- **"Select all splits by default" is off by default.** The switch at the bottom of the Installer's options starts off (only
  the base and the splits that fit the phone are ticked). It still ticks every split when you turn it on.

## v6.0.4-Pro (versionCode 604)

- **A launch that works shows no dialog.** In an app's **Components** tab, **Launch** now just opens the activity and a toast
  says it went through. The sheet with Android's answer opens only when the launch fails, so a launch that worked no longer leaves
  a dialog to close.

## v6.0.3-Pro (versionCode 603)

- **The app menu is a little taller.** The sheet that opens from ⋯ on an app now stands 93% of the screen high instead of
  85%, so the lists under its buttons (Permissions, App Ops, Components, Manifest) get more room: 64 px more on a 360 × 800
  screen. Only this sheet changed; the others keep their height, and the strip above it still closes it when you tap it.

## v6.0.2-Pro (versionCode 602)

- **The Share APK button is gone from the app menu.** Tap ⋯ on an app and the action grid no longer has it. **Extract APK**
  is still there and still saves the app's `.apk` (or an `.apks` bundle for a split app) to Downloads and tells you where.
  Nothing else in the menu changed.

## v6.0.1-Pro (versionCode 601)

- **The Settings tab is now called Hidden Settings.** "Settings" sounded like this app's own settings, but the tab reads
  and edits Android's own **Global**, **Secure** and **System** settings tables, so it now says so
  (its card reads "Android's hidden settings"). Nothing else about it changed, and what you had saved (the log of
  changes with its Revert buttons, the table, filter and sort you last used) is kept.

## v6.0-Pro (versionCode 600)

- **An Overlays tab: recolor Android and switch its overlays.** A new tab right after Settings with two sub-tabs.
  **Theme** changes the Material You theme of Android 12 and newer: the source color the whole system palette is built
  from (your **wallpaper**, which is Android's default, or **any color**) and one of six styles (**Tonal Spot**,
  **Vibrant**, **Expressive**, **Fruit Salad**, **Rainbow**, **Spritz**). **Overlays** lists every overlay that
  `cmd overlay list` reports and switches each one on or off. Both go through ADB, Wireless Debugging, Shizuku or Root; with
  none of them ready the tab says so and opens Working Modes.
- **Pick a color the way you like.** A hex box (`6750A4`, `#6750a4`, `#abc` and `FF6750A4` are all understood), **hue /
  saturation / lightness** sliders, or **657 named presets** (searchable, with Red / Orange / Yellow / Green / Teal / Blue /
  Purple / Pink / Neutral chips). The last eight colors you applied are kept as chips.
- **Colors in use.** The five tonal palettes Android is using right now (Accent 1–3, Neutral 1–2, ten steps each) are read
  from the system and drawn as swatches; tap one to copy its color. They redraw by themselves a moment after a change,
  once Android has repainted, and on Android 11 and older the tab says that Material You needs Android 12.
- **Overlays, easy to handle.** The list is grouped by the app each overlay restyles, with how many are on in each group;
  search it, narrow it with **On**, **Off** and **Theme** chips, **tap the switch or press and hold the row** to flip an
  overlay, or tap the row for its sheet (state, target, **Switch on / off**, copy the name, the target or the exact `cmd
  overlay` command). Overlays Android lists as unavailable say so and are not offered as switches. Long lists are drawn 120
  rows at a time.
- **Honest about what happened.** An overlay change is read back by listing the overlays again in the same command, so a
  row shows what Android reports; an overlay that is fixed on or off by the system says so instead of pretending, and Android
  12's bare "commit failed" is called what it is (a refusal with no reason). A theme is read first, then written as the
  color members Android's own wallpaper picker writes, **keeping the other choices the setting holds** (a font, icon shape
  or icon pack on Pixel-like phones) because Android switches off whatever the setting leaves out, and read back. Phones with
  Samsung's "wallpaper colors" switch get it set to match and read back too: **Undo** puts it back to what it was, a theme
  Android refuses puts it back at once, and a switch that will not change is reported. Only fixed shapes can be written (six
  hex digits, one of six styles, wallpaper or color); overlay names are single-quoted, and names that could confuse `cmd overlay`
  (spaces, control characters, a leading `-`, over 300 characters) are refused before anything runs. When the link drops
  mid-change the app says it cannot tell whether it went through and reads the state again; a listing that fails after a change
  is not mistaken for "no overlays".
- **Undo, a log, and surviving the restart.** Every overlay or theme change shows a bar with **Undo**; theme changes are
  also listed in **Settings → ⋯ → Changes** with a **Revert**. Android restarts apps when the palette or an overlay
  changes, before or after the answer arrives, so the app keeps a short-lived note of the change and, when it comes back,
  returns to the tab with the Undo bar (and finishes the log entry the answer would have made). Back closes the overlay sheet,
  then the preset list, then leaves the tab.
- **Android 11 and older** open on the overlay list: Material You needs Android 12, so Apply theme and Default are off there
  and say why. A style Android knows but this page does not list (Monochromatic, say) is shown as it is.
- **Checked before it shipped.** Three independent review passes over the new tab (the native side, the page, and a second look at the logic the first fixes
  added: 26 findings, 25 fixed and one left on purpose, because overlays are switched for the phone's main user), plus 551 checks of the
  native rules (including round trips through a real `sh` for hostile overlay names and theme values, and every step of an
  Apply, Undo, Default and refusal against a fake phone), a comparison of the page's color check with the Java one over more
  than 1,300 cases, and a headless-browser pass over both halves of the tab at 320 and 360 px in light and dark.

## v5.9-Pro (versionCode 590)

- **A Settings tab: read and edit Android's hidden settings.** A new tab before Store lists every setting in the
  phone's **Global**, **Secure** and **System** tables (what `settings list` prints), one sub-tab per table with its
  count. **Tap** a setting to edit it, **press and hold** one that is a switch (`1`/`0`, `true`/`false`, `on`/`off` or
  `yes`/`no`) to flip it, keeping the capitalisation it had, and tap **＋** to create a setting of your own in any of the
  three tables. It works through ADB, Wireless Debugging, Shizuku or Root; with none of them ready the tab says so and
  opens Working Modes.
- **Easy to find your way around.** Search matches names, values and descriptions; chips narrow the list to **Switches**,
  settings with a **Description**, or the ones **Edited** with this app; sort by name or by value. About 130 well-known
  settings carry a plain-English description (`adb_enabled` is "USB debugging"), the ones that can cut your ADB link, lock
  you out of the screen or break setup are marked **careful** and ask before they change, and long tables are drawn 120 rows at a
  time with **Show more**.
- **The editor.** A sheet with the value in a text box (multi-line values and anything up to 20,000 characters), quick
  values (`0` `1` `true` `false` `null` `(empty)` `-1`), **Flip**, copy of the name, the value or the exact
  `settings put` command, **Reload** from the phone, and **Delete**.
- **Honest about what happened.** Every change is checked by reading the setting back, so "saved" means the phone now
  holds that value. When Android refuses, its own answer is shown with advice (Xiaomi, Redmi and POCO phones need
  "USB debugging (Security settings)" on in Developer options for most changes). Names that can never work (empty, starting
  with `-`, containing `=`, spaces or control characters) are refused before anything runs, and values reach the shell
  quoted, so `;`, `$(…)`, quotes and `>` are stored as text and never run. One request at a time reaches the phone, in
  the order you made them, and a request nobody answers times out instead of spinning.
- **Undo, and a log of what you changed.** Each change shows a bar with **Undo**; **⋯ → Changes** lists the last 150
  changes made with this app, newest first, and **Revert** puts the old value back (or removes a setting you created).
  **Copy shown** and **Share shown** export the list you are looking at as text.
- **Back works the way you expect.** Back closes the editor, then clears the search, then leaves the tab.
- **Reviewed twice, then hardened.** Two independent reviews (the native side and the page) found and fixed: a table cut short
  by a dropped connection or a timeout is reported instead of shown as if it were complete; a refused change or delete is no
  longer taken for success when the value is the word `null`; when the link drops during a change the app says it cannot tell
  whether it went through and reads the table again; the editor opens with the value the phone holds right now, and a refused
  Save keeps what you typed; settings that are a choice (private DNS, dark theme, ringer mode, rotation …) are no longer offered
  as on/off switches; press and hold works on a touch screen however long you hold; Revert acts on the row it is shown on;
  a value too big for adb to carry is refused instead of corrupting the request; and **Command** copies a command that runs
  on the phone (the old one broke when pasted into a PC terminal).

## v5.8-Pro (versionCode 580)

- **A Back button that does the sensible thing.** The system Back button / gesture now closes the sheet that is open,
  then clears a selection (apps, debloater, files), steps up a folder or out of an archive, returns to the previous
  tab, and only then warns **"Press back again to exit"**. Leaving takes a deliberate second press: a double tap
  (under 0.7 s) or a late one (over 3.5 s) just warns again, and while an install, an update download, a file job,
  a backup or a terminal command is running it asks before leaving. Nothing closes the app by accident any more.
- **Installs from Files always use ADB, Wireless Debugging, Shizuku or Root.** Installing from a file row, from inside an
  archive or right after signing never goes through the system installer: it runs on the privileged backend that is
  ready (and offers Working Modes when none is), with this app's own signature gates off so an edited or re-signed
  APK still opens. Android itself still verifies signatures (this app can't switch that off), so an APK whose signature
  Android refuses is signed with this app's key and installed, and an app signed by someone else is replaced only
  after you agree to uninstall it (its data is deleted; the copy that will replace it is prepared first, so a failure
  while preparing leaves your app alone, and with Shizuku as the backend Shizuku's own app is never the one removed).
  Failures say why and what to turn on. A key that was rotated still matches the key it replaced.
- **An About tab.** The new last tab shows the developer (**Bingblop**) and the GitHub repo, this build's version,
  package, device, WebView and **signing certificate** (when it is the official release key), copy / share
  **debug info** for bug reports, tips, privacy and credits, and **Buy me a coffee**: one tap opens PayPal to
  donate **$1**, or type any amount. Entirely optional; the app stays free.
- **Sign APKs you edited — on the device.** Editing an APK in the archive browser breaks its signature, so there is now
  a **Sign** button (and an "edited — sign it now" banner): it signs with **APK Signature Scheme v2** using a key that
  is generated inside the **Android Keystore** (in secure hardware where the phone has it, never exported). Sign in place
  or save a `-signed` copy, see the signature now / the installed copy's signer / this key's fingerprint, get a warning
  when an installed copy has a different signer, and install the result in one tap. Checked against the real
  `apksigner` for RSA and EC keys, multi-megabyte APKs and tampered files. Stored `.so` files are now 16 KB-aligned.
- **Archive browser: install, nest, compare.** **Install** an APK/APKS/XAPK from inside an archive without extracting it
  by hand, **open an archive inside an archive**, and **Compare** two archives (or an archive and an installed app) —
  added / removed / changed files, and a **line-by-line diff** of changed text and compiled XML (a new permission shows
  up at a glance). The extract folder is remembered.
- **Terminal: history, saved scripts and pinned buttons.** Up/Down arrows and a History list recall earlier commands, Save keeps
  named commands and multi-line scripts, and up to six can be **pinned as one-tap chips**. Works in the normal terminal
  and in Rish mode. The normal terminal no longer freezes the page while a command runs.
- **Logcat for one app.** Pick an app (or tap **Logs** in an app's menu) to see only the lines it wrote — across restarts,
  matched by its user ID — and **save or share** the filtered log as a bug-report text file with a header. The live view
  draws the latest 800 entries, skips redraws when nothing changed, and polls off the page's thread.
- **File manager: select many.** Press and hold a row (or tap Select), pick files and folders, then **Copy** or **Move**
  them (open the target folder, **Paste here**) or **Delete** them in one go. Many files go through a few shell commands
  instead of one each, conflicts ask first, failures are listed with the reason, and system locations are protected
  (including the same storage seen through `/mnt/...` mounts and however a path is spelled, `/a/../sdcard` too). A lost
  connection stops the batch instead of waiting on every item, and a file merely *named* like an adb error isn't
  mistaken for one.
- **Rish shell hardening.** A mistake that makes some shells quit (`. missing.sh`, `export a-b=1`, `$((1/0))`) no longer drops
  you out: the shell is restarted in the same folder with a note. **STOP** is gentler (Ctrl-C first, then terminate, then
  kill — on the whole process tree, so a script's children don't linger and unrelated background jobs survive), closing
  can't leave stray processes or be undone by a restart in flight, a silent shell makes start fail instead of spinning,
  `exec >/dev/null` or `PATH=` can't hide the end of a command, a runaway background job can't flood the screen, and the
  next shell opens in the folder you left. Tested against a real mksh + toybox.
- **Archive engine fixes (from an independent review).** Editing keeps what it used to drop — **AES-encrypted** entries stay
  decryptable and Unicode names stay — and refuses (instead of silently truncating) entries of 4 GB or more; extraction
  writes to a temporary file and **checks size and CRC**, so a damaged entry can't clobber an existing file and an entry
  can never overwrite its own archive; one bad entry no longer stops a folder extraction; **CRX / self-extractor** archives
  open (view-only); duplicate names don't block unrelated edits; renaming a file to `folder/` moves it into the folder;
  non-UTF-8 names keep their bytes; odd names (`/abs`, `a//b`) are listed; a text file with mixed line endings is
  view-only instead of being silently rewritten; an unchanged text save no longer rewrites (and unsigns) the APK; compiled
  XML decoding is capped so a crafted file can't exhaust memory; temp files are never left behind.
- **Security hardening.** App labels and file names are escaped everywhere (a package whose label contained HTML could run
  script with access to the app's shell bridge), store fields from third-party catalogs are escaped, the release build is
  no longer `debuggable`, backups are off (`allowBackup=false`), WebView debugging is only on in debuggable builds, the
  WebView can't be navigated away from the app's own page, and a crashed WebView renderer is recovered.
- **Faster.** The Applications list is drawn a page at a time (250, more as you scroll) so a phone with thousands of
  packages stays responsive; typing in a long list's search waits for a pause; counts are one pass; the working-mode probe
  is cached for a moment; logcat and terminal commands run off the page's thread.
- **Fixes.** Store lists no longer show duplicates when refreshed mid-load or when switching F-Droid repos; GitHub live search
  shows every hit and reports failures without hiding the list; the storage APK search can't get stuck, pages its results
  and the installer ignores the answer for a file you picked before the current one; the **app's own "Download" update
  button** works again; categories named like JavaScript built-ins, long unbroken names and emoji initials display
  correctly; logcat's color key works from the keyboard and no longer lags on malformed lines.

## v5.7-Pro (versionCode 570)

- **Terminal: Rish mode replaces "adb devices".** Tap **Rish mode** and the app switches the working mode to
  **Shizuku** (asking for Shizuku permission if needed) and opens a **persistent Rish shell** — one long-lived
  shell running as the Shizuku shell user, so `cd`, `export` and shell variables stay put from one command to the
  next. The prompt shows the device and folder (`husky:/sdcard $`, `#` for root), output **streams in as it is
  produced**, **RUN becomes STOP** while a command runs (it ends the command and keeps the shell), and tapping
  **Exit Rish** (or typing `exit`) goes back to the normal terminal. Unbalanced quotes and other typos can't wedge
  the shell, and very chatty commands are trimmed instead of freezing the page.
- **File manager: View now opens inside .apk, .zip and other package files — without extracting them.** The
  contents appear as folders (with search, breadcrumbs and paging for huge archives). Tap a file to preview it
  (text, images, **compiled Android XML shown back as XML**, hex for binaries) and then extract it, rename or move
  it, delete it, add files, create folders, or **edit a text file in place**. Archives are rewritten safely next
  to the original and swapped in only when that worked; unchanged entries are copied as raw compressed bytes, and
  APK-style alignment is kept. Works on ZIP64, protects against path-traversal names, and installed/system
  packages open **view-only**. (An edited APK's signature no longer matches, so it must be re-signed before
  Android will install it — the app says so.) Any file can also be tried with **Open as archive**.
- **Installer: XAPK support and a storage search.** Install `.xapk` bundles (base + splits, with the game data /
  OBB files copied into place afterwards). The old **Set as default installer** button is gone — the explanation
  of how to make this app the APK handler stays at the bottom of the card — and in its place a **Find APKs on
  this device** button runs an automatic storage search for every `.apk`, `.apks`, `.apkm` and `.xapk`
  and lists them (with size, age, folder, search and type filter); tap one to load it.
- **Store: "Komi" is now "GitHub", the catalogs are complete, and every store has a category drop-down.**
  - **GitHub** (was Komi) browses the whole catalog page by page (up to 5,000 Android apps) with live search
    and direct `owner/repo` install, and falls back to a built-in list when offline.
  - **F-Droid** now lists **every app in the chosen repo** (the index is streamed, so even the very large official
    index loads), defaults to the official F-Droid repo with a repo picker, caches for 12 hours, verifies each
    download's SHA-256, and asks first on a metered connection.
  - **Orion** now shows its whole catalog (887 apps; a size cap used to cut the last 87 off).
  - A **category drop-down** filters the apps in each store by the categories they are tagged with.
- **Logcat is readable.** Every log entry is its own row (a stack trace stays together), with a **level badge and
  color for Verbose / Debug / Info / Warn / Error / Fatal**, dimmed time / tag / pid, a **color key** you can tap
  to hide or show levels (with counts), highlighted filter matches, and live updates that don't yank your scroll
  position. Copy still gives the raw log lines.
- **Debloater checkmarks match the Applications tab** — a selected row fills the whole box with the accent colour.
- **Press and hold the ✓ button to clear every selection** in the Applications tab (a normal tap still opens the
  batch actions).
- **More room in the app list** — the per-row **Force Stop** button is gone (Force Stop is still in each app's
  ⋯ menu and in the batch actions), so names and badges get the space.

## v5.6-Pro (versionCode 560)

- **The Store is now five sources, in sub-tabs.** The Store tab opens on **ShizuStore** and adds four
  more sub-tabs beside it:
  - **Komi** — a curated set of trusted open-source apps that publish their APK on **GitHub Releases**
    (the kind of GitHub app store [komi-store](https://github.com/komi-store/komi-store) is built for).
    Each app is resolved live to the right build for your device's ABI and installed through your active mode.
  - **F-Droid** — the known third-party **F-Droid repositories** from the community
    [known-repositories](https://forum.f-droid.org/t/known-repositories/721) list. Tap a repo to browse and
    install its apps directly, or copy its address & fingerprint to add it to your F-Droid client. (The very
    large catalogs show their add-to-client details instead of browsing in-app.)
  - **Orion** — the public [Orion Store](https://github.com/RookieEnough/Orion-Store) catalog
    (`RookieEnough/Orion-Data`), including Morphe-built apps, resolved from GitHub, Codeberg and direct links.
  - **Aurora** — an honest hand-off to **Aurora Store** for Google Play apps (the private Play API can't be
    reimplemented in-app). Google Play and APKMirror sources are intentionally not bundled.

  Every install still downloads from each app's own upstream — nothing is rehosted — and runs through this
  app's installer (ADB / Shizuku / Root, or the system installer).
- **Better patched-app detection: Morphe and same-package ReVanced builds.** The patched detector now
  flags **Morphe** (`app.morphe.*` and the Morphe installer), and the inspector's deep scan streams each
  `classes*.dex` to recognise **ReVanced** and **Morphe** builds even when the patch keeps the app's
  original package name — the case the old signals (package, installer, manifest) missed.

## v5.5-Pro (versionCode 550)

- **Unexported activities now actually launch (ADB / Shizuku / Root).** Launching an activity that another
  app doesn't export used to fail with *"Permission Denial: … not exported from uid …"*, because the shell
  (uid 2000) isn't the activity's owner and doesn't hold `START_ANY_ACTIVITY`. The app now launches those the
  system's way: it briefly sets the target as the device **assistant**, injects **KEYCODE_ASSIST**, so the
  **system** starts the activity (which bypasses the exported check), then restores your assistant. Exported
  activities still start directly with `am start`. This is the same technique dedicated activity launchers
  use over Shizuku.

## v5.4-Pro (versionCode 540)

- **Patched / modified apps are flagged in the list.** Each app in the Applications list now shows a
  **badge** when it looks patched or repackaged by a third-party tool — **ReVanced**, an
  **Xposed / LSPosed module**, **LSPatch**, **NPatch**, or an app re-signed with a **debug key**. A new
  **Patched** filter lists only those apps. Detection in the list is free (package name, the manifest's
  `appComponentFactory` and meta-data, and the installer), so it adds no load time.
- **A definitive breakdown in the app inspector.** Opening an app (⋯ → inspector) runs a deeper scan that
  also reads the signing certificate and the APK's own entries (`assets/lspatch/`, `assets/xposed_init`,
  `META-INF/xposed/`, NPatch and ReVanced markers), and shows exactly which tool(s) touched the app and
  which installer put it there — so you can tell a genuine store build from a modified one before trusting it.

## v5.3-Pro (versionCode 530)

- **ShizuStore tab.** A new **Store** tab (at the end) browses [ShizuStore](https://github.com/timschneeb/ShizuStore)'s
  curated catalog of Shizuku-powered apps. Search and sort by most starred, most downloaded, recently
  updated or added; open an app for its description, screenshots, star count, requested permissions and
  source link; then **Install** downloads the APK straight from the developer's own upstream (GitHub /
  GitLab / F-Droid) and installs it through your active mode (ADB / Shizuku / Root), or hands it to the
  system installer with no privileged mode. Catalog and metadata come from ShizuStore by timschneeb; APKs
  are served by each app's developer, not rehosted.
- **Optional VirusTotal scan in the Installer.** The Installer tab has a new **VirusTotal scan** card.
  Paste your own VirusTotal API key and scan a package against 70+ antivirus engines before installing. The
  scan is a SHA-256 lookup, so **nothing is uploaded**; only if VirusTotal hasn't seen the file can you
  choose to upload it for analysis. Results show the malicious / suspicious / harmless counts with a verdict
  and a link to the full report. Entirely optional — leave the key blank to ignore it.
- **The app icon has no background.** The adaptive launcher icon is now fully transparent behind the mark,
  so it takes your launcher's own icon shape and backdrop instead of a filled square.
- **New installs start on Material You.** A fresh install now defaults to the Material You (dynamic,
  wallpaper-based) theme, falling back to the Material 3 baseline on Android 11 and older. Your saved theme
  is untouched on upgrade.

## v5.2-Pro (versionCode 520)

- **Pair over Wi-Fi from a notification.** The Wireless Debugging card has a new **Pair via Notification**
  button. It drops a high-priority notification with an inline reply box, so while Android's *Pair device with
  pairing code* dialog is on screen you can type the 6-digit code straight from the notification shade - no
  switching back to the app, so the code can't rotate out from under you. The app finds the current pairing
  port over mDNS automatically; if it can't, reply with `port code` (e.g. `37123 123456`). Pairing runs in the
  background and the notification updates with the result. If notifications aren't allowed yet, the button
  requests the permission first.

## v5.1-Pro (versionCode 510)

- **ADB cheat sheet in the terminal.** The ADB Console tab has a **Cheat Sheet** button: a searchable,
  categorized reference of ~90 commands (device info, packages, app control, permissions, intents, input &
  key events, screen, connectivity, battery testing, logs, files, reboot). Tap any command to drop it into
  the input (with `<placeholders>` selected for quick editing). Commands are in on-device shell form - no
  `adb shell` prefix needed, since the terminal already runs on the device. Compiled from Pulimet's ADB gist.
  The cheat sheet can also **load the full reference live from the gist** (Pulimet's list) inside the modal.
- **The terminal input no longer auto-capitalizes.** It defaults to lower case (ADB commands are
  lower case); hold Shift to type capitals when you actually need them.
- **App icon no longer has a black background.** The adaptive launcher icon now uses a clean white
  background behind the mark.
- **First-launch permission prompt.** On the first launch the app now checks and requests the standard
  runtime permissions it uses (notifications, and legacy storage on pre-Android 11). Special-access grants
  (All-files access, usage access, overlay) are still requested in context from their own screens.

## v5.0-Pro (versionCode 500)

- **Update this app from inside the app.** The Updates tab now has a dedicated **App update** card at the
  top, just for ADB Application Manager Pro itself. It checks this project's GitHub releases, shows your
  installed version vs. the latest with the release notes, and updates in one tap. The download is verified to
  be this app, a newer version and **signed with the same key** before installing. With ADB / Shizuku / Root
  the install is seamless (the app restarts); with no privileged mode it hands the APK to the system installer
  for a normal confirmation. Checking for updates also refreshes this card.
- **Unexported activities launch reliably.** Launching an activity through ADB / Shizuku / Root now starts it
  in a **new task** (`FLAG_ACTIVITY_NEW_TASK`), with fall-backs to a plain start and `cmd activity`. Without
  the new-task flag an activity often reported success yet never appeared - the usual reason an unexported
  activity "wouldn't launch". If every method fails, each attempt's output is shown so the real error is visible.
- **Navigation tidy-up.** A **Colors & Themes** button moved to the header (top-right), so themes are one
  tap from anywhere and no longer take a tab slot. Tabs are reordered: **Applications → Saved Lists →
  Debloater → Installer → Files → Updates → ADB Console → Logcat** (Logcat is now last).

## v4.9.5-Pro (versionCode 395)

- **The file manager resolves storage to `/storage/emulated/0`.** `/sdcard` and `/storage/self/primary`
  are symlinks, and the `self` view resolves differently for an ADB/Shizuku shell (uid 2000) than for the
  app, so a shell often can't read through them - which is why `/sdcard` could come up empty. Storage paths
  now resolve to the concrete `/storage/emulated/0`, which the app and a privileged shell read the same way,
  so storage browses correctly in every mode. The Storage shortcut and the default path use it too.

## v4.9.4-Pro (versionCode 394)

More file-manager fixes from on-device testing:

- **Fixed the `/sdcard//sdcard` doubled-path bug.** Because `/sdcard` is a symlink to the real storage
  volume, the shell fallback was listing the *link itself* as a lone entry instead of the folder's contents.
  Storage folders are now dereferenced when listed, and all paths are normalized (no more duplicate slashes),
  so `/sdcard` opens straight into its contents.
- **View and Install from the file manager no longer need a shell.** Viewing a file and installing an APK
  found in storage now read the file directly through the app's own file access (matching how listing already
  works), so they work on storage with All-files access and avoid shell-visibility limits. Privileged system
  paths still fall back to the shell.

## v4.9.3-Pro (versionCode 393)

More on-device fixes:

- **The file manager now reads storage directly.** `/sdcard` and other storage folders are listed through
  the app's own file access, which is instant and avoids the shell-visibility limits that can leave an
  ADB/Shizuku shell unable to see `/sdcard`. It falls back to the shell only for privileged system folders
  (`/data`, `/system`, ...). If storage looks inaccessible, a **Grant All-files access** button opens the
  right settings screen - after granting it, `/sdcard` lists with no privileged mode needed.
- **Logcat live tail is snappier** - shorter refresh interval and a lighter per-poll fetch while playing.

## v4.9.2-Pro (versionCode 392)

Follow-up on-device fixes:

- **File-manager folder listing is now robust across `ls` variants.** When a device's `ls -la` columns
  don't match the detailed parser, the listing falls back to a plain `ls -1p` name list (names, with a
  trailing `/` marking folders), so `/sdcard` and other folders list regardless. If a folder genuinely
  can't be read, the raw command output is shown instead of an empty screen, so the format is visible.
- **Logcat "Clear" now actually empties the on-screen terminal** (and the device buffer) and stops live
  play, instead of immediately repopulating.

## v4.9.1-Pro (versionCode 391)

On-device fixes from testing v4.9:

- **The file manager now lists `/sdcard` and other folders reliably.** The `ls -la` parser was rebuilt to
  handle both toybox and busybox date formats - it finds the time field and takes the name after it - so
  listings, names with spaces, symlink targets and sizes all parse correctly, and a permission-denied folder
  shows the error instead of looking empty. Opening the Files tab now always refreshes.
- **Logcat has a Play / Pause control.** While playing it live-updates (polls about every 1.5s) and
  auto-scrolls; it stops when you pause or leave the tab. Opening the tab always refreshes.
- **The Logcat and ADB Console panes are taller** (more terminal space).

## v4.9-Pro (versionCode 390)

Installs over v3.1 – v4.8 without uninstalling (same signing key).

- **New Installer tab — an all-in-one APK / APKS / APKM installer.** Pick a package file and the app
  reads it (package name, version, min/target SDK, size, signing certificate) before anything is installed;
  for a split bundle (`.apks` from bundletool, `.apkm` from APKMirror) it lists every split APK and lets you
  choose which to install, with the base always included and "select all splits" on by default.
- The install runs through whichever **authorizer** you pick — ADB, Shizuku, Root, or **No privilege** (the
  platform's own confirm-dialog installer, which still handles split bundles). It reuses the same
  session-based install path (`pm install-create` / `install-write` / `install-commit`) the restore flow
  already uses.
- Full install control, each mapped to the matching `pm install` flag: grant all permissions (`-g`), allow
  downgrade (`-d`), allow test packages (`-t`), install for all users (`--user all`), bypass low target-SDK
  block (`--bypass-low-target-sdk-block`, Android 14+), request update ownership (`--update-ownership`,
  Android 14+), install reason (`--install-reason`), package source (`--package-source`, Android 13+),
  installer package / install source (`-i`), and originating URI (`--originating-uri`). Privileged-only
  options are disabled automatically under the No-privilege authorizer, where the OS wouldn't honor them.
- Post-install **dex optimization** (`pm compile -m <mode>`, with an optional force recompile `-f`) and an
  optional **auto-delete** of the chosen package file once it installs successfully.
- Two safety gates before committing: **block signature mismatch** (refuse when an installed copy is signed
  with a different key, which Android would reject anyway) and **block unknown signature** (refuse an
  unsigned or unreadable package), both on by default and read straight from the APK. Packages are staged in
  the app's own cache and nothing outside that directory is ever installed.
- Toggles to show or hide the SDK, size and version readouts for the picked package.
- **Default installer.** The app now handles opening an APK file (a VIEW / INSTALL_PACKAGE intent for
  `application/vnd.android.package-archive`), so it can be set as the default APK handler. Opening an APK
  routes into the Installer tab; a privileged mode installs, and with no privileged mode it falls back to
  the normal system installer. A button deep-links to the default-apps settings.
- **Components now cover all four kinds.** The Components tab lists activities, **receivers**, **services**
  and **providers**, each with its exported / enabled / permission detail, and any component can be
  **enabled or disabled** (`pm enable` / `pm disable`). The shown state reflects the real pm override.
- **Dex optimization** is available as a single-app action (in the app menu) and as a batch action, with a
  compile-mode picker (`pm compile -m <mode>`, optional force `-f`).
- **New Files tab — a privileged file manager.** Browse any path with ADB / Shizuku / Root, view text
  files, create folders, rename, copy, move and delete, and install an APK from any location (staged to a
  readable temp, then handed to the Installer). Every path is shell-quoted.
- **New Logcat tab.** Read the device log (`logcat -d`) with level, line-count and text/tag filters
  (the filter is applied in-process, never in the shell), plus clear and copy.
- **Play Store (via Aurora) routing** in the Updates tab: detects whether Aurora Store / Play Store are
  installed, opens Aurora Store's in-app updates, and routes any installed app to its store page. (Automatic
  Play version detection is a planned follow-up, since it needs anonymous Play authentication.)
- The release workflow now builds, signs and publishes from a version tag; set the `KEYSTORE_BASE64` /
  `KEYSTORE_PASSWORD` repo secrets for a build signed with your key.

## v4.8-Pro (versionCode 380)

Installs over v3.1 – v4.7 without uninstalling (same signing key). Three more GitHub Copilot review
passes on v4.7-Pro's diff, after it had already shipped, found further genuine issues - all fixed here:

- **Unexported-activity launches from the Components tab could report success while doing nothing.**
  Android's `exported=false` denial frequently doesn't throw an exception back to a plain
  `startActivity()` call - it's just logged server-side and nothing opens - and activities without
  detailed per-activity info were defaulting to "exported" rather than "not exported". Launching an
  activity now always goes through the verifiable `am start` shell path whenever a working mode
  (ADB/Shizuku/Root) is active, regardless of the exported flag, and unknown/legacy activity info now
  defaults to not-exported instead of exported.
- Selecting multiple apps for a batch action no longer immediately covers most of the list with the
  full action panel; a small checkmark button appears in the bottom-right corner instead, which opens
  the panel on tap (and can collapse it back without losing the selection).
- **Restore no longer scans forward for `backup.json` by name.** A picked file is untrusted, and skipping
  past an unexpected entry first still fully decompresses it to find its end - a tiny zip bomb placed
  before `backup.json` would have been inflated in full before this app ever got to check anything.
  `backup.json` must now be the very first entry, or the file is refused immediately. The same
  unbounded-decompression gap applied to any other unrecognized entry during restore (not just `data.tar`,
  already fixed in v4.7-Pro, below); every entry is now read through the same byte-capped loop regardless
  of whether its contents end up kept.
- The manifest viewer's string-pool size check compared a crafted count against the whole manifest's byte
  size, which at the 32 MB cap still let a count of ~33.5 million through and allocated a reference array
  in the hundreds of MB. It's now bounded by what could physically fit in that pool's own offset table.
- Restoring app data no longer reports success when fixing the restored files' ownership or SELinux labels
  (`chown`/`restorecon`) actually failed.
- The working-mode tile considered ADB-over-TCP "ready" from an open port alone, which could select a mode
  where the device is actually offline or unauthorized; it now requires the same confirmed connection the
  other backends do.
- A backup or APK export that fails partway through writing no longer leaves the partial file behind
  looking like a finished one; it's deleted instead.
- **The update-check regex timeout could poison itself.** A timed-out match's thread is abandoned, not
  stopped, since `Matcher` can't be interrupted; running those on a small *shared* pool meant that after as
  few hostile catalog patterns as the pool had threads, every later, completely ordinary update check would
  queue behind them and never run - turning one or two bad entries into a standing outage. Matching now
  runs on its own fresh thread each time, so a stuck match only leaks that one thread instead of blocking
  everything after it. Separately, a timed-out match was indistinguishable from "didn't match", which an
  inverted asset filter could read as "nothing to exclude"; a timeout now always rejects the candidate
  instead.
- **A Root data backup could report success from a tar run that actually failed**, as long as it had
  written something to the output file first (a disk-full or I/O error partway through can do that). Only
  tar's own "some files changed while being read" code is now treated as the recoverable case it always
  was meant to be; anything else deletes the partial file and fails the backup.
- **A Root data restore deleted the app's current data before extraction had even been attempted**, so a
  failure at that point (which the preceding checks can't fully rule out) left the app with nothing instead
  of its original data plus a clean error. The current data is now moved aside instead of deleted, and
  restored if extraction or the ownership/SELinux repair after it fails.
- Editing the packages in the saved list currently used as the quick list now refreshes the Quick Settings
  tile/widget's label immediately instead of waiting for Android's next periodic update; deleting that list
  now clears it as the quick list instead of leaving a dangling reference.
- Importing a profile that lists the same package twice (even by accident) now keeps one entry for it
  instead of queuing both states' steps - including, in the worst case, contradictory ones for the same app.
- Clarified that on Android 8.0-9 (API 26-28, before `MediaStore.Downloads` existed) backups and extracted
  APKs are saved to this app's own storage instead of the public `Download/` folder, and are removed if you
  uninstall the app - this was already the actual behavior, just not previously documented.
- **Restoring permissions now applies the same dangerous-permission check backup creation already uses.**
  Without it, a crafted backup wrapped around an otherwise-legitimate, correctly-signed APK could list any
  syntactically valid permission in `backup.json` - including signature or development-level ones this
  format never actually produces - and have the privileged restore backend asked to grant it.
- A backup's filename only had minute precision; two backups of the same app and version within the same
  minute could collide on Android 8-9 specifically, where that name is opened directly as a file rather
  than through `MediaStore` (one backup silently overwriting the other, or a failed second write deleting
  an earlier valid one). Added millisecond precision.
- **Replaced the hand-rolled timeout around the two regexes matched against untrusted Obtainium catalog
  data with [RE2J](https://github.com/google/re2j), a linear-time (non-backtracking) regex engine.** Two
  earlier fixes in this same spot (a shared thread pool, then a fresh thread per attempt) both tried to
  survive `java.util.regex` hanging on a catastrophic pattern without actually being able to stop it; the
  second attempt still let a single hostile catalog entry leak an unbounded number of permanently-running
  threads over time; since `pickApk` matches the filter once per APK asset on every update check, that is
  an eventual resource exhaustion, not just a leak. RE2J has no pathological input by construction, so
  matching now runs directly with no thread or timeout machinery at all.

## v4.7-Pro (versionCode 370)

Installs over v3.1 – v4.6 without uninstalling (same signing key).

- **What's new**: the changelog is bundled in the app. After an update it opens once with the changes
  since the version you last ran; **Color & Themes → About** has the button, version and links.
- **Watch a profile**: in **Profiles**, tap **Watch** on one profile. When apps no longer match it
  (typically after a system update brings them back) a banner on the Applications tab says how many and
  opens the same reviewed Apply step. After a reboot with a new system build, a notification reminds you (Android 13+
  asks for notification permission the first time you watch a profile). Nothing is changed without your
  confirmation.
- **Quick Settings tiles and a home-screen widget** (they run without opening the app):
  - **Working mode** tile / **Mode** widget button: switches to the next mode that is ready
    (ADB TCP → Wireless Debugging → Shizuku → Root), or back to Automatic.
  - **Stop apps** tile / **Stop apps** widget button: force-stops every app in your **quick list**.
    Choose it with **Quick list** on a card in **Saved Lists**.
- **Backup and restore**: **Backup** in the app menu (and **Backups** on the Applications tab).
  A backup is one `.adbbackup` file in `Download/ADB App Manager/Backups/` holding the APK (with splits),
  granted permissions and changed app ops. With **Root** it can also hold the app's data. Restore
  installs the APK through ADB, Shizuku or Root, re-applies permissions and app ops, and with Root puts the
  data back. You can **Share** a backup or **Choose a backup file** from another phone. Data restore
  refuses archives that would write outside the app's own folders.
  Without Root, private app data cannot be read on current Android, so backups are APK + settings only.
- **Profiles are applied properly**: a profile now remembers disabled and suspended separately, and applying
  works out the steps per app (bring back, re-enable, unsuspend, disable, suspend, uninstall), so an app that
  is in a different state than the profile wants is changed instead of being counted as matching. Undo of a
  profile run unwinds the steps newest first. Saving profiles that the app cannot keep (too large) now says so.
- Extracting or sharing an APK handles one request at a time, so a second tap cannot mix up the results; the
  share sheet gets each file from its own private folder.
- A full pass through every source file (not just this release's changes), backed by Copilot review plus an
  independent full-file audit:
  - **Restore no longer trusts a picked backup file's own claims.** `backup.json` can come from "Choose
    backup file…" and is untrusted by design; restoring used to take its stated package, version and signing
    key at face value, including on the path that brings a removed system app back. Everything now comes
    from the extracted APK itself, both for an app currently installed and for a system app only present on
    the system image.
  - A backup now correctly names the base APK `base.apk` regardless of its real on-disk name, so backing up
    a system app (whose source file is rarely actually called that) can be restored at all.
  - Restoring data now refuses archives containing FIFO or device-node entries, not just hard links and
    outward-pointing symlinks; an app-op explicitly set to Allow is backed up and restored instead of being
    treated as "nothing to restore"; skipping a backup's data when you didn't ask for it no longer
    decompresses that data anyway.
  - **Quick list entries are validated before they can reach a shell command.** The list editor accepts any
    text, and the new Stop-apps tile runs each entry with no per-entry review; a crafted entry could have run
    an arbitrary privileged command on a single tap. Every package name is now checked before anything is
    built from it.
  - Deleting a watched profile clears the setting the reboot notification reads; that notification also has a
    working baseline on the very first reboot after you turn watching on.
  - **The manifest viewer no longer risks a confusing crash on a crafted or corrupted APK** (it already
    couldn't crash the app itself): a hand-rolled binary-XML parser had several unchecked-arithmetic and
    unchecked-allocation spots reachable by viewing any installed app's manifest, including a sideloaded one.
    It also now caps how much of a manifest it will read, and closing tags are tracked properly instead of
    trusting a value a crafted file could point anywhere.
  - **Update checks no longer trust a catalog's regex unconditionally.** The Obtainium catalog is
    community-maintained; a pattern with pathological backtracking could previously hang the update check.
    Matching now has a hard timeout. The Galaxy Store download link is now required to be HTTPS, matching the
    rest of the update sources, and a download is capped in size and can no longer report an empty or
    unbounded response as a successful update.
  - A handful of smaller robustness fixes: a failure partway through generating the per-install ADB key can
    no longer leave a mismatched key pair on disk; reading a very large file no longer crashes outright.
  - A second look at that same signer check found it would skip verification entirely if either side's
    signing certificate couldn't be read at all, instead of refusing the restore; it now fails closed.
  - Package-name validation no longer rejects `"android"` itself - one of several framework-owned permissions
    and components `PackageManager` genuinely reports under that single-segment name.
  - An install through Shizuku that fails partway through writing or committing its session no longer leaves
    it open. Backing up an app whose app-ops can't be read now says so in the backup instead of silently
    recording an empty, misleadingly-successful-looking override list.
- README: debloat-flow animation, light-theme screenshots and the new features.

## v4.6-Pro (versionCode 360)

Installs over v3.1 – v4.5 without uninstalling (same signing key).

- **Copy buttons and share sheet**: Package / Version / Name chips and Share in the app menu;
  **Copy Packages** and **Share List** for a batch selection; **Share CSV** for the app list; **Share**
  for the manifest and the terminal output; **Share APK** extracts an app and opens the share sheet.
  Files go through a private, non-exported provider (`ShareProvider`) with one-off read grants.
- **Search with highlight**: the manifest viewer and the terminal highlight every match, show
  "2 / 7 matches" and jump with ▲ ▼. The manifest viewer keeps "Matches only" (with line numbers) and can
  show the whole file in context instead.
- **App profiles**: save the disabled / suspended / uninstalled apps under a name, preview what
  applying would change, apply (recorded in History, so it can be undone), share as
  `.adbprofile.json` and import on another phone.
- README rewritten with screenshots, code snippets and the full feature list.

## v4.5-Pro (versionCode 350)

Installs over v3.1 – v4.4 without uninstalling (same signing key).

- **Select and copy text**: long-press any text (app names, package names, versions, permission and
  activity names, the manifest viewer, terminal output, debloat descriptions and history) to
  highlight it and use the system Copy menu. Buttons, pills and toggles stay unselectable, so taps
  still work as before.
- Dragging to select text on an app row no longer selects or expands the row by accident.

## v4.4-Pro (versionCode 340)

Installs over v3.1 – v4.3 without uninstalling (same signing key).

### App list
- **Sort** by name, last updated, install date, size, or updates first. When sorting by date or
  size, each row shows that value. The choice is remembered.
- **Updated 7d** filter for apps updated in the last week (handy for spotting a bad update).
- **Export** saves every app with its version, install and update dates, type, state, APK size and
  any known update as CSV to `Download/ADB App Manager/`.

### App menu
- **Sizes**: APK size (and how many parts a split app has); data and cache too once **usage access**
  is allowed. "Show data usage" grants it through ADB/Shizuku/Root, or opens the settings page.
  Sorting by size then uses total storage instead of APK size.
- **Extract APK** saves the app to `Download/ADB App Manager/APKs/`: a single `.apk`, or for split
  apps a `.apks` bundle (base + splits) that split-APK installers such as SAI can install.

## v4.3-Pro (versionCode 330)

Installs over v3.1 – v4.2 without uninstalling (same signing key).

- **Install and update dates** in the app menu, under the version
  ("Installed Jan 15, 2024 • Updated Sep 20, 2026").
- **Versions in the app list**: each app shows its version on the badge row. The **Versions**
  pill hides or shows them, and the choice is remembered.
- **Update hints**: when the Updates tab has found a newer version, the app list shows
  "v26.0 → 27.1" and the app menu shows "→ 27.1 available on Galaxy Store" with an **Update**
  button (or Download / Release for updates that install elsewhere).

## v4.2-Pro (versionCode 320)

Installs over v3.1 – v4.1 without uninstalling (same signing key).

- The app menu shows the app's **version** right under its package name, as
  "Version <name> (<version code>)". Apps uninstalled for your user show their version too.

## v4.1-Pro (versionCode 310)

Installs over v3.1 – v4.0 without uninstalling (same signing key).

### Open-source updates for sideloaded apps
- The Updates tab now checks sideloaded apps (not from the Play Store or Galaxy Store) against, in
  order: **your sources**, your **imported Obtainium list**, the **Obtainium community catalog**
  (looked up online, honouring its APK filter / version rules), **IzzyOnDroid** and **F-Droid**.
  Apps installed through an F-Droid client are checked against F-Droid first.
- Releases are read from **GitHub** (API, falling back to the public release pages when the
  60-checks-an-hour limit is reached; an optional **GitHub token** raises it to 5,000) and
  **Codeberg**. The APK matching the phone's architecture is picked automatically.
- **Import Obtainium List** reads an Obtainium export (Settings → Export) so every app you track
  in Obtainium is checked here too.
- Apps whose releases live somewhere only Obtainium can track get **Open in Obtainium**; sideloaded
  apps with no known source are listed under **Not tracked** with **＋ Set source**.
- Before installing, the download's **signing key is compared** with the installed app. A mismatch
  (for example an F-Droid build over a developer build) is stopped with a clear explanation instead
  of a failed install.
- Update All covers every installable source; releases without an APK for this phone link to their
  release page.

## v4.0-Pro (versionCode 300)

Installs over v3.1 – v3.9 without uninstalling (same signing key).

### Updates tab
- Checks the **Galaxy Store** for updates to Samsung system apps and apps installed from the Galaxy
  Store, using Samsung's public update service, and checks this app's **GitHub Releases**.
- **Update** per app, or **Update All** (one at a time). Each update is the official APK from the
  Galaxy Store, verified to be the same package and a newer version, then installed through ADB,
  Shizuku or Root. Failures show Android's reason with a Retry button.
- Updates for this app open in the browser so Android's installer handles them.
- The tab shows how many updates are pending. Play Store apps aren't covered: Google provides no way
  for other apps to check them, and the Play Store keeps updating them itself.

### Debloat history
- Every Debloater run, batch action and single-app freeze/uninstall/suspend (and update) is logged
  with time, packages and result. **Undo** reverses a run (reinstall what was uninstalled, enable
  what was disabled, and so on). **Copy Log** and **Clear History** included. Kept between launches
  (last 150 entries).

### Remembered filters
- The Applications filter and all Debloater filters (levels, vendor, state, brand) are restored
  when the app starts.

## v3.9-Pro (versionCode 290)

Installs over v3.1 – v3.8 without uninstalling (same signing key).

### Debloater: filter by brand
- New brand filter row in the Debloater. Your phone's own brand comes first (outlined in the accent color, detected from the
  phone's manufacturer), followed by the other brands found on the phone (Google, Meta, Microsoft,
  carriers, chip makers...), each with a package count that follows the other filters.
- UAD-NG has no brand field, so the maker is worked out from the package name (`com.samsung.*` /
  `com.sec.*` → Samsung, `com.facebook.*` → Meta, `com.oplus.*` → Oppo, and so on), with a
  fallback to the organisation part of the name. Each package also shows its brand as a badge,
  and search matches brand names.

## v3.8-Pro (versionCode 280)

Installs over v3.1 – v3.7 without uninstalling (same signing key).

### Debloater tab
- New **Debloater** tab powered by the
  [Universal Android Debloater Next Generation](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/wiki)
  (UAD-NG) community list: descriptions, removal levels and dependency notes for 5,000+ packages.
- The list (`uad_lists.json`, GPL-3.0) is **downloaded from UAD-NG's GitHub at runtime** and cached
  on the phone, not bundled. It refreshes automatically when older than a week, or with **Update List**.
- Shows only packages found on your phone, including ones already uninstalled for your user.
- Filters: removal level (Recommended by default; Advanced, Expert, Unsafe), vendor list (OEM, Google,
  AOSP, Carrier, Misc) and state (on device, enabled, disabled, uninstalled), plus search across
  package, name and description.
- Tap a package to read its full description and dependencies; packages that other installed
  packages need are flagged.
- **Uninstall** (for your user, reversible), **Disable**, **Restore** and **Save to List**. Every action
  is reviewed first, with a prominent warning when Expert or Unsafe packages are included.
- **Removal Levels** explains the four levels (from the UAD-NG wiki); **UAD-NG Wiki** opens the wiki.

## v3.7-Pro (versionCode 270)

Installs over v3.1 – v3.6 without uninstalling (same signing key).

### Suspend / Unsuspend
- The app menu has **Suspend** (for active apps) and **Unsuspend** (for suspended ones).
  A suspended app stays installed with its data, but can't be opened until it is unsuspended.
- Suspended apps are marked in the app list with a **SUSPENDED** badge and a greyed-out icon,
  and a new **Suspended** filter shows only them. Suspension is detected in every mode,
  including Read-Only.
- The batch sheet has **Suspend** (asks for confirmation, like Freeze and Uninstall) and
  **Unsuspend**.

## v3.6-Pro (versionCode 260)

Installs over v3.1 – v3.5 without uninstalling (same signing key).

### Security: private ADB key per install
- Versions up to 3.5 shipped the same ADB key inside every APK (and in this public repo), so anyone
  with that key could connect to a phone that had approved it with ADB TCP open. **The bundled key is
  removed.** Each install now generates its own 2048-bit RSA key on the phone.
- On first launch after updating, the old shared key is replaced automatically. Your phone asks
  **"Allow debugging?"** once on the next ADB connection, and Wireless Debugging must be paired again.
  To fully remove the old key, tap **Revoke USB debugging authorizations** in Developer Options.
- Working Modes shows the key's fingerprint (it matches what the "Allow debugging?" prompt shows),
  with **Copy Fingerprint** and **Regenerate Key**.

### Appearance
- **Schedule**: a fourth Appearance option. Light and dark start times (default 07:00 / 19:00,
  ranges may cross midnight). The theme switches on time while the app is open.
- **Pure black in dark mode**: true #000000 backgrounds for AMOLED screens, with cards lifted just
  enough to stay visible.

### Builds on GitHub
- New **Build APK** workflow builds and signs the APK on every push and pull request and uploads it as
  a downloadable artifact. Add the `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD` secrets to sign with
  your release key (see README).

## v3.5-Pro (versionCode 250)

Installs over v3.1 – v3.4 without uninstalling (same signing key).

### Light and dark mode
- New **Appearance** switch at the top of Color & Themes: **Light**, **Dark** or **System**
  (follows the phone's dark mode, and re-themes instantly when it changes).
- Every palette has a light version: **Material 3** uses the official M3 light scheme,
  **Material You** reads both light and dark schemes from the wallpaper, and the six classic
  palettes get a generated light scheme with accents darkened to readable contrast on white.
- Status and navigation bar icons switch to dark on light themes.
- The whole UI is now theme-aware (sheets, inputs, buttons, toasts, avatars, terminal); text on
  accent-colored buttons picks black or white automatically.
- Color picker tweaks are saved separately for light and dark, with a **Reset tweaks** button.

### Defaults
- New installs start on **Material 3** with Appearance set to **System**. Existing users keep
  their current palette in Dark mode.

### Material You
- Follows wallpaper changes: the dynamic colors are re-read when the wallpaper changes and whenever
  you return to the app.

### Fixes
- ADB Console output keeps its line breaks.

## v3.4-Pro (versionCode 240)

Installs over v3.1 – v3.3 without uninstalling (same signing key).

- Color & Themes: **Material 3** is now the first palette and **Material You** the second, with the
  six classic palettes below them.

## v3.3-Pro (versionCode 230)

Installs over v3.1 / v3.2 without uninstalling (same signing key).

### Components
- The Components tab now lists **every** activity, including **unexported** and disabled ones, with
  badges (exported / unexported / disabled / protected), a count, search and filters
  (All / Exported / Unexported).
- Every activity has a **Launch** button:
  - Exported activities start with a normal intent (works in any mode).
  - Unexported activities start with `am start -W -n` through the active privileged mode
    (ADB TCP, Wireless Debugging, Shizuku or Root). Read-Only mode asks you to set one up first.
  - The full `am start` output is shown, so when Android refuses a launch (for example a
    "Permission Denial" for the shell user, or a disabled activity) you see the exact reason.
    Root can usually start activities the shell user cannot.
- Component names are validated and single-quoted in shell commands, so inner classes with `$`
  launch and stop correctly.

## v3.2-Pro (versionCode 220)

Installs over v3.1 without uninstalling (same signing key).

### App menu (single app)
- **Permissions** now has its own list: search, filters (All / Granted / Denied / Changeable /
  Install-time), protection badges (runtime, development, normal, signature, app op) and permission
  labels. Runtime and development permissions toggle between Granted/Denied; install-time
  permissions are shown locked because `pm grant/revoke` cannot change them.
- **App Ops** is a separate list: search, filters (All / Allowed / Ignored-Denied / Foreground) and
  one-tap modes per op (Allow, Foreground, Ignore, Deny, Reset). Uid-level modes are labeled. Any
  other op can be set by name (e.g. `RUN_ANY_IN_BACKGROUND`).
- New **Manifest** viewer: decodes the app's binary `AndroidManifest.xml` into readable,
  syntax-highlighted XML, with resource references resolved to names. **Find** shows matching lines
  with line numbers. **Copy** puts the XML on the clipboard; **Save to Downloads** writes
  `Download/ADB App Manager/<package>_AndroidManifest.xml`.
- The menu shows **Freeze** for enabled apps and **Enable** for frozen ones.

### App list
- Each app row now has only **App Settings**, **Force Stop** and the **Menu** button.
  Freeze/Enable moved to the menu. App Settings works in every mode, including Read-Only.

## v3.1-Pro (versionCode 210)

### Material Design 3
- New **Material 3** palette: the M3 baseline dark scheme (primary `#D0BCFF`, surface `#141218`,
  surface container `#211F26`, on-surface text `#E6E0E9`).
- New **Material You** palette: M3 dynamic color generated from the wallpaper on Android 12+
  (falls back to the M3 baseline on older versions).
- Palettes now also theme text, muted text, surfaces and the secondary color, and the status and
  navigation bars follow the theme background.
- The selected theme is now restored on launch (it was saved but never loaded before).

### Working modes
- **Fixed: modes could not be changed while ADB over TCP 5555 was enabled.** Every status refresh
  re-connected port 5555 and forced the saved mode back to ADB TCP. Status checks are now read-only
  and the selected mode only changes when you pick one.
- New **Automatic** mode card and a **Use This Mode** button on every mode.
- The header badge shows when a pinned mode is selected but not ready (orange), instead of silently
  falling back to another backend.
- Disconnecting ADB returns to Automatic, so Shizuku or Root can take over.
- `tcpip 5555` now targets the wireless debugging connection when one exists (avoids
  "more than one device").
- Root is no longer picked automatically (it caused su prompts); select it explicitly.

### Wireless Debugging
- **Fixed: IP addresses could not be typed.** Address fields were `type="number"`, which blocks `.`
  and `:` after the first few digits. They are now text fields that accept `IP:port`, `[IPv6]:port`
  or just a port.
- Added an IP field to Step 2 (Connect), plus **Use My Wi‑Fi IP** and **Auto-Detect Ports** (adb mDNS).
- Pairing accepts `IP:port` too.

### Shizuku
- **Fixed: Shizuku could not authorize the app.** The manifest was missing Shizuku's
  `ShizukuProvider` and the `moe.shizuku.client.V3_SUPPORT` marker, so Shizuku could not deliver its
  binder or show the permission prompt. Authorization now uses the official Shizuku API (13.1.5).
- Approving the prompt switches to Shizuku mode immediately, even with ADB TCP 5555 enabled.
- Commands run through the Shizuku remote process API (faster), with `rish` as fallback.
- New **Open Shizuku** button and clearer statuses (not installed / not running / update needed).

### Other fixes
- "Select All" in the batch bar called a function that did not exist.
- `adb devices` parsing now checks the exact target and its `device` state.
- Batch action results now include each command's output.
