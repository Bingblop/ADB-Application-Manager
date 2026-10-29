# Phone script for v7.19.5

About 4 minutes, one pass or fail per step. v7.19.5 fixes Smart Tabs (`CHANGELOG.md`, `## v7.19.5-Pro`): the bottom bar now holds the tabs you open most. None of the steps change the phone.

**Before you start:** install the `arm64-v8a` file of v7.19.5 over v7.19.4. Settings (the gear) → **Navigation** set to **Bottom bar only** (or Bottom bar + top bar), and **Smart Tabs** off for now.

## Smart Tabs on the bottom bar

1. Note the four slots of the bottom bar (Application Manager and your three pinned tabs). Open **More** → a tab that is **not** on the bar (for example **Custom Fonts**), then Back. Do it five times. **Pass:** the bar has not changed.
2. Settings → **Navigation** → turn **Smart Tabs** on. **Pass:** that tab is now on the **bottom bar**, right after Application Manager; a pinned tab you used less went under **More**; **About** is still the last tile under More.
3. Settings → **Feature List**. **Pass:** the note says Smart Tabs is on and that the bottom bar holds the tabs you open most; the tabs are listed in the new order; the arrows **and the pins** are greyed out, and the pins are lit for exactly the tabs on the bar.
4. Open **More** → another tab not on the bar, then Back, eight times. **Pass:** the bar does not change while you do it.
5. Press Home, then open the app again from the recent apps (do not close it). **Pass:** that tab is now right after Application Manager on the bottom bar.
6. Close the app from the recent apps and open it again. **Pass:** Smart Tabs is still on and the bar is the same as in step 5.
7. Turn **Smart Tabs** off. **Pass:** the bottom bar is your three pinned tabs again, and the pins and arrows in the Feature List work.
8. Optional, with **Bottom bar + top bar**: turn Smart Tabs on again. **Pass:** the top bar starts with Application Manager and the same tabs in the same order as the bottom bar, and ends with About.

Report what fails with **Report a bug or idea** in the About tab, with the step number.
