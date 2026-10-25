# Phone script for v7.14.0

About 50 minutes, one pass or fail per step. v7.14.0 is a feature release; nothing in it has been run on a phone or a watch by the author. Any working mode will do unless a step says otherwise (Root only for step 14). Section H needs a Wear OS watch and can be skipped without one.

**Before you start:** install the `arm64-v8a` file of v7.14.0 over your current version (an update, not a fresh install). Have a few small user apps you can freeze and unfreeze safely.

## A. Start and name (3 minutes)

1. Open the app. **Pass:** the launcher label and the header say **ADB System Manager** ("ADB System" over "Manager", every word visible); About says 7.14.0; your settings, saved lists, keys and pinned tabs are still there.
2. If the phone's Android System WebView is older than Chrome 111 (About shows the WebView version): **Pass:** a notice offers Update and Not now, once; rows and buttons still draw with their colours. Otherwise skip.

## B. Apps tab (6 minutes)

3. Look at the filter row. **Pass:** All Apps, Installed | Uninstalled, System | User, Enabled | Frozen, Running, Updated 7d, Suspended, Trackers, Patched; no "3rd Party". The top row below the search has only **Sort** and **Button**, on one line.
4. Select one app by its icon. **Pass:** only that row has an outline, a band of light sweeps across it once; a running or frozen app has a soft glow, no outline. Frozen is a clear blue.
5. Settings > Row tints: try Off, Faint, Subtle, Normal, Vivid. **Pass:** five steps; the row glows get stronger; badges stay readable at Vivid.
6. Open an app's ⋯ menu. **Pass:** the buttons stand in four groups (Open, Control, Maintenance, Package); Uninstall is filled red, Freeze ice blue, Reinstall green; there is no Patched / modified box. Tap the round expand button on a tab (Activities, say): it fills the screen; the X and Back return to the menu.
7. Uninstall a test app from its menu, then Reinstall it. **Pass:** nothing in the menu moves while it changes. Then change the theme in Settings: the Undo bar that appears is gone after about 3 seconds.
8. Open Saved Applications. **Pass:** **Profiles**, **CSV** and **Backups** sit beside New List and open their sheets.

## C. Permissions and Settings (6 minutes)

9. Settings > Language card > **Application Permissions**. **Pass:** the sheet lists every permission with Android's name under it, including Read Logs, Bluetooth Connect and Notifications. With a working mode, tap **Allow** on Read Logs: it turns to "✓ Granted" (read back from the phone). The app restarts when the sheet closes.
10. Fold and unfold two Settings cards (the round chevron or the title). **Pass:** they fold smoothly and stay folded after the app is closed and opened again. There is no Private DNS card in Settings (it is in Extra Settings).
11. Anything that asks a question in the app (for example deleting a saved list). **Pass:** the dialog has the app's colours and a real title, not "The page at file:// says".

## D. Task Manager and the speed test (8 minutes)

12. Task Manager: set the graph speed to 50 ms and auto-refresh to 0.5 s. **Pass:** the graph scrolls smoothly and the app stays responsive; nothing faster is offered.
13. Task Manager > Network > **Speed test**, on Wi-Fi, **Start**. **Pass:** ping, jitter, download and upload fill in, the gauge moves, the data centre is named; the result is in the history. On mobile data, the warning about up to 350 MB shows before the test starts. **Stop** and Back stop it at once.
14. (Root only) GPU renderer: choose **ANGLE (OpenGL ES on Vulkan)**, then the previous renderer. **Pass:** each choice is confirmed; outside Root mode ANGLE shows a lock and explains.

## E. Live Logcat and the Console (4 minutes)

15. The **Live Logcat** tab. **Pass:** it is a tab of its own; the lines drop-down says 1000; Play is green, Stop red; Verbose dark green, Debug yellow, Info the accent, Warn orange; rows are tinted in their level's colour. Change the level and filter, close and reopen the app: they are kept.
16. Console. **Pass:** the segments are ADB Shell | Terminal, and the Terminal works there.

## F. Hidden Settings history (3 minutes)

17. Hidden Settings: change one harmless setting, then tap the clock button next to the search box. **Pass:** the Changes sheet lists it with the table, "just now", the date and time and old → new. **Revert** puts the old value back. Close and reopen the app: the history is still there.

## G. Connected Devices (6 minutes, needs a second Android device connected)

18. A connected device's Apps list. **Pass:** the same filter pills as the Apps tab (only the ones a device can report), the icon is the selection box, a frozen app is tinted blue, and the row's Action Button follows **Button** in the Sort / Button row. Holding the Action Button opens that device app's menu, not this phone's.
19. Select a device row, then go to the Apps tab and clear the selection there. **Pass:** the device row stays selected.
20. The device's Logcat. **Pass:** 1000 lines, the colour key with a count per level; a chip hides or shows its level; hiding every level keeps the counts and says every level is hidden.
21. The Reconnecting devices card. **Pass:** it is in Connected Devices, folded at first.

## H. ADB Stub on a Wear OS watch (17 minutes, needs a watch on the same Wi-Fi with ADB debugging and Debug over Wi-Fi on, connected in Connected Devices)

22. With the watch chosen, **Pass:** an **ADB Stub** card under its device card (badge Wear OS) with **Install**; a phone or tablet has no such card. Tap **Install**: Installing..., Starting..., then **Running** with **Stop**. The watch shows the ADB Stub screen: Connected in green, its Wi-Fi address, "Screen: kept on".
23. Leave the watch alone for two minutes. **Pass:** the screen stays on (it may dim), and the phone still lists the watch's apps.
24. Device menu > **Disconnect**. **Pass:** the stub closes on the watch within a few seconds and its screen turns off on its normal timeout; a minute later Wi-Fi behaves as before the test (write down what you see).
25. Connect the watch again. **Pass:** the stub starts by itself. Device menu: **Start ADB Stub on connect** turns it off for the next connection; **Stop** on the card closes it.
25a. Tap **Start**, then swipe the app away from the phone's recent apps without Disconnect. **Pass:** about 5 minutes later the stub closes on the watch by itself and its screen turns off on its normal timeout. Write down how long it took.

## I. Self-test (2 minutes)

26. About > **Self-test** > Run. **Pass:** the checks finish with PASS, INFO or SKIP (a FAIL needs a note: which check and what it printed). **Copy report** copies a report with no serial, IP address or account name in it.

Report any step that fails with the step number, the working mode, and the phone (and watch) model.
