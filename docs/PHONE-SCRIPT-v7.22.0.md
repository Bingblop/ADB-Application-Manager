# Phone script for v7.22.0

About 15 minutes, one pass or fail per step. v7.22.0 makes the **Monet theme match ColorBlendr**, puts it on **one screen with one Apply**, adds **Tint text color**, and lets you **uninstall a switched-off overlay** (`CHANGELOG.md`, `## v7.22.0-Pro`). Steps 5 to 11 write a colour overlay; step 12 takes it off again. Steps 13 to 17 uninstall an overlay package, which only a test overlay should go through.

**Before you start:** install the `arm64-v8a` file of v7.22.0 over the previous version. Working mode **Root (Superuser)** with su granted, or Shizuku started as root (UID 0). Colours apply to Google apps (Settings, Phone, Messages, Chrome), not to One UI's own screens; that is expected. Note which Android version the About tab shows.

## The one screen and the gate

1. Open the **RRO/Monet** tab → **Theme**. **Pass:** one scrolling screen in this order: Colors in use, Color and the two extra accents, the ten styles, Color spec, Contrast, Intensity, the three switches (Accurate shades, Pure black, Tint text color), How this works, Back up and restore, and a single **Apply** bar fixed at the bottom. No second Apply anywhere.
2. Settings (the gear) → **Working Modes** → switch to **Shell** (plain ADB), then back to the Theme screen. **Pass:** the root-only rows (the root styles, Pure black, Tint text color, Apply) are greyed with a lock glyph and a reason; the app's own Material You colour still changes.
3. Switch back to **Root (Superuser)**. **Pass:** the locks are gone.
4. Tap the **?** on the tab. **Pass:** the guide opens on the Monet theme section.

## Colours that match ColorBlendr

5. Pick a seed colour you also set in ColorBlendr (same colour, **Tonal Spot**, **Color spec 2025**, **Contrast Standard**). Tap **Apply**. **Pass:** a "Theme overlay written" message, and the Colors in use chips match what ColorBlendr shows for the same settings.
6. Open **Settings**, **Phone** and **Chrome**. **Pass:** their accents and surfaces match the same screens themed by ColorBlendr (same seed), and are not darker or duller.
7. Back in the Theme screen turn **Dark** on in the phone's system settings, then reopen Settings. **Pass:** the dark colours follow, and the light ones return when you switch back.
8. Change **Style** to **Vibrant**, then **Fidelity**, **Apply** each time. **Pass:** the colours change each time and the Colors in use chips follow.
9. Turn **Tint text color** off, **Apply**. **Pass:** body text in Settings is plain black or white, not tinted. Turn it on, **Apply**: the text takes a hint of the accent again.
10. Turn **Pure black** on, **Apply**, with the phone in dark mode. **Pass:** the dark backgrounds of Google apps are black.
11. On Android 16 QPR1, QPR2 or 17 only: after any **Apply**, open **Overlays**. **Pass:** the theme overlay is listed and On (no "refused" message at Apply).

## Taking it off

12. **Back up and restore** → note the backup, then use the undo for the overlay (**Undo** / **Reset**). **Pass:** the colours return to the phone's own and the overlay is gone from the **Overlays** list.

## Overlays: nothing lost, and uninstall

13. **RRO/Monet** → **Overlays**. Switch any non-essential overlay on with its switch, then off. **Pass:** each change lands and the row shows the real state.
14. Press and hold a row that is **On**. **Pass:** it switches off (as before).
15. Press and hold a row that is **Off**, with the working mode on **Shell**. **Pass:** nothing changes and a message says uninstalling needs Super User, or Shizuku as root or the system user.
16. Switch to **Root (Superuser)**, then press and hold a switched-off overlay that is a test overlay you can lose. **Pass:** a prompt "Uninstall <name>?" that says the APKs are copied to Download/ADB App Manager/Overlays first. Confirm. **Pass:** the row disappears, and Download/ADB App Manager/Overlays/<name>/ holds the APK file(s).
17. Press and hold a switched-off **firmware** overlay (one under /system or /vendor, for example a system navbar one). **Pass:** a plain refusal that it is part of the phone's firmware; nothing is removed. Reinstall the saved copy from the **Installer** tab (choose all files in the folder together) and **Pass:** the overlay is back in the list, Off.

Report what fails with **Report a bug or idea** in the About tab, with the step number, the Android version and, for steps 5 to 11, the seed colour and the style.
