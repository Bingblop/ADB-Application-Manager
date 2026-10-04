# On-device checklist

The automated tests (`tests/`) drive the page with a mock Android bridge and the file code on a desktop JVM. They cannot try the real thing: a real
WebView, real storage, real notifications, Android's own PDF renderer. This is what to try on a phone, release by release. Send a screenshot, or
`adb logcat -d | grep -i bingblop` (or the Logcat Viewer tab), for anything that does not behave as written.

Tick the box when it works; write what you saw when it does not.

## v7.1: language and font

- [ ] **Settings > Language** is the first card. Choose Español: tabs, buttons, sheets and the Feature List change at once, no restart.
- [ ] Close the app completely and open it again: it starts in Español (no flash of English).
- [ ] An app called like a word of the language (for example one named *Settings*, *Camera*) keeps its name in the list.
- [ ] العربية: the layout runs right to left; paths such as `/storage/emulated/0/Download` are not scrambled; nothing scrolls sideways.
- [ ] Choose English again: everything is back as it was.
- [ ] **Settings > Font > Choose a file…**: pick a `.ttf` from Downloads. The sheet shows the sample; **Use this font** changes the text of the app.
- [ ] **Find fonts on this phone** (needs All-files access): the progress bar moves, fonts are listed with their real names, a variable font works.
- [ ] Restart the app: the font is still in use. **Use system font** brings the normal one back.
- [ ] The home-screen widget, the quick tiles and notifications stay English (known).

## v7.2: file manager

- [ ] Every folder list starts with a **".."** row with a folder drawing; it goes up one folder, also in an empty folder.
- [ ] **＋ File** creates a file; a text file opens in the editor. A name with a slash, `..` or a taken name (also `A.TXT` next to `a.txt`) is refused.
- [ ] **Show hidden files**: names that begin with a dot appear dimmed; unticked, the list says how many are hidden.
- [ ] A folder of photos shows **thumbnails** (portrait photos upright, a transparent PNG on white, a video shows a frame). A fast scroll stays smooth.
      **Clear thumbnail cache** says how much it freed.
- [ ] Paste a file into a folder where the name exists: **Replace / Skip / Keep both** each do what they say (check the file contents).
- [ ] Copy a 1 GB file: **Cancel** stops it and leaves no half file.
- [ ] **Open with…** on a PDF, a video and an APK: the chooser lists real apps and the file opens. **Share** works.
- [ ] **Edit** a text file (also one with Windows line ends): Save works, closing with unsaved text asks, a file open in two places warns that it changed.
- [ ] **View** a photo (Fit / actual size), a **PDF** (Previous / Next, a long page), a `.ttf` (sample).
- [ ] Without any working mode (no ADB/Shizuku/Root) but with All-files access: copy, move, delete, rename and ＋ Folder all work.

## v7.3: search, extract, long jobs, Find APKs

- [ ] Search `.jpg` in **Internal storage**: results appear with a progress line; **Stop** stops it; a result opens; the arrow shows it in its folder.
- [ ] Untick **Include subfolders**: only the chosen folder is searched. Tick **Search inside archives** and search for a name inside a zip.
- [ ] `content:word` finds a text file by what is in it; the matching line is shown. `size:>50mb`, `date:7d`, `type:video` work together.
- [ ] **Extract…** on a zip: into a new folder named after it; again with **Keep both**, **Skip**, **Replace**; **Delete the archive afterwards** deletes it
      only after a complete extraction.
- [ ] Copy a large folder and **leave the app**: a notification shows percent, speed and time left and the copy finishes; **Cancel** in the notification
      stops it. (On Android 13+ allow notifications for the app.)
- [ ] **Installer > Find APKs**: duplicates and older versions are flagged; **Select duplicates and older versions**, **Delete selected**, then **Undo**
      restores all of them.
- [ ] Install an APK from storage: the result sheet offers **Delete the installer file**; it goes to the trash, **Undo** brings it back.
