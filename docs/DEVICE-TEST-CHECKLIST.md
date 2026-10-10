# On-device test checklist (v7.1 to v7.12.9)

The automated tests run the page in a browser with a pretend phone. They cannot prove the parts that talk to Android:
icons, the file chooser, added storage, shell commands and the haptic feedback. This list is for those. Tick a box when
it works; write down what you saw when it does not.

**Before you start**

- [ ] Install the APK over the old version (it must keep your data). About shows the version you expect.
- [ ] A working mode is on (ADB over TCP, Wireless Debugging, Shizuku or Root). Most items below need one.
- [ ] Use a phone you do not mind changing: some items disable apps or change app ops. Everything can be undone from the same screens.

## 0. New in v7.10.0

- [ ] Hidden Settings: under each name there is what the setting does and a Values line with the current value in bold (try zen_mode, screen_off_timeout, wifi_on); tap one: the About box and the value buttons show; an unknown name (a Samsung or app setting) says "What it probably is"; the "How to read this list" link opens the explanation; wifi_on and zen_mode are not flipped by a long press (v7.10.1).
- [ ] Morphe Patcher > Patch sources: the official Morphe Patches and Gboard bundles show their patch count (no "Could not be read"), the Apps pane lists their apps (v7.10.1 fix).
- [ ] Morphe Patcher > Gboard: patch the Gboard APK or APKM with all its patches: no "NoSuchMethodException" in the log, only patches that really do not fit are reported (v7.10.2 fix).
- [ ] Morphe Helper: download the APKM of an app in the browser (Open the site), come back: it is listed under "In Downloads" with its parts; Use, then Patch this file, and Install all parts with and without a working mode (v7.10.2).
- [ ] Morphe Helper > All versions on APKMirror for com.google.android.inputmethod.latin: versions older than the newest ten appear (and Gboard 18.0.3.954559732 with This version), or, if APKMirror blocks it, only the newest and no error (v7.10.3).
- [ ] Morphe Patcher: a run where one patch fails: the card shows the first error open and "N more failed only because a patch they need failed" folded (v7.10.3).
- [ ] Terminal > Settings > Sync with Termux: the Termux shell restarts and prints "Synced with Termux" with your aliases and functions counted (put `alias hi='echo hello'` in Termux's ~/.bashrc, sync, run `hi`); Termux does not open. Set up storage in Termux: opens Termux only when storage is not set up, otherwise says it is (v7.10.3).
- [ ] Terminal > Real terminal (v7.10.3): with Shell "This app" a prompt appears; `ls`, then `top` (full-screen, q quits), arrows and CTRL then c work, the extra keys row works, the keyboard does not hide the keys; with "Working mode" (ADB: this is `adb shell` on a pty; Shizuku/Root: the helper is run as shell/root), with "Termux": `vim` or `nano` (after `pkg install vim`) works and resizing (rotate the phone) redraws it. The X keeps the session, `exit` then Enter starts a new one.
- [ ] Terminal > Termux setup > Set up Termux now (v7.10.3): the steps run in the Termux shell (pkg update, pkg install vim nano git python ...), then "Termux is ready" appears; in Termux, `vim --version`, `git --version`, `python --version` work and a new Termux session shows the coloured prompt with the git branch inside a repository; `~/.bashrc` still holds your own lines. Run it a second time: it is quick and `~/.bashrc` still has one starter block. Choose tools: tick tmux, untick htop, run: tmux is installed and htop is not touched.
- [ ] Morphe Helper > in-app browser (v7.10.3): with APKMirror blocked in Helper, "Open it here, in the app" opens APKMirror and the browser check passes; open an app, pick a version and variant, tap its download: the bottom bar shows the name and progress, the browser closes by itself and Helper shows the file (e.g. "APKM, 5 parts"). Back goes back in the page; the Android Back key closes the browser at the first page. With Settings > Open the sources in = The phone's browser, Open the site uses your browser as before.
- [ ] App Stores > screenshots (v7.10.8): open an app with screenshots in ShizuStore, tap one: it opens full screen. Pinch to zoom in and out, double tap, drag a zoomed picture, swipe to the next one, use the arrows, press Back: only the viewer closes and the app's details are still there. Rotate the phone while it is open (the picture should still fit), and try it with a slow connection (it says Loading… and then shows it).
- [ ] Saved Applications > Back up and restore (v7.10.8): Choose folder… and pick a folder on the phone (then try an SD card or a cloud folder if you have one); Back up now: the file `saved-lists-….json` is in that folder (check with the Files app). Switch Back up automatically on, edit a list, wait 5 seconds: `saved-lists-latest.json` is updated (one file, no copies). Delete a list on purpose, then Restore from the folder… and choose Add them to my lists: it comes back. Restore from a file… with the same file: nothing doubles. Replace my lists with them with a backup made before you changed lists: you get the old state, and the Quick list is the one it was. Close and reopen the app, back up again: the folder still works (no need to choose it again).
- [ ] Connected Devices > Saved devices (v7.10.9): connect a watch or tablet, then open Saved devices (N): it is listed with how it was connected. Switch its Wi-Fi off: it stays listed with Connect and Delete. Delete it (asked first): it goes from this list and from the Not connected cards. Delete one that is connected: it stays connected. Switch Remember the devices I connect off, connect another device: it works but is not saved.
- [ ] Logcat > tap an entry (v7.10.9): a window opens with the whole message. Copy: paste it somewhere. Web search: your browser opens a search. Ask agent (was More info before v7.12.0) with a default agent connected (Command-Line Interface tab): an explanation streams in under the buttons; with none connected, it says so. Long-press text in a row: it selects, the window does not open.
- [ ] Task Manager (v7.10.9): CPU tab > the °F / °C menu changes the CPU temperature (and the Battery tab's). GPU tab: the renderer card is the first thing; change it (working mode needed) and the card names the new one after System UI restarts.
- [ ] Apps tab (v7.10.9): the boxes at the top show their colours when on (Enabled green, Frozen cyan, 3rd Party orange, System purple). Turn on Running, then tap Bloatware (Uninstalled): Running goes off and only the uninstalled apps are listed. The row under the boxes starts with the sort menu, with no "Sort" text before it.
- [ ] Hidden Settings (v7.10.9): open a setting, Web search opens the browser; Ask agent (was Explain (AI) before v7.12.0) with a default agent connected explains it under the buttons. Look up wifi_frequency_band or ble_scan_background_mode: they say what they do and list their values.
- [ ] Haptic feedback (v7.10.10): tap buttons, tabs, switches and list rows: a short tick each time (once, never twice for one tap). Settings > Haptic feedback > Vibrate on taps off: nothing vibrates; on again: one tick. It is on after a fresh install.
- [ ] App Stores > + (v7.10.10): add a GitHub project (https://github.com/owner/project), then a GitHub user, then an F-Droid style repository (for example https://apt.izzysoft.de/fdroid/repo): each becomes a tab before the +, named from its address (or by you), loads its apps, and is still there after closing the app. Rename and Remove this store work. An address that is no repository says so. Tap a row in the GitHub, F-Droid, Orion and your own tabs: the sheet opens with the description, screenshots (tap one to enlarge), Website and Source; Install in the sheet installs the app.
- [ ] Task Manager > Processes (v7.10.10): Apps first puts the apps above the system processes; tap again for the plain order.
- [ ] Settings (v7.10.10): Find fonts on this phone, then go to another tab and come back: the list is gone. The gear in the header is in the colours of the theme you chose, not green.
- [ ] SD Maid (v7.10.10): the tab is called SD Maid and has the Trim Caches card (not in Settings). Turn the accessibility service on in Android's settings BEFORE giving consent in the app, then give consent: it stays on. Scan AppCleaner, Delete: caches the files cannot reach are cleared by the service (the screen shows each app's settings for a moment). If some are left, Clear the rest with accessibility does them.
- [ ] Force stop and Clear data read back (v7.10.17): open an app that is running (a browser), Force stop: its row shows a spinner (Working…, Checking…) and the message ends "<App>: Stopped"; an app with a keep-alive service may say "Still running". Clear data on an app that has files under Android/data (a browser, a game): in ADB or Shizuku mode the card says "Checked afterwards (the app's folder on the shared storage...)" and Data cleared; in Root mode it counts the private folder. Turn Settings > Progress messages off: the spinner still shows.
- [ ] Debloater list (v7.10.17): tap a row of the UAD-NG Debloater list to open it: a Removal Levels button at the bottom opens the four levels.
- [ ] Morphe Helper and APKMirror splits (v7.10.17): Morphe Patcher > Helper, a package that APKMirror has only as a bundle (Google Photos, YouTube Music...): Newest/This version. If it says the source blocked it, Open it here: the in-app browser opens the variant page for your phone (the BUNDLE row); tap Download: the .apkm is saved, listed "APKM, N parts", and Install all parts / Patch this file work.
- [ ] Leftover data of one app (v7.10.18): uninstall an app with "Keep data" (or use one already uninstalled), turn on the Uninstalled filter: its row has a bin button. Root mode: it names the size; ADB/Shizuku: it just asks. Confirm: the batch result says the data was cleared.
- [ ] Selected apps list (v7.10.18): select a few apps, open Show Applications: Select All / Clear All are outlined buttons and work.
- [ ] VirusTotal quota (v7.10.18): Settings > VirusTotal API key, paste a key, Test key: the line "N of 500 lookups left today" appears with the time to 00:00 UTC; scan a file in the Installer: Refresh shows one fewer.
- [ ] SD Maid > Clear Data from Uninstalled Apps (v7.10.16): uninstall an app with `pm uninstall -k --user 0 <package>` (or use the Uninstall (keep data) action), open the SD Maid tab, tap the button: in Root mode it is listed with its size; in ADB/Shizuku mode every uninstalled app is listed. Tick it, confirm: the results say how it was cleared. In Root mode check `ls /data/user/0/<package>` is gone. Then install the app again: it starts fresh.
- [ ] Batch menu (v7.10.16): select a few apps, open the batch menu: grab line at the top in the middle; pull it up (taller), down (smaller), down again (closes to the checkmark button, apps still selected). Select All / Clear All have outlines; Keep selection after running is on; turn it off, clear, select again: on again. Show Applications is next to Copy Packages, Share List in the grid. Switch to another tab with apps selected: the checkmark button is gone at once and nothing is selected when you return. Tap Uninstalled then Running: Uninstalled turns off. The package names under the app names have a tint of the theme colour.
- [ ] VirusTotal key (v7.10.16): Settings > VirusTotal API key: paste a key, Test key: approved. The Installer's VirusTotal card (load an APK) says it uses the key; remove the key: it says to enter it in Settings with a button that opens Settings. Morphe Helper > Settings > VirusTotal says the same.
- [ ] UAD-NG tag (v7.10.15): tap the UAD-NG tag on an app in the list: the sheet shows the level, the description and no explanation of the level; the bottom buttons are UAD-NG Wiki, Removal Levels, Close. Removal Levels opens a second sheet with the four levels; Back closes only that sheet; Close on it returns to the first.
- [ ] Clear data in Root mode (v7.10.14): with Root as the working mode, open an app that has data (a browser, say) and tap Clear data (confirm): the toast ends "<App>: Data cleared" and its card shows "Checked afterwards: N files (X) before, 0 after". Try a batch of three. Then switch to ADB or Shizuku and do the same: no "Checking" and the old "Action executed" message.
- [ ] Connected Devices apps (v7.10.14): on a connected watch Disable an app: "Checking the device…" and then "<App>: Disabled"; Uninstall a harmless one: "Uninstalled"; select three and Disable: one look, and the failure sheet (if any) names only the ones that did not change. Settings > Progress messages off: the "Checking…" and "Working on it…" messages are gone, the results stay.
- [ ] One app, read back (v7.10.13): in an app's menu tap Freeze: the toast goes Working on it… > Checking the phone… > "<App>: Frozen"; Enable > "Enabled"; Suspend an app (needs Android 9+; a work-profile or protected app may refuse) > "Suspended", Unsuspend > "Not suspended"; Uninstall a harmless app > "Uninstalled" and Reinstall > "Installed". Try a protected system app: it should say "Still installed" / "Not frozen" with the result window. A batch of Suspend on three apps: the progress sheet says Checking the phone… (3 apps) before the results.
- [ ] Connected Devices > Console (v7.10.13): type `getprop ro.p` and see the grey suggestion, Right arrow (keyboard) or a tap on the line accepts; open the Apps sub-tab first, then `pm path com.` offers the device's own packages; in adb mode `pu` offers `pull /sdcard/`.
- [ ] Batch uninstall (v7.10.12): select five installed apps you do not need (include one system app if you use ADB or Shizuku), Batch > Uninstall, confirm. The results list tags each app Uninstalled (green) or Still installed (red), not "Failed" for apps that are gone; the toast counts match. Check one card: it may begin "Checked afterwards: uninstalled...". Then select some of them again (Show uninstalled) and Reinstall: tags say Installed. Freeze and Unfreeze three apps: Frozen / Enabled.
- [ ] About > Contact the developer (v7.10.12): the window names bingblop.coral666@simplelogin.fr; Send opens the email app addressed to it, subject ADB App Manager; send yourself a test and check it arrives in the developer's inbox through the alias.
- [ ] RRO/Monet > Overlays (v7.10.11): the list shows Enabled first, then Disabled, then Installed, not changeable (counts in the headings). Every row has a short description under its name that makes sense for what it is. Switch one on: it moves to Enabled after the refresh.
- [ ] Terminal and ADB Console suggestions (v7.10.11): type `pm l` in the Terminal: a grey completion line shows above the box; the right arrow key on a keyboard (or the on-screen arrow of a hardware keyboard) accepts it; on the phone's own keyboard, tapping the line accepts it. After `pm path ` and the first letters of an app's package name, the package is offered. Same in the ADB Console pane. Nothing runs until RUN.
- [ ] About > Issues and Contact (v7.10.11): Issues opens github.com/Bingblop/ADB-Application-Manager/issues in the browser. Contact the developer: write a line, Send: your email app opens with To = the alias, subject ADB App Manager and the details at the bottom of the body; the developer's own address appears nowhere. With the details switch off they are absent. With no email app installed: the sheet says so and Copy message works.
- [ ] Morphe Helper > downloads in the background (v7.10.7): in the in-app browser start a big download (a few hundred MB), press Home and switch the screen off for a few minutes: a "Downloading ..." notification shows the progress and it keeps going (check the size when you come back). Pull the notification down: Pause all pauses it (it is still in the list, Resume continues from where it stopped), Cancel removes it. Let one finish with the app in the background: a note "... is saved" replaces the progress and opens the app when tapped. Turn off "Keep downloading in the background" under the list: leaving the app now stops the download. On Android 13+ also try with notifications turned off for the app: the download still goes on.
- [ ] Settings > My Themes (v7.10.7): make a look (a palette style with your own source color and a color tweak, Pure black on), Save current theme, give it a name; make another look, save it; tap each one: the whole app switches, Light/Dark stays as you had it. Rename, Update (after changing the look) and Delete work; close and reopen the app: the themes are still there.
- [ ] Settings > Palette Style (v7.10.6): tap each of the nine styles with Dark and then Light appearance: the whole app changes color, text stays readable, nothing looks broken (cards, badges, bottom sheets). Pick a source color (the circle): the tiles and the app follow it; Automatic goes back to the wallpaper's color (on Android 12+, change the wallpaper and come back: the colors follow). Close and reopen the app: the style and color are still there. Tap a curated palette: it takes over.
- [ ] Connected Devices > Reconnect all and timings (v7.10.6): with two devices dropped (two watches, or a watch and a tablet) a "2 devices not connected" line with Reconnect all appears; press it: each is tried in turn and the toast counts them. In Settings > Reconnecting devices set the timings to `0, 5` and drop a device: only two attempts are made, about 5 s apart. A bad entry (`0, x`) is refused in words. Reset restores `0, 8, 25`.
- [ ] Connected Devices > a dropped device (v7.10.5): connect a watch over Wi-Fi, then switch its Wi-Fi off: a toast "Lost the connection" and a "Not connected" card with Reconnect and Forget appear within a few seconds. Switch Wi-Fi on: the automatic attempts (at once, 8 s, 25 s) bring it back, or Reconnect does. Switch Wireless debugging off and on (new port): Reconnect finds the new port. Unplug a USB device and press Reconnect: it says to check the cable. Disconnect in the device menu leaves no card.
- [ ] Morphe Helper > Downloads from the browser (v7.10.4): start a big download in the in-app browser, close the browser: the row keeps going in Helper (size, speed, time left). Pause: the bytes stop; Resume: it asks for the rest only (the size goes on from where it was, not from 0). Turn Wi-Fi off mid-download: the row says it stopped and how far; Wi-Fi on, Retry: it goes on. Close and reopen the app with a paused row: it is still there and resumes. Cancel removes it; Use this file on a saved one gives "APKM, N parts".
- [ ] Terminal > Full screen: covers the app, the screen fills the room, the on-screen keyboard does not hide the input line or the extra keys, Back comes out (v7.10.3).
- [ ] Any bottom sheet: pull the bar at its top up (bigger), down (smaller, then closes) (v7.10.3).
- [ ] Apps tab: the Action Button menu at the end of the row opens a sheet with a title and a line per choice; App Stores: Mihon's description reads as text; Morphe community finder: icons are clean (v7.10.3).
- [ ] Morphe Helper: Settings > Try the other sources automatically; This version with `18.0.3.954559732-release-arm64-v8a` for Gboard finds the release (v7.10.3).

**Header, banner, guide, looks**

- [ ] The header has no icon: the title "Full ADB/Root System Manager" starts at the left edge in two fitted lines. Two smaller uppercase lines show the manufacturer/model and Android version, plus One UI on Samsung. The gear and working-mode badge have matching heights. Try a narrow screen and a larger system font: no title, subtitle, or button is cut off.
- [ ] Without a working mode the orange Read-Only banner shows with an X in its top right corner; the X puts it away; closing the app completely and opening it again shows it again.
- [ ] About, **Help Guide** (next to GitHub): opens without a network, with 80 topics in six groups. Tapping a topic opens it on its own; Previous/Next and Contents work. Search shows matching topics with snippets. Back closes the guide, and reopening continues where you were.
- [ ] Bottom sheets show a small grab handle; scrollbars are thin; a tab fades in when opened. Nothing is cut off or moved.
- [ ] **Granular Color Pickers**: all seven buttons open the popup. Both Colors and Custom have the fixed # prefix; typing or pasting `#00dfff` produces `00DFFF`. Hue/saturation/brightness sliders and both hex boxes stay in sync. Apply is disabled until six hex digits are present. Cancel and Android Back change nothing; Apply and Reset tweaks work in both light and dark mode.

**Latest Application Manager changes**

- [ ] The button row has Share CSV, Profiles, Backups, and the Action Button chooser, with no Export button. The Versions pill and versions on app rows are gone; version information is still available in the app menu.
- [ ] Choose an Action Button in the Apps tab: row actions change and Settings shows the same choice. Change it in Settings and check the Apps chooser. Force-close and reopen the app: both choosers and the row actions restore the saved choice.
- [ ] With the UAD-NG list downloaded, listed apps show their classification chip on the row. Tap one: its description and dependencies open without selecting the row or opening the app menu. An unlisted app has no chip, and the chip in the app menu still works.

**The app menu and Settings**

- [ ] Open an app's menu: the pills are Permissions, App Ops, Activities, Components, Manifest, Raw, Features, Configurations, Signatures, Libraries (swipe the row sideways; the picked pill slides into view). No "Copy version" chip. **Activities** lists the screens (LAUNCH, DISABLE / ENABLE); **Components** lists receivers, services (STOP) and providers.
- [ ] **Features**: a game shows OpenGL ES with "on this phone"; the Not on this phone chip lists what the phone lacks. **Configurations**: minimum / target / compiled Android versions are right for an app you know. **Signatures**: v2 / v3 are lit for a recent app; the SHA-256 matches what `apksigner verify --print-certs` (or the app's maker) says; tapping it copies it. **Libraries**: a Flutter or game app lists its .so files under arm64-v8a.
- [ ] Uninstall, Clear Data and Rem Updates in the app menu ask first (Cancel changes nothing); Freeze and the others do not. The batch Freeze question reads "Confirm Freeze Selected Apps".
- [ ] Settings, last card: **Trim Caches in All Applications**. Without a working mode it shows a padlock. With one: the question, then "Trimming caches…", then a report with free space before and after (a heavily used phone frees a few hundred MB or more). Apps, logins and files are intact.
- [ ] The permissions sheet has **Install unknown apps** (Allow opens Android's screen, or grants at once with a working mode). Working Modes, Root: with Magisk / KernelSU set to deny this app the tag reads DENIED and the badge says "not ready"; after allowing it and re-selecting Root it reads ACTIVE.
- [ ] The small **?** on the first card of a tab (Debloater, Installer, Files, Connected Devices, Settings, ...) opens the Help Guide at that tab's topic.
- [ ] **Connected Devices**: Uninstall a system app on a watch that refuses with "only root can delete system app" (a Samsung watch, for example): the app is removed anyway and `/data/local/tmp` has no `adbam_unin_*.jar` left (`adb shell ls /data/local/tmp`). Reinstall brings it back.

**APK variants (needs one 32-bit phone and one 64-bit phone)**

- [ ] The release has three APKs and a `SHA256SUMS.txt` that lists them. `-arm64-v8a` installs on the 64-bit phone and its working modes work as before.
- [ ] `-armeabi-v7a` installs on the 32-bit phone, ADB over TCP / Wireless Debugging connect (the 32-bit adb starts), and Connected Devices can list devices. Write down the model and Android version if it does not start.
- [ ] `-universal` installs on both. The self-update (About, Check for update) offers the matching variant.
- [ ] A phone without a working mode: **APK Installer**, Authorizer **No privilege**, installs a small APK (Android asks once to allow installs from this app).

**Morphe Patcher** (the engine runs in a process of its own, `:morphe`, and has only been run on a computer so far: this is its first test on a phone. A phone with 6 GB or more is best)

- [ ] The tab Morphe Patcher is in the tab bar after Connected Devices. The card says it is powered by Morphe, Morphe Manager and Helper for Morphe (GPL-3.0). The yellow note "does not contain the Morphe engine" is NOT shown (it is shown by a build without the engine).
- [ ] Apps page: **Download Morphe Patches** (about 11 MB) downloads, "Reading the patches..." appears for a few seconds, then YouTube, YouTube Music and Reddit are listed (the ones installed here marked Installed). If it stays empty, Sources shows the reason on the card ("Could not be read": copy it).
- [ ] Open an app that is installed and has patches. The patch page shows its version with "Supported version" when the version is one of the listed ones. Simple lists the default patches; Advanced lists App-specific and Universal patches, the gear of a patch with options opens its options.
- [ ] **Patch** an app with one or two small patches (try a universal one on a small app of yours first, for example "Change installer source"). The steps run, the log scrolls like logcat, a notification "Patching ..." with Cancel stays while the app is in the background, and it ends with "is patched" and a file name. Write down: the time it took, the heap line of the log ("Heap limit: N MB") and whether the phone stayed responsive.
- [ ] With **Install when finished** on and a working mode: the app installs silently. If the patched app has the same package as an installed one, Android refuses (another signature): the question "Uninstall the installed copy first?" appears, and after OK it installs. With no working mode the system installer opens.
- [ ] **Delete the APK after installing** (try it once): the patched APK is gone from Patched APKs and from Download/Morphe Patcher after the install; with it off both copies stay (Keep patched APKs in: Both).
- [ ] A big app (YouTube, about 150 MB) with its supported version: it either finishes or says that it ran out of memory (not a silent crash). Cancel in the notification and in the page stops it and nothing is saved.
- [ ] A split app (one installed from Play with config splits): the log says "Bundling N split APKs" and the patch works on it.
- [ ] **Morphe Helper**: Aptoide or Uptodown, package of a small app, version empty: Newest downloads, shows the SHA-256 and the install and patch buttons. All versions lists versions. A source that cannot be downloaded says so and Open the site opens the browser. With a VirusTotal key (Settings, Test): Scan shows clean or the engine counts.
- [ ] **Community**: the finder loads (about 2 MB), By app and Bundles list, a category filters, "only installed apps" keeps your apps, **Add** on a bundle asks first and then the bundle shows Installed in green. **Sources** lists it, Turn off removes its apps from the Apps page, Delete removes it.
- [ ] **Patched APKs**: the run is listed with its patches; Install, Share, Save to Downloads and Log work; Delete removes the file.

**SD Maid** (the tools have only been run on a computer against test folders: this is their first test on a phone. Start with the general setting **Dry run** on)

- [ ] The tab SD Maid (called SD Maid SE before v7.10.10) is in the tab bar after Morphe Patcher. The card names the working mode and lists the data areas (Public storage, Public app media, Public app data, Public app resources, Private app data, Portable storage) as available, through the working mode, or not available. With Root, Private app data is available; with ADB or Shizuku, Public app data is available through the working mode on Android 11 and newer (without any mode it is not).
- [ ] **SystemCleaner** Scan (with Dry run on): the progress shows the step, a bar, a count and the folder; the result says "N filter match(es)" and a size; Details lists the filters, a filter opens to its paths, unticking an item changes the number the Delete question asks about. Delete with Dry run on reports and deletes nothing (the files are still there). Turn Dry run off, make a file called `test.log` in a folder in Download, scan again: the Log files filter lists it and Delete removes it; it is in History with its path.
- [ ] **AppCleaner** Scan: apps are listed by size with a System tag where it applies. Delete without the accessibility service clears what file access reaches and, with a working mode, trims all caches ("Deleting caches using ADB access"). Then enable the accessibility service (the card: Enable the service, consent, turn it on in Android's settings; on Android 13 and newer first "Allow restricted settings" in this app's App info) and Delete again: the screen is covered, App info of the apps opens one after the other and Clear cache is tapped; Cancel on the cover stops it; turning the screen off stops it with "stopped because the screen was off or locked". Write down the phone, the Android version and the language for every run: the steps follow the system's settings texts and may fail on a maker's skin.
- [ ] **CorpseFinder** Scan: uninstall a test app that left its folder in Android/data or Download, then scan: its remnant is listed with its area and a risk. An installed app's folder is NOT listed.
- [ ] **Deduplicator** Scan: copy a file of more than 512 KB twice into Download; the set is listed with one copy marked Kept. Delete keeps exactly one copy.
- [ ] **Two at a time**: Scan all starts all four; two work, two say In queue; the live status at the top follows them while you scroll; Cancel on one stops only that one. The notification "SD Maid SE" shows the progress with a Cancel button while the app is in the background.
- [ ] **1-tap scan and delete**: the checkbox of a tool asks before it turns on; with it on, Scan scans and deletes without a list and History has the run. (Use it on SystemCleaner with Dry run on first.)
- [ ] **Exclusions**: exclude a path from a result with Exclude (Undo works), create an app, a path and a segment exclusion in the manager, remove one, Restore defaults, Export (clipboard) and Import. An excluded folder is never listed or deleted by the tools you chose.
- [ ] **History**: shows the reports and, for a report, the deleted paths; Reset all asks and clears it.

**Connected Devices** (needs a Wear OS watch, or a second Android phone, on the same Wi-Fi)

- [ ] On the watch: Developer options, ADB debugging and Debug over Wi-Fi on. **Add device**, Wi-Fi tab: Pair (address:port and the 6-digit code from the watch), then Connect. The watch shows in the picker as Connected with its model, Android version, screen, battery and Wear OS.
- [ ] **Nearby**: Scan lists the watch; Pair / Connect from the list work.
- [ ] **Bluetooth** (a watch paired with the Wear OS app, Debugging over Bluetooth on in the app and on the watch, ADB on in this app): Connect over Bluetooth adds a device with the Bluetooth link badge; allow it on the watch.
- [ ] A device that has not allowed this phone says so; after tapping Allow on it and Refresh it becomes Connected.
- [ ] **Apps**: the list loads, filters and counts work, Disable then Enable a user app, Uninstall and Reinstall a system app you do not need (then put it back), Pull APK saves into Download/ADB App Manager/Devices.
- [ ] **Send**: an APK, an APKS or XAPK, and "From this phone" install on the watch; the progress bar moves, Stop works, every package shows Installed or the reason. Bluetooth sharing opens the Bluetooth app's picker.
- [ ] **Console**: `getprop ro.product.model` answers; `adb pull /sdcard/x /sdcard/Download/` runs through adb; `adb kill-server` is refused.
- [ ] **Logcat**: Play follows the log, Clear empties it. **Files**: browse, view a text file, pull one, send one here, rename, delete a test file. **Hidden Settings**: read, change and put back one value you can see (screen_off_timeout).
- [ ] **Display**: pick another density, Apply: the watch changes at once and Keep / Put it back counts down 15 s; leave it alone and it goes back by itself. Reset to default and the size box work.

## 1. Start and Apps list (v7.9.18, v7.9.19)

- [ ] Force-close the app, open it again: it is on **Application Manager** with the **All Apps** pill lit (no box lit), even if you had left another filter on.
- [ ] Each row shows the app's icon on the left, no letter box, and the checkbox sits to the right of the "..." button.
- [ ] Tap a row's name: it selects. Tap the checkbox: it selects. Selecting shows the round checkmark button.
- [ ] **Hold** a row (not the icon, not a button) for half a second: you feel a tap and that app's menu opens. Letting go does not also select the row. No text gets highlighted.
- [ ] **Hold an icon**: "Icon saved to Download/ADB App Manager/Icons" and the PNG is there.
- [ ] Share CSV, Profiles and Backups have an outline and light up when pressed; every button gives a short vibration. Export was removed in v7.10.0; the Action Button chooser is at the end of this row.
- [ ] Hold the round checkmark button: the selection clears.

## 2. Icon pack (v7.9.17, v7.9.18)

Needs an icon pack installed (Nova, ADW, Apex or Go style).

- [ ] Settings, **Icon pack** lists the installed packs. Choosing one redraws the list's icons with it.
- [ ] An app the pack does not cover shows its own icon on the pack's background/shape when the pack has one.
- [ ] "Default icons" goes back. Updating the pack or an app refreshes its icon.
- [ ] **Clear icon cache** says it cleared and the icons come back.

## 3. Action Button (v7.9.18)

Settings, **Action Button**, after Icon pack. For each choice, look at the icon on a row and tap it:

- [ ] **App Settings**: Android's App info opens.
- [ ] **Force Stop**: the app stops (a toast says so).
- [ ] **App Launcher**: the app opens.
- [ ] **Enable / Disable**: the icon is a check circle on a frozen app and a block sign on a normal one; tapping flips it and the icon follows.
- [ ] **Install / Uninstall**: Uninstall asks first; an uninstalled app shows the install arrow and Reinstall brings it back.
- [ ] **Unsuspend / Suspend**: same idea; the icon follows the state.
- [ ] **Permission Manager** and **Activity Launcher**: see sections 4 and 5.
- [ ] **None**: no extra button on any row; the "..." button stays.
- [ ] The button also shows on **UAD-NG Debloater** rows and follows the app's state there.
- [ ] **Hold the button**: a sheet of actions for that app opens (the hold does not also run the button). Picking one runs it.
- [ ] Settings, Action Button, **Hold menu**: move actions with the arrows, switch some off, Reset. The hold sheet follows. The last action cannot be switched off.

## 4. Permission Manager (v7.9.18)

- [ ] Each permission shows its label, description, group, protection level, granted or not, and whether it can be changed.
- [ ] Filters (Granted, Denied, Changeable, Locked) and search work.
- [ ] Tap a changeable permission's button: it grants or revokes (check it in Android's own app permissions).
- [ ] **Grant all shown** / **Revoke all shown** act on exactly what the filter shows, ask first, show progress, list any failure.
- [ ] The **App Ops** chip lists the app's app ops; Allow / FG / Ignore / Deny / Reset change them (check with `appops get <package>` in the terminal). A permission with an app op shows "App op NAME: mode".

## 5. Activity Launcher (v7.9.18)

- [ ] Activities show exported or not, enabled or not, and the permission they need.
- [ ] **Launch** on an exported activity opens it; on an unexported one it works in a working mode or says why not.
- [ ] Tick several, **Disable selected**: asks first; they show "disabled". **Enable selected** brings them back.

## 6. Batch menu (v7.9.18, v7.9.19)

Select three or four apps you do not mind changing, open the round checkmark.

- [ ] The menu is taller, has a fourth row (Batch Ops, Show Apps, Command), scrolls if needed, and has a **X** at the top right (like the app menu) that minimizes it. "Clear" clears the selection.
- [ ] **Show Apps** lists the selected apps; **Remove** takes one out (its checkbox in the list unticks).
- [ ] **Batch Ops**: tick two app ops with different values, **Apply**: progress bar and Stop work, the result lists each app. Check one app with `appops get <package>`.
- [ ] Presets: **Privacy lockdown** ticks ten ops; apply it, then **Undo Privacy lockdown** puts them back to default.
- [ ] Save your own preset, rename it (pencil), duplicate it (copy icon), delete it (X).
- [ ] **Command**: type `echo $package`, the preview shows the first app's name. Run: each app's output shows its own package name.
- [ ] **Dry run**: switch it on, run `pm clear $package`: it lists the commands and runs nothing. "Run it for real" reopens the sheet.
- [ ] **Run it on** a saved list (from Saved Applications): only the list's apps that are installed are used, the rest are counted as skipped.
- [ ] Save, rename, duplicate and delete a saved command.
- [ ] A results dialog's **Share CSV** opens the share sheet with a .csv that opens in a spreadsheet.
- [ ] **Run again** repeats the run; **Run again on the N that failed** appears when something failed (try `am start -n $package/.Nope`).

## 7. Export and import of presets and commands (v7.9.19)

- [ ] Batch Ops, **Export presets** opens the share sheet with a .json file; Command, **Export saved commands** does the same. Save one to Downloads.
- [ ] **Import...** opens Android's file chooser; picking that file again says "already here" and adds nothing.
- [ ] Delete a preset, import the file: it is back. Rename one in the app, import the file: the old name comes back as "(imported)".
- [ ] Import a text file that is not ours: "That is not a presets file from this app".

## 8. Press and hold guide (v7.9.18)

- [ ] Settings, last card, lists what can be held. Try each item it lists (app row, icon, Action Button, checkmark, file row, found APK, 1/0 setting, overlay row) and check it does what the guide says.

## 9. File Manager and added storage (v7.9.18)

- [ ] The toolbar has no Up button; the ".." row at the top of a folder goes up. Buttons have an outline and vibrate.
- [ ] **Add storage** an SD card or USB drive (or another app's folder). Open a folder, create a folder and a file, rename, delete.
- [ ] Select several items there (hold a row), **Copy**, open another folder, **Paste**: the copies appear. **Move** works too. Try a copy from phone storage into the added storage and back.
- [ ] A name clash asks Replace / Skip / Keep both. Stop works on a big copy.
- [ ] Open a picture, a PDF, a font and a zip from added storage: each shows in its viewer. **Open with** and **Share** work.
- [ ] **Extract** a zip from added storage: it goes to Download.
- [ ] Search inside added storage (a name, then `ext:jpg size:>1m`): it finds files. A `content:` search says it is skipped there.

## 10. Earlier v7.9.x features (quick pass)

- [ ] **Expressive Animations** (Settings, Motion): on by default, sheets and buttons spring a little; off makes them plain; the phone's "remove animations" setting always wins.
- [ ] **Termux** (Command-Line Interface, Termux shell): "Match my Termux environment" makes your aliases work; **Sync with Termux** opens a Termux session and grants storage access.
- [ ] Batch actions (Freeze, Enable, Suspend, Force Stop...) show progress and Stop, and the page stays smooth.
- [ ] The false "failed" fix: launch an app whose name contains "error" or "failed": no failure is reported.

## 11. Filters (v7.9.20, v7.9.21)

- [ ] The top of Application Manager has Installed, Running and Bloatware on one row, then two boxes below: **Enabled | Frozen** and **3rd Party | System**. Each half shows its own count.
- [ ] Tap **Running**, then **3rd Party**: the list shows your running user apps, both buttons are lit, and a line under Sort names the filters with a **Clear filters** button.
- [ ] Tap **Enabled**, then **Frozen**: Frozen replaces Enabled (a pair is one filter); tap it again and neither is on.
- [ ] Tap **Enabled** then **System** (and the pills in the row below): you see only your enabled system apps; boxes and pills light together.
- [ ] **Clear filters** turns every filter off and the **All Apps** pill lights again. With only one filter on, the button is not shown.
- [ ] Debloater: the "Also show only" row combines with Removal, vendor and state; with two or more on, **Clear filters** appears and clears that row only.
- [ ] Saved Applications: pills apply inside each list (counts update, lists with no match hide); **Clear filters** shows with two or more on and brings every list back.

- [ ] Freeze an app, then uninstall it (Debloater, Uninstall): it leaves the **Frozen** box and filter and shows under **Bloatware (Uninstalled)** only. Reinstall it: if it is still disabled it is **Frozen** again.
- [ ] Files, search for a word (for example `jpg`), then tap **Images**, **Over 10 MB**, **Last 7 days**: the list narrows, the count says how many are shown, and **Clear filters** brings everything back.
- [ ] Added storage (SD card or USB): search `content:word` finds text files that hold the word; `archive:` says it is skipped.
- [ ] Settings, Lists, **Remember my filters** on: turn on Running + 3rd Party, force-close the app, open it again: the same filters are on. Off: it opens on All Apps.

- [ ] With no working mode on, open an app's menu: the permission toggles, the App Ops modes (on the mode that is set) and every Enable / Disable, Stop and Launch button of activities, services, receivers and providers show the lock and look dimmed. Turn a working mode on and open the menu again: only install-time permissions keep the lock.

## 12. Language, font and File Manager core (v7.1 to v7.4)

These came before the rest of this list and were only checked in the browser. They need a real phone because they use the system font list, the file access, the foreground notification and the archive libraries.

- [ ] Settings > Language: the drop-down at the very top lists the languages. Pick one: the tabs, buttons and Settings change at once; app names, package names, file names and logs stay as they are. Pick "System default": it follows the phone's language again after a restart of the app.
- [ ] Settings > Font: the system font is the default. **Choose a font file** (a .ttf or .otf from Downloads): the whole app uses it; **Find fonts on this phone** shows a progress bar and a list. Removing the choice puts the system font back.
- [ ] File Manager: every folder starts with a **..** row that goes up one level. **+ File** and **+ Folder** make them; a name with a slash is refused. **Show hidden files** shows the dot files and hides them again.
- [ ] A folder of pictures shows thumbnails (Show thumbnails on); **Clear thumbnail cache** empties them.
- [ ] Copy a file onto a name that is already there: the dialog offers Replace, Keep both and Skip, and does what it says. Delete asks first.
- [ ] Tap a picture, a PDF, a text file, a .ttf and a zip: each opens in its viewer. **Open with** hands the file to another app; the editor saves a text file back.
- [ ] Search (the bar at the top of the File Manager): a name, `ext:apk`, `content:word` and `archive:name` each find the right files; the pills under the results (kinds, size, age) narrow them.
- [ ] **Extract** a zip, a 7z and a tar.gz: a progress notification shows the file, the time left and a Cancel button; pulling the notification down while it works does not stop it. A password-protected zip or 7z asks for the password and refuses a wrong one.
- [ ] Open a .rar: it can be read and extracted, not changed. Compress a folder to zip and to 7z: the file opens on the phone.
- [ ] Installer > Find APKs on this device: a progress bar runs; duplicates and older versions are flagged; delete selected asks first and can be undone.

## 13. Trackers (v7.11.0)

- [ ] Apps tab: tap the **Trackers** pill. A message says how many apps are read, the pill counts "12/150" and ends on a number; the list shows only apps with trackers. A second tap off and on is instant.
- [ ] Open an app you know uses ads or analytics (a free game, a news app): the **TRACKERS** chip under UAD-NG shows an orange number; the list names trackers such as Google AdMob or Firebase Analytics, each with a web address that opens. An open-source app from F-Droid usually shows a green 0.
- [ ] An app that is only a launcher stub or a preinstalled system app may show a grey ? or 0: both are fine; it must never crash or hang. Updating an app and opening it again re-reads it.
- [ ] Turn the phone to airplane mode and tap the pill: it still works (nothing is downloaded).

## 14. Hidden Settings backup and Update the tracker list (v7.11.1)

- [ ] Hidden Settings: change two harmless settings (for example Global > `stay_on_while_plugged_in`), open **Changes**, tap **Back up changes**: the share sheet offers a .json file; save it. Change both settings again by hand, then **Restore…**, pick the file: the question names both settings; after Yes they have the backed-up values and appear in the Changes list again. Restoring the same file once more says everything already matches.
- [ ] Open the file in a text app and break a name (put a space in it), restore: that setting is skipped, the others work. Pick a file that is not a backup (a photo, a presets file): a message says it is not a Hidden Settings backup.
- [ ] Apps > any app > TRACKERS chip > **Update the list**: the button says Updating…, then "Tracker list updated: N trackers" (about 430), and the chip reads the app again. In airplane mode it says why it failed and the list keeps working.

## 15. Ask the agent about an unlisted app (v7.11.2)

- [ ] Open an app the UAD-NG list does not rate (a small vendor system package): an **Ask agent** button (no symbol on it) stands where the chip would be, under Share and a little to the left (since v7.12.0; before that the chip read UAD-NG Not listed · Ask). An app the list rates (Bixby, a Google app) still shows its level and no Ask chip.
- [ ] Tap the button with no default agent chosen (v7.12.5): the window opens and, after "Looking it up on the web…", shows "What the pages say" with passages and a Sources row (v7.12.6 built-in web lookup, free, no key); the line above says which words were searched. With the phone offline it says something went wrong instead of hanging. Crawl4AI and Browser Use (v7.12.6): in the Command-Line Interface tab choose each, paste a real free-tier key and tap **Test and save**; the first becomes the Default Ask Agent when **No default** is chosen. While Browser Use works the window shows a progress bar that fills by steps and a **Stop** button: Stop shows "Stopped." at once. Own server (v7.12.6): in the Command-Line Interface tab choose **Own server (OpenAI-compatible)**, enter your computer's LM Studio / llama.cpp / Ollama address (like `http://192.168.1.20:1234/v1`) and connect; choose it as the Default Ask Agent and press an Ask agent button: the answer comes from your server. APK Installer/Updater (v7.12.8): see section 18 below. Ask agent windows (Ask agent, log entry, Hidden Settings): **History** lists answers, **Pin** keeps one when more than 12 pile up, **Clear history** keeps pinned ones. Each agent's Connect sheet has a **Test** button. Connected Devices: **Disable** blue, **Uninstall** red. Settings, **Test the default agent** (v7.12.7): with an agent connected it answers one sample question and says how long it took; with none chosen it says there is nothing to test; **Stop** ends it. The Ask agent window's **History** lists past answers; tapping one shows it again without a request; **Clear history** empties it. In Connected Devices, **Enable** and **Reinstall** (rows and the batch bar) are green. A log entry's and a hidden setting's **Ask agent** show a progress bar and **Stop**. Settings, **Row tints**: Off / Subtle / Normal change the Apps rows at once; the app menu's **Enable** and **Reinstall** are green. Press an Ask agent button: Crawl4AI shows an answer with its sources within a few seconds; Browser Use shows "Browsing the web…" and then the answer after a minute or two. **Web search** works, and the **Open Settings** link opens the Default agent card. With an agent connected: the answer streams in, ends in a verdict, and **Copy the answer** copies it. **Web search** opens a search for the package.

## 16. Logcat, recording, default agent (v7.12.0)

- [ ] Logcat > set the level to **Error** and press **Play** while using other apps: lines stay on the screen (they used to vanish after two seconds). A pause with nothing new does not empty the view. Changing the level starts a fresh view; **Clear** empties it.
- [ ] Logcat > **Record to file**: the button becomes **Stop recording** with a growing line count; leave the tab, use an app, come back; tap **Stop recording**: the file opens as a still picture. Search for a word (the match is marked), hide a level with the key, go to **End**, tap an entry (Copy / Ask agent / Web search), **Copy shown**, **Save** (the file is in Download/ADB App Manager), **Share**.
- [ ] Logcat > **Recordings** lists it with its size; tap it to open it again; the cross deletes it (**Delete** in the picture does too).
- [ ] Settings > **Default Ask Agent** (called Default agent before v7.12.8) sits under Language. With **No default** chosen, press **Ask agent** on a Hidden Setting row: the window opens with the free Web search and an Open Settings link (which outlines the card for a moment); nothing is sent. Choose an agent that has no key: the line under it says it is not connected; the button still leads to Settings.
- [ ] Connect an agent (key in the Command-Line Interface tab), choose it as the default, press **Ask agent** on a hidden setting row, on a permission in an app's menu, on an overlay row and on a Task Manager process: a box shows what is sent and the answer streams in; **Ask again**, **Web search** and **Copy the answer** work.
- [ ] App menu of an app the UAD-NG list rates: the chip reads only the level (no "UAD-NG" text). An unlisted app shows **Ask agent** in its place.
- [ ] SD Maid: the four tools come first, then **Leftover data of uninstalled apps**, then **Trim Caches**.

## 17. The Installed box (v7.12.5)

- [ ] The first box reads **Installed** and its count leaves out the uninstalled apps. The **All Apps** pill still counts every app.
- [ ] Tap **Installed**: only installed apps are listed. Also tap **Bloatware (Uninstalled)**: every app on the phone is listed and both boxes are lit. Tap **Installed** again: only the uninstalled apps remain.
- [ ] With Installed and Bloatware (Uninstalled) lit, tap **Running**: Bloatware (Uninstalled) turns off (a running app is installed).

- [ ] App menu buttons (v7.12.5): **App Info** now stands right after **Clear Data** and **Uninstall** right after **Rem Updates**; **Uninstall** is tinted red and **Freeze** blue.

- [ ] First launch in the phone's language (v7.12.5): on a phone set to Deutsch (or Español, Français...), install the app fresh: it starts in that language, and the first-launch **Allow a few permissions** sheet has a **Language** drop-down set to it; pick English there: the whole app switches at once. On a phone in a language the app does not have it starts in English. The sheet opened later from About has no Language drop-down.

## 18. APK Installer/Updater, KeyStore, Default Ask Agent and Authorization Manager (v7.12.8)

- [ ] The tab bar shows **Installer/** over **Updater**, and the Feature List calls it **APK Installer/Updater** (row 3). The **App Stores** tab has its old name and holds only ShizuStore, GitHub, F-Droid, Orion and your own stores. No tab or message anywhere still uses the short-lived combined name (Stores and Updater in one tab).
- [ ] Tap the tab: two boxes at the top. **Application Installer** (left) is shown every time you tap the tab, with the old APK Installer cards. Tap **Application Updater** (right): the cards come in this order: Find and download an app, App update, Updates Available, Play Store apps (via Aurora), the update rows, KeyStore. Leave the tab and tap it again: it opens on the Installer box.
- [ ] With updates waiting, the tab label reads like **Installer/ Updater (3)** and the Application Updater box shows the same number. An app's **Update** button in its menu, About > **Check for update** and the **Download** action open the Application Updater box. Switch the tab off in the Feature List: those buttons say "APK Installer/Updater is turned off. Turn it on in Settings, Feature List."
- [ ] Find and download an app, search by package: type `org.mozilla.firefox` and tap Search: one row with the name and package (and "Installed: version" if you have it). Search by name: type `firefox`: your installed apps that match come first, then matches from Aptoide.
- [ ] Source APKMirror, **Version** empty, tap **Download**: the bar fills, then a card shows package, version, format, size and SHA-256 with **Install** and **Share**. Type an older version (for example one release back) and Download: that version arrives. Type a version that does not exist: with **Allow closest nearby version** on, the nearest one arrives; with **Exact version only** on, it is refused with a message.
- [ ] **Versions** on a row lists what the source has, with a **Download** per version. Switch the source to Uptodown, Aptoide, **F-Droid (open source)** and **IzzyOnDroid (open source)** and repeat one download on each: Download and Versions work, and the file is named like `package_versionCode.apk`. F-Droid's note says the file is F-Droid's own build. **Google Play** only offers **Open the page**, which opens the Play Store page; nothing is downloaded.
- [ ] Untick **Allow split APK** and download an app that APKMirror ships as a bundle: you get a single APK (or a message that none exists). Tick it again: a bundle (APKM, APKS or XAPK) is accepted and the card says its format.
- [ ] When APKMirror asks for a browser check, the message offers **Open it here, in the app** and **Open on the web**. The first opens the app's own browser: pass the check, download the file there, and it is saved and handled like a normal download (card, scan, copy, install).
- [ ] **Install on download** on, working mode on: after the download the app installs by itself. With no working mode, Android's own install screen opens. Install over an app signed with another key: the app offers to uninstall the installed copy first and says its data is deleted; refuse, nothing changes.
- [ ] **Save downloads to** > **Downloads/App Updater**: the file is in that folder. > **A folder I choose**: pick a folder in Android's picker; after the download the file is in Downloads/App Updater and a copy is in your folder. > **This app's own storage**: no file appears in Downloads.
- [ ] **VirusTotal scan** on with a key in Settings: after the download the result of the hash lookup is shown. With no key, note what it says; the download itself should still work.
- [ ] **Use the Default Ask Agent to search as well** on, with a connected agent: package names it names are added to the results. With the box off, the agent is not asked.
- [ ] A wrong package name (for example `com.example.nothing`) or a file that is not for the asked package is refused with a message; nothing is installed.
- [ ] KeyStore card: **Export KeyStore** writes `Downloads/Morphe Patcher/morphe.keystore`. **Import KeyStore** with that file: it asks for the password (leave it empty for a Morphe one) and says it worked. **New key** asks first; after Yes, a newly patched app is signed with the new key (Android refuses it over an app patched with the old key). Import the exported file again to get the old key back.
- [ ] Settings > **Default Ask Agent** reads Perplexity on a fresh install. The list is grouped (No default, Web research (recommended) with Perplexity, Exa, Browser Use and Crawl4AI, Chat models with an API key, Free Open-Source) and has no Cursor, Copilot or OpenCode. With no Perplexity key, **Ask agent** on a Hidden Setting row answers with the free web lookup (no key). Choose **No default**, leave Settings and come back: it is still **No default**. **Test the Default Ask Agent** works once a key is set.
- [ ] About > **Authorization Manager**: the card shows a code of five groups of five letters and digits (no I, L, O or U) and says nothing accepts it yet. The copy button puts it on the clipboard (paste it somewhere to compare). Close the app completely and reopen it: the code is the same. The circular refresh button asks first; after confirming, a different code shows and stays after a restart.
- [ ] Exa (Default Ask Agent): in the Command-Line Interface tab choose **Exa**, paste a key from dashboard.exa.ai/api-keys and tap **Test and save** (one small search). In Settings > Default Ask Agent, Exa sits in **Web research (recommended)** right after Perplexity (order: Perplexity, Exa, Browser Use, Crawl4AI). Choose it and press an **Ask agent** button: the answer comes with its sources. Exa is not offered as a chat in the Terminal.

## 19. Security and stability changes, Dex optimization and Standby bucket (v7.12.9)

None of these has been run on a phone yet; this section is the first run.

- [ ] Private adb socket: connect in ADB mode as usual (Wireless Debugging or ADB over TCP) and open the Apps tab: the list loads. Then check which path the phone took, from a computer: `adb shell ss -ltn | grep 5042`. **Nothing listening** = the private socket is in use (the intended result). **Port 5042 listening** = this phone refused the socket and fell back to the old loopback port: adb works, but the old exposure is unchanged on this phone, so write it down as a separate result (phone model, Android version) rather than a pass.
- [ ] Keystore vault, upgrade: install this version over v7.12.8 with a GitHub token and a VirusTotal key already saved. Settings still shows the GitHub Token button ticked and the VirusTotal key card still reads "approved" (or asks you to test it once). Nothing asks you to type either again.
- [ ] Keystore vault, new value: enter a new GitHub token, then a new VirusTotal key and tap Test key: both work. Clear them: the button loses its tick and the key card says no key.
- [ ] Download safety: in the Updater download any app from APKMirror or F-Droid: the download completes and the SHA-256 card shows. Download a Morphe bundle: it completes.
- [ ] Install checks: install a downloaded app over the installed one from the same source: it installs. Try one signed with a different key (a test APK you have): it is refused with a message about the signer.
- [ ] VirusTotal upload: scan a small APK with the VirusTotal card: the hash lookup shows. If the file is unknown and an upload is offered, it uploads and a result comes back.
- [ ] Dex optimization, **space**: app menu > Dex optimization > mode **space** > Apply on one app: the result window says Done (or the phone's own message).
- [ ] Dex optimization, **reset**: choose **reset**: the Force switch is hidden and the note says the result depends on the Android version; the subtitle reads "Reset the dex optimization of ...". Apply: Done. Note the Android version and what `dumpsys package dexopt` (or `cmd package dump <pkg>`) says afterwards.
- [ ] Dex optimization, batch: select two apps, batch menu > Dex optimization > **speed-profile**: the progress bar moves and Stop works.
- [ ] Standby bucket, read: app menu > **Standby** on a normal app: the sheet opens with the bucket the phone reports (`adb shell am get-standby-bucket <pkg>` shows the same). On a phone with no working mode the sheet does not open and says why.
- [ ] Standby bucket, set: pick **rare**, Apply: the toast says "Standby bucket changed" and `am get-standby-bucket <pkg>` reports rare. Pick **restricted**, then **active**: same. On the current launcher or another exempted app the sheet shows the bucket greyed out and a set is refused with the phone's words.
- [ ] Task Manager: open the Task Manager tab, then close the app from Recents: the phone shows no leftover activity from the app (battery stats show no polling after closing).
- [ ] Command output in the log: on a release build, first clear the log from a computer (`adb logcat -c`; installing the update does not clear it, and lines from the previous version would look like a leak), then run a privileged action (for example Freeze an app), then `adb logcat -d -s ADBAppManager`. The tag lines show the mode and sizes of a command, not its text or output. (A debuggable build still logs the whole line, by design.)

## Report

Note the phone model, Android version, working mode and the item number of anything that failed, then open an issue
at https://github.com/Bingblop/ADB-Application-Manager/issues.
