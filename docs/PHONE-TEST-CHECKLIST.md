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

## v7.5: Task Manager

- [ ] Open **Task Manager** (after Logcat Viewer) with no working mode connected: **Processes** and **GPU** show the "needs a working mode" card;
      **CPU**, **RAM**, **Battery** and **Network** work anyway, with live numbers and a moving graph.
- [ ] Connect a working mode: the gate on Processes and GPU goes away without leaving the tab; the process list fills in, sorted by CPU, and
      **Sort by memory** re-sorts it live as new readings arrive.
- [ ] Press the **✕** on a running app's row: it force-stops immediately, with **no confirmation dialog**, and the list refreshes right away.
- [ ] CPU: the per-core row count matches the phone's real core count; load a core (anything that keeps the CPU busy) and watch the graph and the
      per-core numbers respond within a couple of auto-refresh ticks.
- [ ] RAM: the used/available/swap figures roughly match another tool (`dumpsys meminfo`, a system monitor app).
- [ ] GPU: on a phone with an Adreno or Mali GPU, scrolling something heavy (a game, a long list) moves the number; on anything else it says
      "Not available on this phone" instead of showing a wrong reading. If it falls back to the clock-speed approximation, the note under the graph
      says so.
- [ ] Battery: unplug and plug the charger (AC, then USB if you have both) and watch the status line follow in a few seconds; the **°F/°C** and
      **mA/µA** switches change the shown numbers immediately, with no new reading needed.
- [ ] Network: start a download or a video call and watch the ↓/↑ numbers and the graph move; switch Wi-Fi off and on and confirm the interface
      list updates.
- [ ] **Auto-refresh** off vs a short interval: with it off, numbers only move after **Refresh now**; with a short interval, they move on their own.
      **Graph speed** at its fastest vs slowest: the line on the graph visibly redraws more or less often (this does not change how often a fresh
      reading is fetched).
- [ ] Leave the tab (switch to another one) and come back: the chosen sub-tab, units, sort and both sliders are exactly as you left them, and the
      graph starts collecting again (it does not pick up where it left off — v7.5 does not keep readings across a visit).
- [ ] Leaving the tab stops the readings (check battery/data use does not creep up while on another tab for a while), and switching Working Modes
      while the tab is open (e.g. turning off Shizuku) is reflected without restarting the app.

## v7.8: Terminal and Coding Agents

- [ ] The tab bar shows **Terminal / ADB Console** on two lines. It opens on **Terminal**; switch to **ADB Console**, close the app (swipe it away),
      open it again: the tab opens on ADB Console. The ADB Console works exactly as before (Rish mode, cheat sheet, saved commands).
- [ ] **Shell: This app**: `ls`, `cd /sdcard` (may be refused: that is Android, not the app), `export X=1` then `echo $X` in a second command,
      a long `ping -c 5 1.1.1.1`, then CTRL-C on one: it stops and the prompt comes back. The extra keys (ESC, TAB, arrows, HOME, END) do what they say
      with the phone's keyboard open.
- [ ] **Shell: Working mode** with Shizuku, then with Root (and over Wireless ADB if you use it): `id` shows uid 2000 (shell) or 0 (root); `pm list
      packages | head` works; leave the tab and come back: the same shell is still there (same folder, same variables).
- [ ] **Termux setup** (gear, Termux setup) on a phone without Termux: it says so and links to F-Droid/GitHub. Install Termux from F-Droid, open it
      once, paste the `allow-external-apps=true` line from step 3 into Termux, tap **Allow** (Android asks "Run commands in Termux environment"), then
      **Test**: it says Termux answered. **Use Termux as the shell**: `bash --version`, `pkg install python -y`, `python -c 'print(1+1)'`.
- [ ] Deny the Termux permission twice: the sheet explains how to allow it in App info. A Google Play copy of Termux: the sheet warns about it.
- [ ] `nano test.txt` (or vim, top, a bare `python`) in the Termux shell: the Terminal offers to open it in Termux, and **Open in Termux** opens a real
      Termux window that runs it.
- [ ] **Coding Agents: Claude**, **ChatGPT**, **Gemini** with a real API key each: a wrong key is refused with the provider's message and nothing is
      saved; the right one is tested and saved, and the Model list fills in. Ask "list the files here and make hello.py print hi": every command and
      file change is shown first; **Run** / **Apply** works, **Skip** stops it, `/undo` takes the file change back.
- [ ] Switch from Claude to Gemini (or to another model) in the middle of a chat: the new one knows what was said before. **STOP** during a long
      answer ends it at once; **New chat** starts over.
- [ ] **Cursor** with a Cursor API key (optionally a GitHub repository in its sheet): a cloud agent starts and its answer streams in. **Copilot**: install
      the Copilot CLI from its sheet (Termux needed), sign in with **Sign in**, then chat.
- [ ] **Sign in with subscription** for Claude (Claude Code), Gemini (Gemini CLI) or ChatGPT (Codex): the install steps run in the Termux shell (the first
      time installs Debian, several minutes); **Sign in** opens Termux with the tool's own website sign-in; afterwards the chat goes through it.
- [ ] **Ollama (on-device)**: **Install on this phone** installs Ollama in Termux and a small model; chatting works offline afterwards. **Jan.ai** /
      **AnythingLLM** on a computer on the same Wi-Fi: enter its address, **Connect**, chat.
- [ ] **Settings** (the gear in the Terminal): saved keys show only by their hint (never the whole key); **Forget** removes one; the default agent,
      models and shell are used after restarting the app; with "Ask before running commands" off, commands run without asking.
- [ ] Reboot the phone: the saved keys still work (they are sealed by the phone's keystore). Clear the app's data: they are gone.
- [ ] Switch the app's language (Settings, Language): the Terminal's buttons, sheets, Help and its [notes] are translated; commands, output, file
      names and model names stay as they are.
