# Phone script for v7.19.3

About 3 minutes, one pass or fail per step. v7.19.3 is the owner's one change of 2026-10-29 (`CHANGELOG.md`, `## v7.19.3-Pro`): a new app icon with no background. Any working mode will do; none of the steps change the phone.

**Before you start:** install the `arm64-v8a` file of v7.19.3 over v7.19.2. If the home screen still shows the lightning bolt a minute later, restart the phone once: some launchers keep the old icon in their cache until then.

## The icon (2 minutes)

1. Look at the app on the home screen and in the app drawer. **Pass:** the icon is the green Android robot in glasses and a tie, in a blue toolbox with a wrench, with **no tile, circle or colour behind it** (the wallpaper shows around it), and nothing of the lid, the antennas or the wrench is cut off. The name under it still reads **ADB/Root Toolbox**.
2. Open **Settings → Apps → ADB/Root Toolbox**, then open Recents. **Pass:** both show the same icon, with no background behind it.
3. Optional: if the launcher lets you change the icon shape, try a circle and a square. **Pass:** with every shape the picture is whole.

## About tab (1 minute)

4. Open the **About** tab. **Pass:** at the top, above the app's name, is the same robot-in-a-toolbox picture with no coloured tile behind it, sharp (not blurry), in both dark and light theme (Settings → Appearance).
5. Pull down the quick settings and look at the app's quick tiles (add one with the pencil if none is there; adding a tile changes nothing else). **Pass:** the small white icon on them is still the lightning bolt (on purpose: Android draws those as a plain white shape).

Report what fails with **Report a bug or idea** in the About tab, with the step number.
