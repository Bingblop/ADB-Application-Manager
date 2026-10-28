# Phone script for v7.18.1

About 15 minutes, one pass or fail per step. v7.18.1 brings five small changes (`CHANGELOG.md`, `## v7.18.1-Pro`).

**Before you start:** install the `arm64-v8a` file of v7.18.1 over v7.18.0 (an update, not a fresh install). Have Wi-Fi and mobile data both available, so you can switch Wi-Fi off and on in the Quick Settings panel.

## A. Row tints in the Apps search menu (3 minutes)

1. Open the app, then Apps. **Pass:** About says 7.18.1. If you never touched Settings → Row tints → Gradient before, the tinted rows (running, frozen, selected) are one even colour, no glow; if you had switched it, your choice is still there.
2. Tap the ⋮ in the search bar. **Pass:** under the three search switches is **Row tints** with a slider (Off, Faint, Subtle, Normal, Vivid) and below it a **Gradient** switch; no row opens Settings any more.
3. Move the slider to **Vivid**, then switch **Gradient** on. **Pass:** the rows behind the menu change at once; open Settings → Row tints: the slider reads Vivid and Gradient is on. Put both back as you like them.

## B. Monet theme backup (4 minutes)

4. Open RRO/Monet Customization, Theme half, and pick a colour and a style (do not apply). Scroll to the bottom. **Pass:** the last card is **Back up and restore**, with **Back up now**, **Share…** and **Restore from a file…**.
5. Tap **Back up now**. **Pass:** a message names the file, `monet-theme-<date>.json`, in `Download/ADB App Manager` (or your backup folder).
6. Pick another colour, then **Restore from a file…** and choose that file. **Pass:** the app asks "Restore the theme from …?"; Yes brings the first colour and style back, and the phone's colours do not change until you tap **Apply theme**.
7. Try **Restore from a file…** with a Saved Applications backup. **Pass:** "That is not a Monet theme backup from this app." and nothing changes.

## C. Mobile data (6 minutes)

8. App Stores, scroll to the bottom. **Pass:** **Update stores on mobile data** is off. Switch Wi-Fi off (mobile data on) and open F-Droid, then a repository you never opened. **Pass:** no download; the store you opened before still shows its apps (letters, no icons), the new one says "You are on mobile data, and stores update only on Wi-Fi…".
9. Switch **Update stores on mobile data** on. **Pass:** "Stores now update on mobile data too", and the repository loads. Close and reopen the app: the switch is still on. Switch it off again.
10. APK Installer/Updater → Application Updater, bottom. **Pass:** **Check for updates on mobile data** is off. Still on mobile data, tap the check. **Pass:** it stops with "You are on mobile data, and updates are checked only on Wi-Fi…" and the earlier results stay. Switch Wi-Fi back on and check: it runs.

## D. Root Features (2 minutes)

11. Open Root Features. **Pass:** the Features card has **Additional Monet Customization**. Without root it is locked ("Needs Super User"). With Root and su granted (or Shizuku as UID 0), **Open tab** goes to RRO/Monet Customization, Theme half, at **Root-only theme controls**.
