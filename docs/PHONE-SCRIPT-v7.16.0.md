# Phone script for v7.16.0

About 45 minutes, one pass or fail per step. v7.16.0 adds the **Root Features** tab, the **root theme card** on the RRO/Monet Customization tab, the **CMF** palette style, **Start Shizuku (ADB)**, **Allow all** restarting the app, the **Kill Apps** and **Working mode** tiles, and the list-name question in Saved Applications (`CHANGELOG.md`, `## v7.16.0-Pro`). Nothing in this script has been run on a phone by the author. Sections A, B, E and F work in any working mode; C and D need a phone rooted with KernelSU, KernelSU Next, APatch or Magisk (skip them on a phone without root, but do step 7 there); section D needs Android 14 or newer for steps 12 to 16. The longer checks behind each section are `docs/DEVICE-TEST-CHECKLIST.md` sections 30 to 33 and the Root and Palette Style items.

**Before you start:** install the `arm64-v8a` file of v7.16.0 over v7.15.0 (an update, not a fresh install), with the **Working mode** and **Kill Apps** tiles in the Quick Settings panel and a quick list of a few user apps. Note your Android version and, on a Pixel, whether it is a quarterly update (Settings > About phone > Android version).

## A. Start (3 minutes)

1. Open the app. **Pass:** About says 7.16.0; your settings, saved lists, keys and pinned tabs are still there.
2. Settings > Palette Style. **Pass:** ten styles, the last one **CMF**; with Dark appearance and the source color #E91E63, CMF's first three swatches are three different pinks, and the app recolors and stays readable.

## B. Working modes, tiles and permissions (10 minutes)

3. Stop Shizuku (or restart the phone), connect ADB TCP or Wireless Debugging in Working Modes, then tap **Start Shizuku (ADB)** on the Shizuku card. **Pass:** "Starting Shizuku through ADB...", then Shizuku runs and the card offers to authorize it. If it fails, copy the text shown under the buttons into the report (checklist section 30).
4. With the mode set to Shizuku (not Automatic), the ADB connection held and Shizuku stopped, leave the app with Back and open it again. **Pass:** a **Start Shizuku** dialog offers to start it; OK starts it. Declined twice, it is not offered again (checklist section 31).
5. Set Working Modes to Read-Only, go back to Apps, pull down Quick Settings. **Pass:** the **Working mode** tile is dim. Choose a mode that is ready and look again: lit. Long-press the tile: the app opens (checklist section 32).
6. Tap the **Kill Apps** tile. **Pass:** the tile is called Kill Apps, the notification shows the progress, and the phone's notification settings for the app list a **Kill Apps** category. Long-press the tile: the app opens.
7. Install this build fresh on a second phone (or after clearing the app's data) with a working mode ready, and tap **Allow all** on the first-launch sheet. **Pass:** "Restarting the app", the app closes and opens again by itself within a few seconds with the permissions granted; it does not stay closed (checklist section 33).
8. Saved Applications: save a selection under the name of a list you already have. **Pass:** a sheet asks **Overwrite**, **Create new list** or **Cancel**, and each does what it says; Create new list after renaming a list onto a used name gives a second list.
9. Console > Terminal > full-screen terminal: run `ls -l /system`. **Pass:** the letters are evenly spaced in columns and do not run into each other.

## C. Root Features (10 minutes; a rooted phone)

10. With Shizuku or ADB chosen, open the **Root Features** tab (before About). **Pass:** the chip reads AVAILABLE (or DENIED or NOT FOUND); the Super User for apps and App components rows are locked with *Needs Super User*. Tap **Open root manager**: your manager opens; allow this app there (in Magisk, tap **Request Super User** first so the app is listed) and come back. **Pass:** the app asks whether to switch the working mode to Root; OK: the chip reads ACTIVE and both rows unlock. If the manager refuses, the toast says "Super User was not granted. The working mode stays …" and your mode is still chosen.
11. With root ACTIVE: **Super User for apps > Open manager** opens the manager; **App components > Open apps** opens the Apps tab. Open a system app's menu (Settings, say), Components tab, tap DISABLE on one part and answer **Cancel** to the question. **Pass:** nothing changes. Then on a user app's part: DISABLE, then ENABLE, each read back. Switch to Shizuku: the ENABLE / DISABLE buttons show the lock, and a tap offers to switch to Root.

## D. Root theme card (15 minutes; a rooted phone, Root ACTIVE)

12. RRO/Monet Customization tab, Theme half, the **Root-only theme controls** card. **Pass:** on Android 14, 15, 16 or 17 (Pixel or AOSP builds) six rows can be used: Pure black backgrounds, Color spec, More styles, Intensity, Secondary and tertiary accents, Accurate shades. On Android 12 or 13 only Pure black; on a Samsung phone every row reads *Not supported on One UI*. Note which you see and the exact Android version.
13. Dark mode. Turn **Pure black backgrounds** on and tap **Apply theme**. **Pass:** Android's dark backgrounds turn black, panels a few greys lighter; **Undo** on the bar puts the colors back; Apply with the switch off switches the overlay off.
14. **More styles:** choose **Fidelity**, look at the preview, tap **Apply theme**. **Pass:** the phone's colors (Settings, the notification shade, Quick Settings) change to match the preview. Do the same with **Monochrome** (greys only, the error red kept). Then Color spec **2026** and More styles **CMF**.
15. **Intensity:** drag Accent saturation to 150 %, Background lightness to 150 %, Apply. **Pass:** stronger accents and lighter backgrounds; Reset brings all three to 100 %. **Secondary and tertiary accents:** pick two colors, Apply: the second and third accents follow them. **Accurate shades** off, Apply: the palest accent colors are a little darker.
16. Tap **Default**. **Pass:** Android's own theme is back and the card says the overlay is off. If anything on screen looks different from the preview, take a screenshot and note the Android version and build (Settings > About phone > Build number): the colors were checked only on a computer against Android's own colour code.

## E. Help Guide (2 minutes)

17. About > Help Guide, search "Root Features" and "Root-only theme controls". **Pass:** both chapters open, and the guide says it was written for version 7.16.0.

## F. Left over from earlier releases (2 minutes)

18. **Android 16 or newer only.** Tap the **Kill Apps** tile and look at the status bar while it runs. **Pass:** if the phone shows Live Updates, a chip like "3/30" appears and the notification follows it; if it does not, it is the plain progress notification (note which; both are right).

Report any step that fails with the step number, the working mode, the Android version, and a screenshot if it is about how something looks.
