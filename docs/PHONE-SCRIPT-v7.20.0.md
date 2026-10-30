# Phone script for v7.20.0

About 5 minutes, one pass or fail per step. v7.20.0 rebuilds the **Doze & power governor** and **Zram & memory** sections of Extra Settings (`CHANGELOG.md`, `## v7.20.0-Pro`) so that every row shows what the phone runs now and what it runs after you apply. Steps 3, 5 and 8 change the phone; each one says how to put it back, and a restart puts the governor and the swappiness back on its own.

**Before you start:** install the `arm64-v8a` file of v7.20.0 over v7.19.5. Open the app in **Root (Superuser)** mode (the shield at the top says Root); steps 5 and 8 are Root-only and should say **Needs Root mode** in any other mode. Open **Extra Settings**.

## Doze timing

1. Scroll to **Doze & power governor** → **Doze timing**. **Pass:** each of the five rows (Light Doze after the screen goes off, Light Doze wake-ups, Deep Doze after the phone lies still, Deep Doze wake-ups, Maintenance window) has a **Phone: …** tag with a real time (for example "Phone: 4 min", "Phone: 1–6 h"), not "…" or "not readable", and the **Phone's own** preset is lit.
2. Tap the **Balanced** preset. **Pass:** the chips 2 min / 10–30 min / 15 min / 2–12 h / 5–10 min light up, every **Phone:** tag turns amber (a change is pending) and the dashed chip still marks the phone's value; **Apply changes** is enabled.
3. Tap **Apply changes**. **Pass:** the result line says the timing was applied and read back; within a few seconds the **Phone:** tags say the new values (Phone: 2 min, Phone: 10–30 min, Phone: 15 min, Phone: 2–12 h, Phone: 5–10 min), no longer amber, and the line under the presets says when they were applied.
4. Turn the screen off, wait about 2 minutes, turn it on and open the app again. **Pass:** the status line of the section says the phone went into **light Doze** while the screen was off (or the "why" line reads "Screen off · Next light Doze step in …" while you watch it with the screen about to go off). Before 7.20.0 nothing happened for 4 minutes or more.
   To put it back: tap **Phone's own** → **Apply changes**; the tags return to the phone's own values (the same ones as in step 1).

## Power governor

5. Scroll to **Power governor**. **Pass:** the four facts are filled (Battery saver, Charging, **CPU governor** with "N clusters · lowest–highest GHz, now X GHz", Fixed performance), the **CPU governor** row has a **Phone:** tag with the governor that runs now (for example schedutil) and a chip per governor the phone offers, one of them marked "(phone's own)".
6. Tap the **performance** chip, then **Apply** in that row (or the **Performance** profile). **Pass:** the row says Set performance, the **Phone:** tag reads **performance** within a few seconds, the CPU fact says "now <highest> GHz", and the **Performance** profile is lit. Tap the **Balanced** profile (or the "(phone's own)" chip): the tag goes back to the phone's own governor. Before 7.20.0 the profile stayed on Balanced whatever you tapped.
7. Plug the charger in and tap the **Battery saver** profile. **Pass:** the result line says Android keeps battery saver off while the phone is charging, and the profile does **not** light up. Unplug and tap it again: battery saver turns on (the status bar shows it) and the profile is lit; tap **Balanced** to turn it off.

## Zram & memory

8. Scroll to **Zram & memory**. **Pass:** the RAM bar shows three numbers (Apps and system, Cache, Free) that add up to the phone's RAM; under **Memory settings** the **Zram size**, **Swappiness** and **Low-memory killer** rows each show a **Phone:** tag with the value running now (for example Phone: 8 GB, Phone: 180, Phone: Phone's own).
9. Tap **Keep more apps open** on the Low-memory killer row and a different **Swappiness** chip (for example 100), then **Apply changes**. **Pass:** the result line lists what was applied; both **Phone:** tags show the new values within a few seconds (Phone: 100, Phone: Keep more apps open), and the saved line under the rows says when. Before 7.20.0 the chips changed and the phone did not.
10. Tap **Drop page caches** above the settings. **Pass:** the RAM bar's **Cache** number drops and **Free** rises, and the line under the bar says before → after.
    To put it back: tap **Phone's own** on both rows → **Apply changes** (a restart also restores swappiness).
11. Restart the phone, open the app → Extra Settings → Zram & memory. **Pass:** the Low-memory killer row still says **Keep more apps open** if you did not put it back (Android keeps it), and the Swappiness row says the phone's own value with a muted line "You applied 100 · Re-apply" under the rows.

## Smart Tabs

12. Settings (the gear) → **Navigation**. **Pass:** on a fresh install (or after Clear data) **Smart Tabs** is off. Turn it on, close the app from the recent apps and open it again: it is still on.

Report what fails with **Report a bug or idea** in the About tab, with the step number.
