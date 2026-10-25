# Phone script for v7.16.2

About 35 minutes, one pass or fail per step. v7.16.2 brings these changes (`CHANGELOG.md`, `## v7.16.2-Pro`): **Live Logcat** wears one colour per level and keeps up with a busy log, **saved lists** are edited by ticking apps, an app's **trackers can be switched off and back on** in Root mode with su granted or with Shizuku running as the system user (UID 1000) or root (UID 0), Working Modes has a **Connection Check** card with **Turn on Wireless Debugging**, a Wireless Debugging port that changed is found again by itself, and the Console has **Screenshot** and **Record screen**. Nothing in this script has been run on a phone by the author. Sections A to D and F work on any phone, including one that cannot be rooted; section E needs a rooted phone (Root with su, or a Shizuku started as root or the system user). The longer checks are `docs/DEVICE-TEST-CHECKLIST.md` sections 34 to 40.

**Before you start:** install the `arm64-v8a` file of v7.16.2 over v7.16.1 (an update, not a fresh install), and have a working mode on (ADB TCP, Wireless Debugging or Shizuku).

## A. Start (1 minute)

1. Open the app. **Pass:** About says 7.16.2; your settings, saved lists, keys and pinned tabs are still there.

## B. Live Logcat (6 minutes; any phone)

2. Open the **Live Logcat** tab (right after Console) and set the level to **Verbose**. Open a few other apps, then come back and let it play for a moment (it does not read while the app is in the background, so the new lines appear when you return). **Pass:** each row is one colour from end to end (the level letter, the tag, the bar at its leading edge and the message) and it matches its chip in the colour key above: green Verbose, yellow Debug, the accent colour Info, orange Warn, red Error, purple Fatal. The message is still easy to read.
3. Settings: switch **Pure black** on (or pick another Monet colour), come back. **Pass:** the rows keep their level colours, and Info follows the new accent.
4. With the log playing for a minute or two, scroll up and down quickly, then go back to the bottom. **Pass:** scrolling stays smooth; while you are scrolled up, new lines do not pull you down; back at the bottom, the list follows the newest line again.
5. Turn the phone to portrait on its narrowest setting (or a small phone). **Pass:** nothing scrolls sideways; a long tag or message wraps.

## C. Saved lists by ticking apps (6 minutes; any phone)

6. Saved Applications > **New List**. **Pass:** the sheet "Create Saved List" lists this phone's apps as the Apps tab's rows, with **Search apps** and the chips **All / In this list / System / User**.
7. Tap three apps, then tap one of them again. **Pass:** the ticks follow the taps, no row moves, and the count line reads "2 apps in this list". Name the list and save.
8. Open that list's **Edit**. **Pass:** "Edit Saved List" shows its two apps first and ticked. Untick one, tick a new one: the line says "1 added · 1 removed". Tap **In this list**: only the ticked apps (the one kept and the one just added). Untick one there: its row stays, unticked, until you change the chip or the search; tick it again. Save: the list holds the new two apps.
9. Open **Edit as text** under the rows and add a package that is not on this phone (for example `com.example.notinstalled`) on its own line. **Pass:** a ticked row tagged NOT INSTALLED appears for it; it stays after Save and reopen until you untick it.

## D. Trackers on a phone without root (4 minutes; any phone)

10. With Shizuku over ADB (UID 2000), ADB or Wireless Debugging: Apps tab, tap the **Trackers** pill and open the menu of an app it lists, then its **TRACKERS** chip. Use an app whose box has a card at the top; if every tracker reads "No part of its own to switch off: it runs inside the app's code.", that app has no tracker parts, so pick another. **Pass:** the card reads "N tracker parts · 0 switched off"; **Disable all trackers**, **Enable all trackers** and the **DISABLE** of each tracker that has parts carry a small lock, and the card says *Needs Super User (Root)*.
11. Tap **Disable all trackers**. **Pass:** nothing is switched off. On a phone without su it asks "Switching trackers off or on needs Super User. Open Root Features to check if your device supports Super User?"; OK opens **Root Features**, whose third row is **Trackers in apps**, locked.
12. Select two apps, open the batch sheet. **Pass:** **Disable Trackers** and **Enable Trackers** are at the bottom with the same lock; a tap switches nothing.

## E. Trackers with Super User (6 minutes; a rooted phone)

> This is the part a rooted phone can show: Root mode with su granted, or Shizuku started as root (UID 0) or as the system user (UID 1000). An unrootable phone stops at section D.

13. Root Features > **Trackers in apps** > **Open apps**. **Pass:** the Apps tab opens with the Trackers filter on.
14. Pick a user app you can do without (a free game), open its **TRACKERS** chip, tap **DISABLE** under one tracker. **Pass:** "Disabled N tracker parts"; that tracker's parts show *off* and the card's "switched off" count grows. The app menu's Activities and Components tabs show the same parts disabled (screens under Activities; services, receivers and providers under Components).
15. Tap **Disable all trackers**, then open the app. **Pass:** the app opens and works (if it does not, note which app and tap **Enable all trackers**).
16. Open History (the clock button in the UAD-NG Debloater). **Pass:** a step "Disabled trackers in (app)". Tap its **Undo**: the parts that step switched off come back on, and the box shows them running again.
17. Select two apps, batch sheet, **Disable Trackers**: a "Confirm Disable Trackers" box first, then the progress and Batch Results "DISABLE TRACKERS • 2 of 2 apps". Then **Enable Trackers** (no confirmation). **Pass:** both run to the end and every part is back on.

## F. Connection Check, Wireless Debugging and screen capture (10 minutes; any phone with Android 11 or newer, on Wi-Fi)

18. Open Working Modes (the working-mode badge at the top). **Pass:** the first card is **Connection Check**, with rows for Wi-Fi, the Wireless Debugging switch, the ADB connection, Shizuku and secure settings access, each OK, CHECK or INFO with a reason in words; with everything connected the tag reads ALL GOOD.
19. With Shizuku or ADB TCP connected (or the secure settings permission granted), switch **Wireless debugging** off in Developer options, come back and tap **Check again**. **Pass:** the switch row says CHECK and offers **Turn on Wireless Debugging**. Tap it: a message says it is on, the row turns OK, and Developer options shows Wireless debugging on.
20. With **Wireless Debugging** as the working mode and connected, switch Wireless debugging off and on in Developer options (Android picks a new port), come back and select **Wireless Debugging** in Working Modes again. **Pass:** within a few seconds it is ready again with no new pairing, and the message says Wireless Debugging moved from the old port to the new one.
21. Console, ADB Shell part: tap **Screenshot** and open another screen within 3 seconds. **Pass:** a message names `Pictures/ADB App Manager/screenshot-....png` with its size, and the picture shows the screen you opened.
22. Tap **Record screen** (the button reads **Stop recording**), use another app for about 10 seconds, come back and tap **Stop recording**. **Pass:** a message names `Movies/ADB App Manager/screenrecord-....mp4` with its size, and the video plays to the end.

## G. Help Guide (1 minute)

23. About > Help Guide, open "Switching trackers off in apps", "Live Logcat", "Saved Applications" (its part "Edit a list") and "Connection Check: why a connection works or does not". **Pass:** the guide says it was written for version 7.16.2, and each page describes what you just saw.

Report any step that fails with the step number, the working mode (and the Shizuku UID shown on the badge), the Android version, and a screenshot if it is about how something looks.
