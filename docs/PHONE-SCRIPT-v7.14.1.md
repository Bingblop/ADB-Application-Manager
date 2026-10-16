# Phone script for v7.14.1

About 60 minutes, one pass or fail per step. v7.14.1 is the 57 fixes of the full review of `main` (sections B to F) plus the changes the owner asked for after v7.14.0 (section G: the Stop apps notification, the Live updates permission, the Speed test section, the File Manager Refresh button, Installed | Uninstalled, the Gradient switch, the Row tints shortcut and the Updated 7d filter taken out). Nothing in it has been run on a phone by the author. Each step checks something that only a real phone can show. Any working mode will do unless a step says otherwise: section B needs Shizuku (step 4 Wireless Debugging), steps 8, 9 and 13 need Root, step 17 needs Standard, step 22 needs Android 16 or newer.

**Before you start:** install the `arm64-v8a` file of v7.14.1 over v7.14.0 (an update, not a fresh install). Have an SD card or a USB drive added as storage in the File Manager, a few test files you can delete, and a small user app you can uninstall and reinstall. For section G also have 30 or more user apps (any, for a quick list that takes a few seconds to stop), Notifications allowed for the app, and Termux or another way to make a file in Download.

## A. Start (1 minute)

1. Open the app. **Pass:** About says 7.14.1; your settings, saved lists, keys and pinned tabs are still there.

## B. Terminal (6 minutes)

2. Working mode Shizuku. In Console > Terminal tap **Real terminal**, choose **Working mode (ADB, Shizuku, Root)** in the list at the top, and type `ls`, then a few more commands. **Pass:** the app stays open and every key reaches the shell (SYS-1: it used to close the app).
3. In the Real terminal (shell: Working mode) type `sleep 300 &`, then `exit`. **Pass:** the line "[process ended …: press Enter to start a new one]" appears at once, without waiting for the `sleep` (SYS-2: the screen used to stay on a dead shell with no such line).
4. Working mode Wireless Debugging, connected. Make the app's adb link drop while Wireless debugging stays on with the same port (for example **Disconnect** in Working Modes, or swipe the app away and reopen it; leave Wireless debugging itself switched on), then open the Terminal with the shell set to Working mode. **Pass:** the shell opens without pressing Connect in Working Modes first (MA1-2). Turning Wireless debugging off and on gives it a new port, which the app does not look up, so that does not test this fix.

## C. Files and archives (8 minutes)

5. On internal storage make a folder `T` with `a.txt` in it; on the SD card or USB drive make a folder `T` with `b.txt` in it. Copy the internal `T`, open the card through its added-storage entry in the File Manager (not `/storage/…`), paste there and choose **Replace**. **Pass:** the card's `T` holds both `a.txt` and `b.txt` (MA3-1: `b.txt` used to be deleted).
6. Copy a folder of the card from its added-storage view and paste it into one of its own subfolders opened as `/storage/<card>/…`; then the reverse (copy from `/storage/<card>`, paste in the added-storage view). **Pass:** each is refused ("Can't put a folder inside itself") or ends; neither runs on and on, and Cancel stops a running copy (MA3-2).
7. Pack an APK into a plain `.tar` in shared storage (Termux, after `termux-setup-storage`: `cd ~/storage/downloads && tar cf test.tar some.apk`) and open `Download/test.tar` in the File Manager. **Pass:** it shows the `.tar` with one entry, the APK, not the APK's own files (ARC-1).

## D. SD Maid (8 minutes)

