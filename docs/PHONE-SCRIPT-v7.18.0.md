# Phone script for v7.18.0

About 30 minutes, one pass or fail per step. v7.18.0 brings two changes (`CHANGELOG.md`, `## v7.18.0-Pro`): a **Search Google Fonts** page that pulls up over the **Custom Fonts** tab, to search the families of Google Fonts, see each one drawn in its own font, download it and put it in Make a font, and sixteen sample emoji under each of the five fonts of Emoji fonts. Nothing in this script has been run on a phone by the author; the Google Fonts code was checked on a computer against Google Fonts' answers captured on 2026-10-26 and 2026-10-27, and the page in a headless browser with a mock. It is written for the owner's Galaxy S26 Ultra (One UI 8.5, bootloader locked, no root), and every step can be run there: nothing on the Google Fonts page or in the emoji samples needs a working mode, root or a Samsung phone, only internet (sections B to E, and step 21). Nothing else on the Custom Fonts tab works differently, except My fonts' **Export**: it is greyed out while Android's save screen is open for a family's **Save to phone** (one save screen at a time), and its "Saved …" message is no longer lost when Android re-creates the app's window behind the save screen. The tab needs a working mode only where v7.17.0 said: **Install** works with any working mode or through Android's installer, the font changer needs Root with su or Shizuku as the system user (UID 1000) or root (UID 0), and System emoji needs Root with su or Shizuku as root; this script uses none of them. The longer checks are `docs/DEVICE-TEST-CHECKLIST.md` sections 47 to 54.

**Before you start:** install the `arm64-v8a` file of v7.18.0 over v7.17.0 (an update, not a fresh install), and do not open the Google Fonts page before step 2. Have Wi-Fi or mobile data on, and airplane mode at hand in the Quick Settings panel. Write down whether the iOS emoji are downloaded (Custom Fonts > Emoji fonts; section G has a step for each case).

## A. Start (1 minute)

1. Open the app, then Custom Fonts. **Pass:** About says 7.18.0; the fonts in My fonts are still there. The top card has a button that looks like a search field, with a search icon and **Search Google Fonts**, and no number yet. Under Make a font's Regular row (no Regular chosen) is the link **Find one on Google Fonts**. Nothing else in Make a font changed.

## B. The list (6 minutes; internet)

