# Phone script for v7.19.4

About 8 minutes, one pass or fail per step. v7.19.4 is the owner's list of 2026-10-29 (`CHANGELOG.md`, `## v7.19.4-Pro`). Steps 7 to 12 need an API key of one agent (Perplexity, or any other in the list); steps 5 and 6 need a device connected in Connected Devices. None of the steps change the phone.

**Before you start:** install the `arm64-v8a` file of v7.19.4 over v7.19.3.

## Smart Tabs (3 minutes)

1. Settings (the gear) → **Navigation**. **Pass:** a **Smart Tabs** switch sits under the three Navigation choices, **off**, with a short line under it that says what it does.
2. With it off, open a tab far down the list (for example **Custom Fonts**) five times, going back to Application Manager in between. Then turn **Smart Tabs** on. **Pass:** that tab is now right after Application Manager on the top bar, and on the bottom bar when it is pinned (or first under **More** when it is not); **About** is still last.
3. Scroll to **Feature List**. **Pass:** a note says Smart Tabs is on, the tabs are listed in the new order and the ▲ ▼ arrows are greyed out; the switches and pins still work.
4. Turn **Smart Tabs** off. **Pass:** the tabs are back in the Feature List order and the arrows work again.

## Connected Devices: Ask agent on hidden settings (2 minutes)

5. **Connected Devices** → a device → **Hidden Settings**. **Pass:** every setting row has an **Ask agent** button; tapping it (not the row) opens the Ask agent window, and the text sent names the device's Android version and model, not this phone's.
6. Tap a row to open its sheet and tap **Ask agent** there. **Pass:** the same window opens; when the answer is in, there is no "Kept with this setting" line, and the row shows no AI tag afterwards.

## Agent API keys and checkmarks (3 minutes)

7. Settings → **Default Ask Agent**. **Pass:** under the test button there is an **Agent API keys** section: an agent list, a key field, **Test key**, **Get a key** and **Remove key** (Remove shows only when a key is saved).
8. Choose an agent, paste a wrong key and press **Test key**. **Pass:** a red line says why it failed; nothing is saved and no ✅ appears.
9. Paste the right key and press **Test key**. **Pass:** "It works ... ✅", the field shows dots, and the agent has **✅** after its name in the Agent API keys list and in the Default Ask Agent list (when it is one of those).
10. Press **Test key** again with the field left as dots. **Pass:** the agent answers one sample question with the saved key.
11. Console → **Terminal**, open the **Coding Agents** list. **Pass:** the same agent has **✅** after its name (when it is a Terminal agent).
12. Back in Settings press **Remove key** and confirm. **Pass:** the field is empty and the ✅ is gone from all the lists.

Report what fails with **Report a bug or idea** in the About tab, with the step number.