8. (Root) AppCleaner: in a test app's cache folder make a sub-folder with a file in it (for example `cache/sub/keep.bin`), add a Segment exclusion `sub/keep.bin`, scan, delete. **Pass:** `keep.bin` (and its folder) is still there afterwards (SDM-1). In Shizuku or ADB mode an app's cache folder is cleared whatever the exclusions say, so this step needs Root.
9. (Root) SystemCleaner: scan with Temporary system files on. **Pass:** nothing under `/data/user`, `/data/user_de`, `/data/media/<user>`, `/data/misc_ce` or `/data/misc_de` is listed (SDM-4).
10. Deduplicator with two search locations, `/storage/emulated/0` and `/storage/emulated/0/dcim`. **Pass:** a photo that exists once is not listed as its own duplicate (SDM-2). In the Deduplicator settings, under Deletion strategy, type `/sdcard/DCIM/` into **Preferred locations**; with a real duplicate in another folder, the copy in DCIM is the one kept (SDM-5).
11. With the app running in a work profile or a second user (otherwise skip): put `keep.txt` and an identical copy in `/storage/emulated/0/Download` (the main user's Download, the one the tools scan), add the exclusion `/sdcard/Download/keep.txt`, and scan with the Deduplicator. **Pass:** `keep.txt` is not listed, only its copy (SDM-3).

## E. Installer and backups (6 minutes)

12. Privileged install of an older version of an installed app. **Pass:** it fails with the downgrade advice; turn on Allow downgrade and press Install again: it installs without asking to pick the package again (MA1-1).
13. If you have a 32-bit app (an armeabi-v7a only app): back it up with app data (Root), uninstall it, restore the backup. **Pass:** the restore runs and the app opens with its data (INS-1). Otherwise skip.

## F. Speed test, Task Manager and permissions (6 minutes)

14. Task Manager > Network > Speed test on Wi-Fi. **Pass:** the ping is close to what another speed test app or `ping 1.1.1.1` shows, not 20 to 40 ms more (V1-1).
15. Start the speed test again and turn Wi-Fi off during the download. **Pass:** the test stops with "The network changed during the test." and no result is added to the history (V1-2).
16. Working mode Shizuku; stop Shizuku (or reboot without starting it), then open Task Manager. **Pass:** RAM shows real numbers (CPU and network show what they show in Standard mode, not a Shizuku reading of zeros), Processes shows the lock with "Needs ADB, Wireless Debugging, Shizuku or Root to list processes." instead of an empty list, and GPU says "Needs a working mode" instead of "Not available on this phone" (MA3-3).
17. Android 13 or later, working mode Standard (with ADB, Shizuku or Root the app grants Notifications by command and Android shows no dialog). Fresh permissions (or Notifications turned off in App info): Settings > Application Permissions, tap **Grant** on Notifications and close Android's dialog with Back. **Pass:** you stay in the app; App info opens only after a second refusal (V2-2).

## G. What is new (about 25 minutes)

18. **Stop apps shows its progress.** Saved Applications: make a list of 30 or more user apps and tap **Quick list** on it (it turns to "Quick list" with a tick). Add the **Stop apps** tile (swipe down twice, pencil, drag it into the panel). Open a few of the apps so they run, then tap the tile. **Pass:** a notification **Stopping apps** appears with the list's name, a line like "3 of 30 · Chrome" and a progress bar that moves; when the last app is done the notification goes away by itself and a message says "Force-stopped N apps"; the apps you opened are no longer running.
19. Put this app (ADB System Manager) and Media Picker (`com.google.android.providers.media.module`, shown under the System filter) into the quick list too, and tap the tile again. **Pass:** the run finishes without closing this app or its window, and the message counts them: "Force-stopped N apps, 2 left alone" (or "1 left alone" when one of them is not in the list). If the phone's WebView app (Android System WebView or Chrome, whichever Developer options > **WebView implementation** shows) is in the list as well, it counts as left alone too.
20. Run it again and, while the notification shows, pull the shade down and tap the notification's **body** (not Cancel). **Pass:** the app opens on **Saved Applications** with the **Edit Saved List** sheet of the quick list; the notification keeps counting behind it and the run finishes (the apps still get stopped); nothing in the list was changed by the tap.
21. Run it again and tap **Cancel** in the notification. **Pass:** the notification is gone at once, the message says "Cancelled after N of 30 apps" (it stops after the app it was on), and apps near the end of the list that were running are still running.
22. **Android 16 or newer only.** Run it once more and look at the status bar. **Pass:** if the phone shows Live Updates, a chip like "3/30" appears and the notification follows it; if it does not, it is the same plain progress notification (note the phone model; both are right). Then open Settings > Application Permissions: a **Live updates** row is listed (an older phone shows none). With Notifications turned off in App info and the working mode Standard, tap **Grant** on Live updates. **Pass:** Android's own Notifications question comes first; after you allow it the phone's Live updates screen for the app opens by itself, and with working modes (ADB, Shizuku, Root) Notifications is granted by command and the same screen opens (Live updates itself has no command).
23. **Speed test section.** Extra Settings: **Speed test** is a section right after Private DNS, with a chip in the header once a result is saved. **Pass:** its button opens the same full-screen test as Task Manager > Network > Speed test; after one finished test its pill shows that download speed.
24. **File Manager Refresh.** Open Download in the File Manager. Make a file from Termux (`touch ~/storage/downloads/refresh-test.txt`), come back and tap the round **Refresh** button in the title row. **Pass:** the file appears and the arrow turns while it reads. In a search, Refresh reads the results again.
25. **Installed | Uninstalled is one choice.** Apps tab: tap **Installed**, then **Uninstalled**. **Pass:** each tap turns the other off (as System | User and Enabled | Frozen do); the same in Saved Applications and, with a device connected, in Connected Devices' app list.
26. **Row tints.** Settings > Row tints: switch **Gradient** off. **Pass:** tinted rows in the Apps tab (a running app's green) and in Live Logcat are one even colour; on again, they fade across the row. In the Apps search bar's three-dot menu the last row **Row tints** opens Settings on that card.
27. **Updated 7d is gone.** **Pass:** the Apps tab, Saved Applications and the Debloater show no Updated 7d filter; Sort > **Last updated** lists what changed lately first.

Report any step that fails with the step number, the working mode, and the phone model.
