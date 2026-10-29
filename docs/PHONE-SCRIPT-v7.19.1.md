# Phone script for v7.19.1

About 9 minutes, one pass or fail per step. v7.19.1 is the owner's three changes of 2026-10-28 and the new name, ADB/Root Toolbox (`CHANGELOG.md`, `## v7.19.1-Pro`). Steps 4 to 8 need a Samsung phone; steps 6 to 8 need Root with su granted (or Shizuku as UID 1000 or 0), step 5 the opposite (ADB, Wireless Debugging, Shizuku as UID 2000, or no working mode).

**Before you start:** install the `arm64-v8a` file of v7.19.1 over v7.19.0. Have one font made on the Custom Fonts tab (Make a font, any .ttf, Build font), and know the way back: Custom Fonts, Phone font, the drop-down, **Default**; or Samsung's Settings, Display, Font size and style, Font style, Default.

## New name (1 minute)

1. Look at the app's icon on the home screen, then open the app. **Pass:** the icon and Android's list of apps say **ADB/Root Toolbox** (it may end in "…" under a small icon), the header reads "ADB/Root" over "Toolbox", About says **ADB/Root Toolbox** 7.19.1, and your settings and saved lists are still there.

## Mobile data switches (1 minute)

2. **App Stores**: tap the mobile data switch, then the **?** at the top right. **Pass:** the switch flips without the Help Guide opening, and the **?** opens the Help Guide. The switch sits below the **?**, not under it.
3. **App Updater**: the same. **Pass:** the same.

## Phone font (6 minutes)

4. Open **Custom Fonts** and scroll to **Phone font**, then tap the **Font** drop-down. **Pass:** a sheet lists **Default** first, then the phone's fonts by name (Samsung's own, such as SamsungOne or Gothic Bold, read "Came with the phone"), and the font you made reads "Made on this phone" (or "..., not installed").
5. Without Root or the system user (tag **LIMITED**): choose the font you made. **Pass:** a warning says it "is not signed by Samsung or Monotype ... The font was not changed.", and the phone's font did not change. Choose a font that came with the phone. **Pass:** Samsung's Font style opens, with a message to choose that font there.
6. With Root (tag **ROOT**) or Shizuku as the system user (tag **SYSTEM USER**): choose the font you made. **Pass:** "The font is applied.", the read-back box lists `sans.loc` and the files, the drop-down names the font, and other apps draw text in it (or after they restart, if the message said so).
7. Choose **Default** in the list. **Pass:** "The default font is back." and the phone's own font is back.
8. Press and hold a downloaded font (or the font you made, after installing it with **Install** under its build result) in the list. **Pass:** "Uninstall ...?" asks first; **Uninstall** removes it and says "Uninstalled ...", and it reads "not installed" (made here) or is gone (downloaded). Press and hold **Default** and a font that came with the phone. **Pass:** each is refused with a message, and nothing is removed.

Report what fails with **Report a bug or idea** in the About tab, with the step number.
