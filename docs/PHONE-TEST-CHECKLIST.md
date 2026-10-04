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

## v7.4: 7z / rar / tar, passwords, Compress, code colours

- [ ] Open a real `.7z` made by 7-Zip (plain, then solid): the header says "7Z" (and "solid" for the solid one); browse, preview a text file, **Extract…**.
- [ ] Open a `.rar` made by WinRAR or `rar` (RAR4 and RAR5 if you have both): browse, preview, extract. Confirm there is no **Compress…**/edit option for it.
- [ ] Open a `.tar.gz`, `.tar.xz` and a bare `.gz`: the bare one shows as the one file inside; all three browse and extract.
- [ ] A 7z made with `7z a -mhe=on -ppw123`: opening it asks for a password before it can even be listed; a wrong one says so and asks again; the right
      one opens it and the header says "password-protected".
- [ ] A zip made with `zip -e` (or an AES zip from 7-Zip/WinZip): the file list shows, but opening the one encrypted entry asks for a password mid-browse,
      without closing the archive.
- [ ] **Extract…** on a password-protected zip or 7z without a password set yet: it asks, then the extraction continues with the password given.
- [ ] **Compress…** a single photo as a bare `.xz`: the result opens in `xz -d` / another phone's archive app. Compress a folder of a few files as `.tar.gz`:
      it opens with `tar -tzf` and the contents match.
- [ ] **Compress…** a selection of files as a password-protected zip (AES-256), leave the password empty for a plain one next to it: open both with another
      archive app / `unzip -P` to confirm the password works (and that the plain one needs none).
- [ ] Compress as `.7z` with a password: confirm with `7z l archive.7z` (no password) that the file **names** themselves are not shown.
- [ ] Edit a `.tar.gz` or `.7z` in the archive browser (rename, delete, add a file): the result reopens and the change is really there; re-extract it
      elsewhere to double check.
- [ ] Open a text file, a `.java`/`.py`/`.sh`/`.json`/`.xml` file and a plain `.txt`: the first four show in colour (comments, strings, keywords); the
      `.txt` stays plain. The **Colors** and **Wrap lines** switches in the editor do what they say and are remembered after closing and reopening.
- [ ] A very large source file (several MB): colouring does not noticeably slow down opening or scrolling (it should give up and show it plain past a size
      the app considers too big).
