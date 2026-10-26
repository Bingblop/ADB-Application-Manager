# Phone script for v7.17.0

About 40 minutes, one pass or fail per step. v7.17.0 brings one change (`CHANGELOG.md`, `## v7.17.0-Pro`): a new **Custom Fonts** tab that makes a Samsung font package from a font file (a Regular, a Bold from a second file or the same variable font, colour emoji inside, a name), downloads five emoji fonts, installs, exports and makes the packages again, and with Root (su granted) or Shizuku running as the system user (UID 1000) or root (UID 0) puts a font on the phone directly; with Root or Shizuku as UID 0 it can also make a downloaded emoji font the phone's emoji until the next restart. Nothing in this script has been run on a phone by the author; the font engine was checked on a computer only. Sections A to D work on any phone (C needs internet); section E needs a Samsung phone; sections F and G need Root with su or a Shizuku started as the system user or root (G: root only). The longer checks are `docs/DEVICE-TEST-CHECKLIST.md` sections 41 to 46.

**Before you start:** install the `arm64-v8a` file of v7.17.0 over v7.16.2 (an update, not a fresh install). Put in Downloads: a static font with a Regular and a Bold file (for example Lato), and a variable font (for example Inter or Roboto Flex). Write down the One UI version and the security patch date (Settings > About phone > Software information).

## A. Start (1 minute)

1. Open the app. **Pass:** About says 7.17.0; your settings, saved lists, keys and pinned tabs are still there. The **More** sheet has a **Custom Fonts** tile (with an "Aa" icon) after Root Features, right before About.

## B. Make a font (8 minutes; any phone, no working mode)

2. Custom Fonts > Make a font > Regular > **Choose**, pick the Regular file. **Pass:** once the app has read the file (until then Choose and Build font stay greyed out), the row names the file, "… · N glyphs · TrueType outlines" (or CFF), a preview draws the sentence in your font, and the Name box holds the family name.
3. Bold > **Another font file** > Choose the Bold file. **Pass:** the preview's bold line is drawn in the Bold.
4. Tap **Build font**. **Pass:** a bar names each step, then "Lato is ready in My fonts." (your Name), and My fonts lists it with its first line drawn in the font, "Regular and Bold", Not installed.
5. Clear the Name and type only spaces. **Pass:** the hint "The name needs at least one letter, digit or sign that can be seen." and Build font greyed out. Type a name again.

## C. Variable font and emoji (8 minutes; any phone, internet for the download)

6. Emoji fonts > **Download** on Noto Color Emoji (on Android 12 or older: Twemoji). **Pass:** a bar counts the MB, then "… downloaded" and the row says Downloaded.
7. Make a font > Regular > Change, pick the variable font. **Pass:** the row ends with "Variable font"; a panel with Style and sliders opens; Bold stays **Another font file** with the Lato Bold, because you changed it in step 3 (Bold switches by itself only while it has never been changed), and the Name keeps what you typed in step 5. Set Bold to **Same variable font, another weight**: its own panel opens at about 700. Type the variable font's name in Name, so that its row in My fonts can be told apart from Lato's. Move the Regular's Weight slider: Style reads Custom and the preview follows.
8. Emoji > the downloaded emoji font; Build font. **Pass:** "Fixing the variable font at the chosen weight..." and "Putting the emoji in..." show, then the font is in My fonts with "Emoji: …" on its line and a bigger size.

## D. Export and Delete (3 minutes; any phone)

9. On a My fonts row tap **Export**, save to Downloads. **Pass:** "Saved ….apk", and the file is in Downloads.
10. Tap **Delete** on the variable font's row, then the red Delete. **Pass:** "Delete …?" first, then "Deleted …" and the row is gone.

## E. Install and Make again (8 minutes; a Samsung phone)

11. Look above the rows. **Pass:** on One UI 8.5 (or 8.0 to 8.4 from the March 2026 patch) a box says Samsung's Font style does not apply fonts made on this phone and to use Apply; on older One UI there is none.
12. With a working mode on, tap **Install** on the Lato row. **Pass:** the app's own install question names "a font package this app made (Custom Fonts, My fonts)" and the matching SHA-256; after Install, "Installed" and the row reads Installed with Uninstall.
13. Samsung's Settings > Display > Font size and style > Font style. **Pass on older One UI:** your font is listed by its Name and picking it changes the phone's font (pick Default again afterwards). **On One UI 8.5:** write down whether it is listed and what picking it does.
14. Back in the app, **Make again** on the Lato row, choose the Regular (and Bold) again, Build font. **Pass:** "… is made again in My fonts.", the Version is one higher, and the button reads **Update**; Update installs over it without asking to uninstall.
15. **Uninstall**. **Pass:** "Uninstalled" (with a working mode), and the font stays in My fonts.

## F. The font changer (6 minutes; Samsung, Root with su or Shizuku as UID 1000 or 0)

16. Without such a mode (Shizuku over ADB, Wireless Debugging, ADB TCP). **Pass:** the Font changer's tag reads LOCKED with "Needs Root or the system user", and Apply on a row carries a small lock; a tap asks "The font changer needs Super User. Open Root Features to check if your device supports Super User?" and nothing changes. On a phone with su, the tap first switches the working mode to Root and su is asked. Refuse it: the mode goes back to the one you had ("Super User was not granted. The working mode stays …"), the same question follows, and nothing changes. If you allow it, or su allows this app without asking because it was allowed before, the changer unlocks and Apply puts Lato on the phone at once. Then tap Reset to Default before step 17.
17. With Root (su granted) or Shizuku as the system user: the tag reads ROOT or SYSTEM USER, Phone font reads Default. Tap **Apply** on Lato. **Pass:** "The font is applied." (or "… once they restart."), the row reads In use, Phone font names it, and the read-back box lists sans.loc and the files with their checksums. Settings, the lock screen and other apps use Lato.
18. Restart the phone. **Pass:** Lato is still the font.
19. **Reset to Default**. **Pass:** "The default font is back.", Phone font reads Default, apps use the phone's own font.

## G. System emoji (5 minutes; Root with su, or Shizuku as UID 0)

20. With Shizuku as UID 1000 (if you have it). **Pass:** the System emoji card reads LOCKED, "Needs Super User".
21. With root: download the iOS emoji (41.8 MB) under Emoji fonts, then on the System emoji card pick it and tap **Use as the phone's emoji**. **Pass:** the read-back box fills in; close Samsung Notes (on another make, any notes app) from Recents, open it again and type a few emoji: in the note they are Apple's. Apps that stayed open keep their old emoji until they start again, and the keyboard's own emoji list may too; Soft reboot starts them all again. If the card instead says the phone's emoji were updated by Google Play services and it does not run: write that down; nothing changed.
22. **Undo**. **Pass:** "The phone's own emoji are back."; reopened apps show the phone's emoji.
23. Use as the phone's emoji again, then restart the phone. **Pass:** the phone's own emoji are back after the restart, and the card offers no Undo.

## Report

Note the phone model, Android and One UI versions, security patch date, working mode, and the step number of anything that failed, then open an issue at https://github.com/Bingblop/ADB-Application-Manager/issues (or About > Report a bug or idea).
