# Phone script for v7.19.3

About 15 minutes, one pass or fail per step. v7.19.3 is the owner's changes of 2026-10-29 (`CHANGELOG.md`, `## v7.19.3-Pro`): a new app icon with no background (on the watch's ADB Stub too, now called ADB Toolbox there), the ADB Stub opening on a watch as soon as it connects and showing its IP address and port, and app details (permissions, app ops, activities, components, manifest) for the apps of a connected device. Any working mode will do. Steps 1 to 5 change nothing; steps 6 to 19 need a Wear OS watch (or, for 11 to 19, any second Android device) that this phone can reach over Wi-Fi in Connected Devices, and change only things on that device that the steps undo.

**Before you start:** install the `arm64-v8a` file of v7.19.3 over v7.19.2. If the home screen still shows the lightning bolt a minute later, restart the phone once: some launchers keep the old icon in their cache until then.

## The icon (2 minutes)

1. Look at the app on the home screen and in the app drawer. **Pass:** the icon is the green Android robot in glasses and a tie, in a blue toolbox with a wrench, with **no tile, circle or colour behind it** (the wallpaper shows around it), and nothing of the lid, the antennas or the wrench is cut off. The name under it still reads **ADB/Root Toolbox**.
2. Open **Settings → Apps → ADB/Root Toolbox**, then open Recents. **Pass:** both show the same icon, with no background behind it.
3. Optional: if the launcher lets you change the icon shape, try a circle and a square. **Pass:** with every shape the picture is whole.

## About tab (1 minute)

4. Open the **About** tab. **Pass:** at the top, above the app's name, is the same robot-in-a-toolbox picture with no coloured tile behind it, sharp (not blurry), in both dark and light theme (Settings → Appearance).
5. Pull down the quick settings and look at the app's quick tiles (add one with the pencil if none is there; adding a tile changes nothing else). **Pass:** the small white icon on them is still the lightning bolt (on purpose: Android draws those as a plain white shape).

## ADB Stub on the watch (5 minutes)

Before: in Connected Devices, connect the watch over Wi-Fi once, so it is in the list; its ADB Stub card says **Installed** or offers **Install** / **Update**.

6. If the card offers **Install** or **Update**, tap it. **Pass:** the stub opens on the watch and the card says **Running**.
7. Look at the watch. **Pass:** under the state at the top it shows **IP:** with the watch's address (the same one as under the device's name on the phone) and **Port:** with the number after the colon there (for example 5555).
8. Tap **Disconnect** in the device menu (the three dots on the device card). **Pass:** the stub closes on the watch first and its screen goes back to normal.
9. Connect the watch again (its row in **Recent**, or **+ Add device**). **Pass:** without touching anything, the stub opens on the watch again within a few seconds, and the card says **Running**. Then **Disconnect** again.
10. Swipe up on the watch's watch face to its list of apps. **Pass:** the stub is listed as **ADB Toolbox** (not ADB Stub), and its icon is the same robot in a toolbox as this app on the phone, with nothing behind it (not the old green circle with a white link). The top of the stub's screen also says **ADB Toolbox**. If the stub's notification shows, its small icon is the lightning bolt.

## App details of a connected device (8 minutes)

Pick an app of the watch you can play with, for example a weather or a sample app (not the watch face or the companion app).

11. In **Apps**, tap the app's three dots. **Pass:** the menu has **Permissions**, **App Ops**, **Activities**, **Components**, **Manifest** and **Details** after **Pull APK**.
12. Tap **Permissions**. **Pass:** a sheet like the Application Manager's app sheet opens on Permissions, with the app's name, package and version, the device's name and address, and its permissions with GRANTED / DENIED buttons (install-time ones have a lock).
13. Tap a runtime permission's button (for example Location or Body sensors) twice. **Pass:** the first tap changes it (message *Revoked ...* or *Granted ...*), the second puts it back, and each time the button shows the new state. Check the app's permissions on the watch if you like.
14. Tap **App Ops**, then **Ignore** on an op such as RUN_IN_BACKGROUND, then **Reset**. **Pass:** the tapped mode lights up each time (or a message says *Android kept ... at ...*).
15. Tap **Activities**, then **LAUNCH** on the first one (the launcher one). **Pass:** the watch wakes and that screen of the app opens on it, and the phone says *Opened on the device*.
16. Tap the square button next to the search box. **Pass:** the tab fills the phone's screen with the tab's name and the app's name at the top. Press **Back**: it is a sheet again. Press **Back** once more: the sheet closes.
17. Open the menu again and tap **Manifest**. **Pass:** after a moment the app's AndroidManifest.xml shows in colours; typing *activity* in the find box shows the matching lines and *1 / N matches*.
18. Tap **Components** and **DISABLE** on a service, then **ENABLE**. **Pass on a rooted watch:** each tap works (*Component disabled*, then *Component enabled*). **Pass on a normal watch:** the message says *Android lets only root or the system do this, and on this device adb runs as the shell user without su.* and the button does not change.
19. Tap **Features**. **Pass:** each feature says *on the device* or *not on the device*, and the watch's type (android.hardware.type.watch) says *on the device*.

Report what fails with **Report a bug or idea** in the About tab, with the step number.
