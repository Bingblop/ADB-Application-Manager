# Phone script for v7.19.2

About 7 minutes, one pass or fail per step. v7.19.2 is the owner's three changes of 2026-10-29 (`CHANGELOG.md`, `## v7.19.2-Pro`). Steps 5 to 9 need a Default Ask Agent that is connected and works (Settings, **Default Ask Agent**, **Test the Default Ask Agent**). Any working mode will do; none of the steps change the phone.

**Before you start:** install the `arm64-v8a` file of v7.19.2 over v7.19.1.

## Custom Fonts order (1 minute)

1. Open **Custom Fonts** and scroll down. **Pass:** the cards read, top to bottom, **Make a font**, **Phone font**, **Emoji fonts**, **System emoji**. Phone font works as it did in 7.19.1.

## Mobile data switches (1 minute)

2. **App Stores**: look at the top of the tab. **Pass:** the **?** sits on a line of its own at the top right, above the mobile data card and outside it, and the card is one thin line (about a button's height) holding only the switch.
3. Tap anywhere on that thin card, then tap the **?**. **Pass:** the switch flips without the Help Guide opening; the **?** opens the Help Guide at App Stores.
4. **App Updater**: the same thin card at the top. **Pass:** it flips on a tap, it is as thin as the one in App Stores, and the tab's **?** is not inside it.

## Hidden Settings: Ask agent keeps a short line (5 minutes)

5. **Hidden Settings**: pick a setting with no description (its row says "No description yet") and tap its **Ask agent**. Wait for the answer. **Pass:** the answer ends with a line starting "In short:", and below it the window says "Kept with this setting: ..." with that sentence.
6. Close the window. **Pass:** the row now shows a small **AI** tag and the short line in place of "No description yet", and the **Described** chip lists the setting.
7. Search for a word from the short line (one that is not in the setting's name). **Pass:** the setting is found.
8. Tap the row to open it. **Pass:** "From Ask agent" heads the sheet with the short line and "· (agent name) · (today's date)". Tap **Ask agent** in the sheet. **Pass:** a new answer comes back and the short line is replaced with the new one.
9. Close the app fully (swipe it away from Recents), open it, and go back to Hidden Settings. **Pass:** the AI line is still on the row and in the sheet.
10. Optional: in Settings choose no Default Ask Agent and ask about another undescribed setting. **Pass:** the web lookup answers, and nothing is kept (no "Kept with this setting" line, no AI tag on its row).

Report what fails with **Report a bug or idea** in the About tab, with the step number.
