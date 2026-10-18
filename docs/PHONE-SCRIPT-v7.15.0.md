# Phone script for v7.15.0

About 35 minutes, one pass or fail per step. v7.15.0 is a top-to-bottom UI pass: the app is renamed **ADB/Root Full System Toolbox**, the header is one colour with only the Android and One UI versions under it, and every tab's look and the way to reach its features changed (sections B to F). Nothing else changed in what the app does. Section G checks the one part of v7.14.1 that never ran on a phone: the Live Update chip of Stop apps on Android 16. Nothing in this script has been run on a phone by the author. Any working mode will do unless a step says otherwise; step 26 needs Android 16 or newer.

**Before you start:** install the `arm64-v8a` file of v7.15.0 over v7.14.1 (an update, not a fresh install). Have a saved list with a few apps, the UAD-NG list downloaded once in the Debloater, and for section G a quick list of 30 or more user apps with the **Stop apps** tile in the Quick Settings panel.

## A. Start and header (3 minutes)

1. Open the app. **Pass:** the launcher and the app's title say **ADB/Root Full System Toolbox**; About says 7.15.0; your settings, saved lists, keys and pinned tabs are still there.
2. Look at the header in dark and in light (Settings > Appearance). **Pass:** the title is one colour (no accent words), and the line under it says only "Android 16 • One UI 8.5", with "One UI 8.5" in one piece; nothing overlaps the gear or the mode badge.
3. With a working mode on, the badge shows the short mode name (for example "Shizuku"). Choose a mode that is not ready. **Pass:** the badge says "Shizuku (not ready)" (or the mode you chose) in the warning colour; a tap on it opens Working Modes.

## B. Apps, bottom bar and app menu (6 minutes)

4. Apps tab: scroll the filter pills sideways. **Pass:** the row fades at the edge that has more; the rows are a little shorter than before and the check marks beside the icons, the row tints and the gradient buttons are as they were.
5. Turn on a filter (for example **User**). **Pass:** **Select shown** appears next to **Clear filters**; a tap selects every app shown.
6. Press and hold the **Apps** tab in the bottom bar. **Pass:** the menu lists **Running**, **Frozen** and **Uninstalled**, and each opens the Apps tab with that filter.
7. Press and hold an app. **Pass:** the app menu opens without asking first; its permission chips are on one line that fades; long permission names break at "." or "_" instead of running off the sheet.
8. Open **More** in the bottom bar. **Pass:** every tile's label fits on its tile.

## C. Saved Applications, Debloater, Installer and Settings (8 minutes)

9. Saved Applications. **Pass:** the lists come first, right under the filter pills; **Back up and restore** and **Quick actions** are folded at the bottom and open with a tap on their title; each list's buttons sit in one grid (Recall, Quick list, Edit, Copy, Delete).
10. Debloater (list downloaded). **Pass:** the top card is folded to its name and status line; the round-arrow **Update List** and the clock **History** buttons sit beside the search; History opens the log with Undo.
11. In the Debloater scroll each filter row sideways. **Pass:** each is one line of pills; nothing wraps onto a second line.
12. Installer: switch between **Application Installer** and **Application Updater** at the top. **Pass:** it is a segmented control like Light / Dark / System, and the buttons under it are all the same height.
13. Settings. **Pass:** the cards are in your v7.14 order (Language, Appearance, Palette Style & My Themes, Curated Theme Palettes, Default Ask Agent, Lists, Motion, Row tints, …); long descriptions show two lines and open with a tap; Palette Style shows three swatches a row (two on a narrow screen, so no name breaks).
14. On the Debloater's folded top card, tap the **?** and then the round arrow beside it. **Pass:** the **?** opens the help, the arrow opens or folds the card; neither tap lands on the other button.

## D. Console and Live Logcat (6 minutes)

15. Console > ADB Shell with a working mode on. **Pass:** no "Active mode" card between the command field and the tool chips (the header badge names the mode); the mode button, the command field and **Run** are one height; before the first command the output area is already a console card ending in a "$ ▍" prompt; the find row appears only once there is output.
16. Switch the working mode to Read-Only and come back. **Pass:** a tinted card says no working mode is ready, with **Configure**. Turn **Rish mode** on (it needs Shizuku installed and running; skip this half otherwise): the card names the Rish shell and how to leave it.
17. Scroll the tool chips. **Pass:** the order is Cheat Sheet, History, Saved, Rish mode, UID, Connect 5555, tcpip 5555, Dev Options, and the row fades at its end.
18. Press and hold the **Console** tab. **Pass:** the menu still lists ADB Shell, Terminal, Full-screen terminal and Coding Agents. Then in Settings > Navigation > Shortcuts, under Console turn **Coding Agents** off and **History** on (a menu holds four) and hold the tab again: **History** is listed and opens the ADB Shell's command history. **Reset shortcuts** puts the four back.
19. Live Logcat. **Pass:** the title row reads "Live Logcat" with **Play** beside it (green with black text; **Stop** red with black text while it runs); the tools are two rows (Level, Lines, All apps / Record, Recordings, Copy, Save, Share); the level key wraps so every level is in view; a tap just under **All apps** opens the app picker, not Recordings.

## E. Hidden Settings and Files (5 minutes)

20. Hidden Settings. **Pass:** the Private DNS pointer is still near the top; the buttons of the search bar are as tall as the search field; on a row the value and **Ask agent** share one line.
21. Files: open Download, then a folder inside it. **Pass:** the path under the title is a row of crumbs (/storage/emulated/0/Download/…, each folder name tappable, the open one in bold); a tap on a crumb, or on the "/" after it, opens that folder; folder names are in the text colour with the icon in the accent; Go and Search are tonal, not bright.
22. Press and hold the **Files** tab (if it is not on the bar, pin it first in Settings > Feature List; the bar holds four, so unpin another tab first). **Pass:** **Search files**, **Storage** and **Downloads** are listed and each does what it says.

## F. The other tool tabs and About (5 minutes)

23. Morphe Patcher: **Pass:** Sources, Morphe Helper, Patched APKs and Settings are one row that scrolls sideways; Apps | Community is one segmented control; **Patch a file** is tonal. Task Manager: the **Graph speed** slider is long enough to drag (on one line with its name where it fits, else under its name; its value always sits at its end); the sort buttons are chips, the chosen one tinted. Extra Settings: the section icons sit on a tinted tile without a glow.
24. Connected Devices with no device connected. **Pass:** the top card shows only **Refresh** (no empty device picker and no second **+ Add device**; the one under "No device connected" stays); **Reconnecting devices** is folded right under the top card.
25. SD Maid: **Pass:** **Scan all** is the only bright button in the top card and the tool list, and each tool's **Scan** is tonal (Clear Data from Uninstalled Apps and Trim Caches keep their bright buttons in their own cards); the Accessibility service card has no empty space under its text. About: **Handy to know**, **Privacy** and **Credits** are folded and open with a tap on their title; **Contact the developer** is unchanged.

## G. Left over from v7.14.1: the Live Update chip (3 minutes)

26. **Android 16 or newer only.** Tap the **Stop apps** tile and look at the status bar while it runs. **Pass:** if the phone shows Live Updates, a chip like "3/30" appears and the notification follows it; if it does not, it is the same plain progress notification (note which; both are right). Settings > Application Permissions lists a **Live updates** row; **Grant** on it asks for Notifications first if they are off, then opens the phone's Live updates screen for the app.

Report any step that fails with the step number, the working mode, and a screenshot if it is about how something looks.
