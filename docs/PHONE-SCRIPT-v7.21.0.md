# Phone script for v7.21.0

About 15 minutes, one pass or fail per step. v7.21.0 adds the **Android Firewall** tab (`CHANGELOG.md`, `## v7.21.0-Pro`). Steps 5 to 13 write rules into the kernel's packet filter; step 14 removes every one of them, and a restart removes them too. Keep the phone next to you and read **Undoing the firewall** in the guide once before you start.

**Before you start:** install the `arm64-v8a` file of v7.21.0 over the previous version. Working mode **Root (Superuser)** with su granted (or Shizuku started as the system user or as root). Have a second app that uses the network ready (a browser is fine), and Orbot is **not** needed.

## The tab and the gate

1. Open the **Android Firewall** tab (two lines on the tab bar, **Firewall** under More or on the bottom bar), right after Custom Fonts. **Pass:** the top card's chip reads **OFF**, the switch is off, the status line says no rule of this app is in the kernel, and the Apps card lists your apps with icon, name, package and UID and two round cells (Wi-Fi, Mobile data) per row.
2. Settings (the gear) → **Working Modes** → switch to **Shell** (plain ADB) or **Shizuku** as UID 2000, then back to the tab. **Pass:** the chip reads **LOCKED**, a box under the subtitle says it needs Super User or Shizuku as system or root, with a **Set Up** button that opens Working Modes; the switch and the cells do nothing. Switch back to Root.
3. **Root Features** tab. **Pass:** an **Android Firewall** row with **Open tab** that brings you to the firewall; with Shell as the working mode the row is locked like the others.
4. Tap the **?** on the tab. **Pass:** the guide opens on **Android Firewall**, with **Undoing the firewall** right after it.

## Block list

5. In the top card tap **Mode** → **Block list**. Tap the **Use the internet** chip, find your browser, tap its **Wi-Fi** and **Mobile data** cells. **Pass:** both turn red, the top card says changes are not applied yet, **Apply changes** is lit.
6. Tap **Apply changes**, then turn the big **switch on** if it is not on. **Pass:** within a few seconds the chip reads **ACTIVE** and the status line gives the number of rules in force and the profile name; the browser **cannot load any page** (an immediate error, not a long wait); another app still can.
7. Untick the browser's two cells and **Apply**. **Pass:** the browser loads pages again; the chip stays ACTIVE.

## Allow list

8. **Mode** → **Allow list**. Tap the **Special** chip. **Pass:** rows for Linux kernel, Root (UID 0), Android system (UID 1000), DNS resolver (UID 1051), shell and the rest, each with a line that says what breaks; this app and the DNS resolver are marked as pre-allowed.
9. Back on **Use the internet**, tick only your browser for Wi-Fi and Mobile data, **Apply**. **Pass:** the app asks once because almost nothing is ticked; after you confirm, the browser loads pages, a second app (not ticked) cannot, and this app's own App Stores or Updater still loads.
10. Tap a column head (the **Wi-Fi** symbol). **Pass:** every row shown is ticked; tap it again and they are all unticked. Leave the browser ticked and Apply.

## Profiles, options, log

11. **Profile** → **New profile…** → name it `Test`, choose **a copy of the current one**. **Pass:** the picker shows `Test`, its ticks match, and switching back to the first profile applies it at once (the status line changes). Delete `Test` from the same sheet.
12. **Options** → turn **Log stopped connections** on, Apply, open a second app that is not ticked, wait ten seconds, then **Log → Read**. **Pass:** one row for that app with a count and the last destination (address, port, protocol); **Active rules → Read** shows chains named `atbx`, `atbx-wifi`, `atbx-mobile`, `atbx-reject`; **Copy** copies them.
13. **Backup → Save to Downloads**, then **Import** the same file. **Pass:** a JSON file lands in Downloads and Import asks whether to replace your profiles or add the file's next to yours; choose Cancel (add) and a profile named `… (imported)` appears. Delete it.

## Saved lists, boot, the way out

14. **Application Manager** → select two apps → **Save to List**. In **Saved Applications** the card has a **Firewall** button; tap it → **Block these apps**. **Pass:** in Android Firewall both apps lose their ticks (allow list) and the top card asks you to Apply. **Forget their rules** from the same button does the same here; **Allow these apps** ticks them on every column.
15. **Options** → **Apply on boot** on, delay 15. Restart the phone, wait 30 seconds, open the tab. **Pass:** the chip reads **ACTIVE** again and the browser rule still holds. Turn **Apply on boot** off.
16. Press and hold **Remove all firewall rules** (the red card at the end) for a second. **Pass:** the status line says the phone is back to Android's own rules, the chip reads **OFF**, every app loads pages again, **Active rules → Read** shows no `atbx` chain, and your profiles and ticks are still there.
17. Restart the phone once more and open the tab. **Pass:** OFF, nothing came back.

Report what fails with **Report a bug or idea** in the About tab, with the step number and, for steps 6 to 16, the text of the status line.