2. Tap **Search Google Fonts**. **Pass:** a page slides up over the tab, titled **Google Fonts**, with **Close** (the X) and **Search fonts or designers** at the top; "Getting the list of fonts from Google Fonts…", then the cards, "List updated" with today's date and **Refresh**, and above the cards the count ("1,950 families", or the number Google has now).
3. Scroll slowly through the first cards. **Pass:** each card shows the family's name, a line such as "Sans serif · Łukasz Dziedzic · 10 styles" and a sentence drawn in the family's own font; a sentence is blank for a moment, never first in the app's font. At the bottom, "Show 30 more · N left" adds 30 cards.
4. Type `lukasz` in **Search fonts or designers**. **Pass:** the list follows as you type, Lato is in it (its designer is Łukasz Dziedzic), and the count reads "N of 1,950" (with your list's number). Clear the search.
5. Pick Handwriting in **All categories**, turn on the **Variable** chip, then sort by **Newest**. **Pass:** the list narrows or reorders each time and starts at the top. Search `zzzz`. **Pass:** "No fonts match." with **Clear filters**, which empties the search and the menu, turns the chip off and shows every family.
6. Type `Hello 123` in **Type something to preview** and move **Size**. **Pass:** after a moment every card in view shows Hello 123 in its own font, larger or smaller with Size. Empty the field: the sentences come back.

## C. A family's page (4 minutes; internet)

7. Search `Roboto` and tap its card. **Pass:** the bar reads Roboto with an arrow; the page shows "Roboto" large in Roboto, "Sans serif · Christian Robertson, ParaType, Font Bureau · 18 styles · Variable: Width, Weight" (as Google lists it now), **Download** with "2 font files", and **Type tester** with a **Width** and a **Weight** slider: moving Weight makes the line heavier at once. **Styles** lists "Thin 100" to "Black 900 Italic", each drawn in its style. **About this family** shows Google's description, **Licence** "SIL Open Font License 1.1" with **Licence text**, and **Open on fonts.google.com**, which opens the browser.
8. Android's Back. **Pass:** the list, at the same place. Back again closes the page. Open the page again with **Search Google Fonts** (the button now shows the count) and tap Roboto's card.

## D. Download, Use, Save, Delete (8 minutes; internet)

9. On Roboto's page tap **Download**. **Pass:** a bar with "File 1 of 2 · … KB of … KB" and **Cancel**, then the message "Roboto downloaded", "Downloaded · 2 files · …", and **Use in Make a font**, **Save to phone** and **Delete**. The card in the list has DOWNLOADED.
10. Empty the Name box of Make a font if it holds anything, then on Roboto's page tap **Use in Make a font**. **Pass:** the page closes, Make a font scrolls into view, the message "Roboto is in Make a font"; the Regular row ends with "Variable font"; Bold reads **Same variable font, another weight**; Name reads Roboto. Tap **Build font**. **Pass:** "Roboto is ready in My fonts." and a new row in My fonts.
11. Empty the Name box again. Open the page, search `Lato`, open its page. **Pass:** the note '"Lato" is a Reserved Font Name: a font made from this family must have another name.' Tap **Download**. **Pass:** "File 1 of 10" to "File 10 of 10", then a **Regular** choice set to Lato-Regular.ttf and a **Bold** choice set to Lato-Bold.ttf.
12. **Use in Make a font**. **Pass:** the Regular row names Lato-Regular.ttf, Bold reads **Another font file** with Lato-Bold.ttf, and Name stays empty. Type `Lato Mine`. **Pass:** under the Name, "Lato's licence keeps the name "Lato" for the original font. A font made from it is a changed copy, so give it another name.", and **Build font** stays on. Type `Mine` instead: the warning goes.
13. Open Lato's page again and tap **Save to phone**. **Pass:** Android's save screen offers "Lato.zip"; save it to Downloads: the message "Saved Lato.zip", and in a file manager the zip holds the Lato `.ttf` files and `OFL.txt`.
14. Open Roboto's page and tap **Delete**. **Pass:** no question, the message "Deleted Roboto", **Download** again, and the card loses DOWNLOADED. The Roboto font you made in step 10 stays in My fonts.
15. Open Noto Sans JP's page, tap **Download**, and tap **Cancel** while the bar moves. **Pass:** "Cancelling…", then **Download** again with no error, and no DOWNLOADED tag. (If the download ends before you can tap Cancel, **Delete** it and write that down.)

## E. Licences and colour (2 minutes; internet)

16. Open Ubuntu's page. **Pass:** **Licence** "Ubuntu Font Licence 1.0" and the note "Ubuntu is under the Ubuntu Font Licence: a font made from this family must have another name, or "Ubuntu derivative" and a name of your own."
17. Open Bungee Spice's page. **Pass:** COLOUR on its card, and the note "This family has colour glyphs. Make a font needs a plain text font, so this one may not work there."

## F. Offline (3 minutes)

18. Turn airplane mode on, close the app from Recents, open Custom Fonts and the Google Fonts page. **Pass:** the list shows at once; search, menus and chips work; the cards you saw before still draw in their fonts, and the others show their name with "No preview".
19. Open a family you have not opened before, such as Merriweather. **Pass:** About says "The download failed (…). Check the connection and try again." (Android's reason in the brackets) with **Retry**; **Download** gives the same sentence. Close the page.

## G. Emoji samples (4 minutes)

20. Still in airplane mode, scroll to Emoji fonts. **Pass:** under Noto Color Emoji, Twemoji, Blobmoji and OpenMoji, sixteen emoji each (a grinning face, a thumbs up, a dog, a slice of pizza, a rocket and others), each row in that font's own art, different from each other and from the phone's own emoji. A row may be empty for a moment but never shows the phone's emoji, and none says "This phone's WebView is too old to draw these emoji." (that is only for a WebView older than version 98). Each row's **Download** (or **Delete**, for a font already downloaded) is still there.
21. The iOS row, **if the iOS emoji are not downloaded:** it says "Reads about 0.3 MB of the iOS emoji file." with **Show these emoji**. Tap it in airplane mode. **Pass:** "Reading the iOS emoji…", then "The download failed (…). Check the connection and try again." and the button again. Turn airplane mode off and tap it again. **Pass:** "Reading the iOS emoji…", then sixteen small pictures of Apple's emoji.
   **If the iOS emoji are downloaded:** it says "Read from the downloaded file.". Tap **Show these emoji** in airplane mode. **Pass:** the sixteen pictures, with no connection.
22. Close the app from Recents and open Custom Fonts again, in airplane mode. **Pass:** the iOS row shows the pictures at once. Turn airplane mode off.

## Report

Note the phone model, Android and One UI versions, security patch date, the version of Android System WebView, and the step number of anything that failed, then open an issue at https://github.com/Bingblop/ADB-Application-Manager/issues (or About > Report a bug or idea).
