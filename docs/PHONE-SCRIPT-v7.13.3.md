# Phone script for v7.13.3

One page, about 20 minutes, one pass or fail per step. v7.13.3 changes only how screens look and how controls are hit; no command does anything different. Nothing in it has been run on a phone by the people and tools that wrote it.

**Before you start:** install the `arm64-v8a` file of v7.13.3 over your current version (an update, not a fresh install). About says 7.13.3. Any working mode will do. Look at the app at normal font size first; steps 11 and 12 use a long language (German) on a narrow screen.

## A. The Apps tab (6 minutes)

1. Apps tab: the list starts right under the filters, without scrolling past a big header. Each filter pill shows its count and the counts match the list when you filter. 3rd Party | System and Enabled | Frozen light the side that is on; the › at the end of the filter row appears only while there is more to scroll to.
2. Tap an app's **icon**: a ✓ badge appears on it, the row is tinted and the batch count goes up. Tap it again: deselected. Tapping the name selects too.
3. Long-press an app's icon: the icon is saved and the row is **not** selected. Long-press the name: the app's menu opens. The gear and ⋯ are easy to hit without catching each other, and a long name such as "Amazon Shopping" fits on one line. With TalkBack the icon reads "Select <app>, checkbox".
4. Search for something that does not exist: "No apps match your search." with **Clear search**. Turn on a filter with no matches: "No apps match your filters." with **Clear filters**. Each button brings the list back.

## B. The File Manager (4 minutes)

5. Files tab: the first files show without scrolling. Tap **⋯ More**: the "File Manager options" sheet slides up; Back or a tap outside closes it.
6. In ⋯ More, tick **Show hidden files**: the list updates behind the sheet. Tap **/system**: the sheet closes and the folder opens. **＋ Add storage** still opens Android's folder picker. A search uses the scope and tick boxes set in the sheet.
7. Open an empty folder, then a folder with only hidden files: the second offers **Show hidden files** and pressing it lists them.

## C. Sheets, menus and the rest (6 minutes)

8. Open an app's ⋯ menu at your normal width: dates and sizes wrap only between words, both copy chips are fully visible, the ✕ is easy to hit. Tap ✕ on the More sheet, the batch menu and the Help Guide: each closes. With TalkBack the ✕ is read as "Close".
9. Dark theme (Material 3 and Sunset): the app menu's **Freeze** and **Uninstall** buttons are now pastel blue and red and read clearly; the USER, SYSTEM, FROZEN and MOD badges read clearly. In light Material 3 the same badges are readable.
10. Task Manager shows two even rows of three tabs. The header's mode badge is one line and the settings gear is a plain outline. Open the Terminal without scrolling: the extra-keys row is fully above the bottom bar, also after the keyboard closes.
11. In German: an app's ⋯ menu shows a fully German date line ("Installiert: … • Aktualisiert: …"). At 320 to 360 dp the Apps rows, their badges, the app menu header and the Doze labels in Extra fit without cut-off text.
12. Tap the intro on SD Maid, Hidden Settings or a store: it opens, tap again closes it. Links in the SD Maid and Morphe credits still open. "Ask agent" on Hidden Settings and Overlays rows reads as a quiet button and turns accent while pressed.
13. Debloater with nothing selected: Uninstall, Disable, Restore and Save to List are dimmed and do nothing; select an app and they work. A toast reads clearly in both themes, and the Console tab's "?" covers no tab name (also in Arabic).
14. Tap just beside small chips, the "?", checkboxes and File Manager tick boxes: the tap still lands on them. Swipe the filter-pill and sort rows sideways: they do not wobble up and down.

## Report

Reply on the release with the step numbers that **passed** and the ones that **failed**, and the phone model, Android version and font size. A short "steps 1-14 pass on Pixel 8, Android 15" is enough.
