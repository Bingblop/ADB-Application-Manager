# Changelog

## v6.1-Pro (versionCode 610)

- **🎨 A bigger theme button.** The 🎨 button at the top of the screen (it opens Colors & Themes) is now 44 × 36 px with a bigger
  icon, where it was about 40 × 25 px, so it is easier to hit.
- **✨ The big counters show the list you are looking at.** On the Applications tab, the cards at the top (Total installed, Running,
  Frozen / disabled, User apps, System apps, Bloatware) light up with a colored border and glow for the filter that is on. They
  follow the filter pills below them (and the pills follow them), and the last filter is lit again when the app opens. A filter
  with no card of its own (Suspended, Updated 7d, Patched …) lights none. The cards also work from a keyboard and for screen
  readers: each is a button with a pressed state.
- **💡 A tip under Export and Share CSV.** It says to scroll the filter pills sideways for more filters. It goes as soon as you
  scroll them (or tap its ✕) and stays gone.
- **🔐 Permissions on first launch.** The first time the app opens, a sheet offers the three accesses Android keeps in its
  settings: **All files access**, **Usage access** and **Display over other apps**. **Allow** opens that screen of Android's
  settings; **Allow all** walks through the ones still missing; with ADB, Shizuku or Root the app switches the last two on by
  itself, with no screen. Skip any of them with **Not now**: they are also under **About → 🔐 Permissions**. After this update
  the sheet comes once, if one of the three is missing. It waits for What's new to be closed first.
- **📁 File access is asked for when an action needs it.** When opening, editing, saving, adding or deleting a file, reading a
  package from storage, listing a storage folder you chose, or searching storage fails for want of All-files access, a sheet
  says what was being done and has an **Allow** button. When the access arrives the sheet closes and the action carries on by
  itself: the folder is listed again, the search starts again, the package is read again (an action asked for more than five
  minutes ago is left alone). **Not now** cancels it. The failures that follow straight away (one action, many files) do not ask
  again, but a button you press yourself (Go in the file manager, Find APKs) asks every time. The Files tab and the search the
  Installer starts when it opens only show a note with a button, not a sheet. With ADB, Shizuku or Root the storage is read
  without the access, so nothing is asked. A failure on a path the access cannot help with (system folders, other apps' data)
  asks for nothing. On Android 10 the app now also asks Android for the old file access (`requestLegacyExternalStorage`), which
  its storage permission needs there to reach files at all.
- **🚀 Launch Application and ⚙️ Application Settings after an install.** When the Installer reports a success, its result
  dialog has two more buttons above **Done**: **Launch Application** opens the app that was just installed (a toast says it
  went through; only a failure says why) and, under it, **Application Settings** opens Android's page for it. The dialog is
  taller to make room, and the output box keeps its height. On a short screen the dialog scrolls instead. Both buttons act on
  the package that was installed, even if you opened another one while the install ran.
- **⬇️ The Installer scrolls to the package.** After you pick a file, also from the Find APKs list, the page glides down to the
  box with the package's name, version and signature (it does not move if you have left the tab, and it does not animate when
  your phone asks for reduced motion).
- **📊 A progress bar for Find APKs on this device.** A bar under the status line shows how far the search is with the number of
  files found so far. Where the search cannot tell (the one shell command of a working mode) the bar sweeps instead.
- **🗑️ Delete a found package from the phone, with Undo.** Press and hold a file in the Find APKs list and confirm: the file is
  removed from storage and an **Undo** bar stays for eight seconds. A tip under the list says so. While the bar is there the
  file waits in a hidden folder on the same storage; it is removed for good when the bar goes, when you leave the Installer or
  at the next start (if the app cannot reach storage then, at the one after). If a file with the same name has appeared in the
  meantime, Undo restores yours as "name (2)" rather than replacing it, and an Undo that fails is offered again. Only regular
  package files can be deleted this way, never a folder, and two files deleted at once cannot replace each other in the trash.
- **🧩 The splits that fit this phone are ticked.** Opening a package with splits ticks the base, the one CPU split the phone runs
  best, the screen-density split Android itself would pick for the phone's density and the phone's languages. The rest
  stay off, each with a note (another CPU, another language, a feature module …) so you decide whether to add it. A **Match this
  phone** link ticks them again after you changed things.
- **▾ Common installers and requesters.** The **installer source (-i)** box and the **requester / originating URI** box each have
  a small ▾ button at their end. It lists common values and fills the box when you pick one: Google Play, F-Droid, Aurora Store,
  Amazon Appstore, Samsung Galaxy Store, Huawei AppGallery, Xiaomi, OPPO / realme, vivo and HONOR stores, APKMirror Installer,
  Obtainium, Droid-ify, Neo Store, Accrescent, Aptoide, APKPure, Uptodown, itch.io and more for the first; Play, F-Droid,
  APKMirror, GitHub, itch.io and other addresses (with the package filled in) for the second. A **Clear this box** entry empties it.
- **♿ Keyboard and screen readers.** The found files (Enter loads one, Delete opens its delete sheet), the ✕ of the tip, the 🎨
  button, the three Allow buttons (each named for what it allows) and the two ▾ lists work from a keyboard and say what they are.
  Pressing and holding a found file no longer starts Android's own text selection, and the permission prompt is painted over
  every other sheet, so it cannot hide under the one that raised it.
- **☑️ "Select all splits by default" is off by default.** The switch at the bottom of the Installer's options starts off (only
  the base and the splits that fit the phone are ticked). It still ticks every split when you turn it on.

## v6.0.4-Pro (versionCode 604)

- **🚀 A launch that works shows no dialog.** In an app's **Components** tab, **Launch** now just opens the activity and a toast
  says it went through. The sheet with Android's answer opens only when the launch fails, so a launch that worked no longer leaves
  a dialog to close.

## v6.0.3-Pro (versionCode 603)

- **⬆️ The app menu is a little taller.** The sheet that opens from ⋯ on an app now stands 93% of the screen high instead of
  85%, so the lists under its buttons (Permissions, App Ops, Components, Manifest) get more room: 64 px more on a 360 × 800
  screen. Only this sheet changed; the others keep their height, and the strip above it still closes it when you tap it.

## v6.0.2-Pro (versionCode 602)

- **📤 The Share APK button is gone from the app menu.** Tap ⋯ on an app and the action grid no longer has it. **Extract APK**
  is still there and still saves the app's `.apk` (or an `.apks` bundle for a split app) to Downloads and tells you where.
  Nothing else in the menu changed.

## v6.0.1-Pro (versionCode 601)

- **🛠️ The Settings tab is now called Hidden Settings.** "Settings" sounded like this app's own settings, but the tab reads
  and edits Android's own **Global**, **Secure** and **System** settings tables, so it now says so, with a 🛠️ instead of the
  gear (its card reads "Android's hidden settings"). Nothing else about it changed, and what you had saved (the log of
  changes with its Revert buttons, the table, filter and sort you last used) is kept.

## v6.0-Pro (versionCode 600)

- **🌈 An Overlays tab: recolor Android and switch its overlays.** A new tab right after Settings with two sub-tabs.
  **Theme** changes the Material You theme of Android 12 and newer: the source color the whole system palette is built
  from (your **wallpaper**, which is Android's default, or **any color**) and one of six styles (**Tonal Spot**,
  **Vibrant**, **Expressive**, **Fruit Salad**, **Rainbow**, **Spritz**). **Overlays** lists every overlay that
  `cmd overlay list` reports and switches each one on or off. Both go through ADB, Wireless Debugging, Shizuku or Root; with
  none of them ready the tab says so and opens Working Modes.
- **Pick a color the way you like.** A hex box (`6750A4`, `#6750a4`, `#abc` and `FF6750A4` are all understood), **hue /
  saturation / lightness** sliders, or **657 named presets** (searchable, with Red / Orange / Yellow / Green / Teal / Blue /
  Purple / Pink / Neutral chips). The last eight colors you applied are kept as chips.
- **Colors in use.** The five tonal palettes Android is using right now (Accent 1–3, Neutral 1–2, ten steps each) are read
  from the system and drawn as swatches; tap one to copy its color. They redraw by themselves a moment after a change,
  once Android has repainted, and on Android 11 and older the tab says that Material You needs Android 12.
- **Overlays, easy to handle.** The list is grouped by the app each overlay restyles, with how many are on in each group;
  search it, narrow it with **On**, **Off** and **Theme** chips, **tap the switch or press and hold the row** to flip an
  overlay, or tap the row for its sheet (state, target, **Switch on / off**, copy the name, the target or the exact `cmd
  overlay` command). Overlays Android lists as unavailable say so and are not offered as switches. Long lists are drawn 120
  rows at a time.
- **Honest about what happened.** An overlay change is read back by listing the overlays again in the same command, so a
  row shows what Android reports; an overlay that is fixed on or off by the system says so instead of pretending, and Android
  12's bare "commit failed" is called what it is (a refusal with no reason). A theme is read first, then written as the
  color members Android's own wallpaper picker writes, **keeping the other choices the setting holds** (a font, icon shape
  or icon pack on Pixel-like phones) because Android switches off whatever the setting leaves out, and read back. Phones with
  Samsung's "wallpaper colors" switch get it set to match and read back too: **Undo** puts it back to what it was, a theme
  Android refuses puts it back at once, and a switch that will not change is reported. Only fixed shapes can be written (six
  hex digits, one of six styles, wallpaper or color); overlay names are single-quoted, and names that could confuse `cmd overlay`
  (spaces, control characters, a leading `-`, over 300 characters) are refused before anything runs. When the link drops
  mid-change the app says it cannot tell whether it went through and reads the state again; a listing that fails after a change
  is not mistaken for "no overlays".
- **Undo, a log, and surviving the restart.** Every overlay or theme change shows a bar with **Undo**; theme changes are
  also listed in **Settings → ⋯ → Changes** with a **Revert**. Android restarts apps when the palette or an overlay
  changes, before or after the answer arrives, so the app keeps a short-lived note of the change and, when it comes back,
  returns to the tab with the Undo bar (and finishes the log entry the answer would have made). Back closes the overlay sheet,
  then the preset list, then leaves the tab.
- **Android 11 and older** open on the overlay list: Material You needs Android 12, so Apply theme and Default are off there
  and say why. A style Android knows but this page does not list (Monochromatic, say) is shown as it is.
- **Checked before it shipped.** Three independent review passes over the new tab (the native side, the page, and a second look at the logic the first fixes
  added: 26 findings, 25 fixed and one left on purpose, because overlays are switched for the phone's main user), plus 551 checks of the
  native rules (including round trips through a real `sh` for hostile overlay names and theme values, and every step of an
  Apply, Undo, Default and refusal against a fake phone), a comparison of the page's color check with the Java one over more
  than 1,300 cases, and a headless-browser pass over both halves of the tab at 320 and 360 px in light and dark.

## v5.9-Pro (versionCode 590)

- **⚙️ A Settings tab: read and edit Android's hidden settings.** A new tab before Store lists every setting in the
  phone's **Global**, **Secure** and **System** tables (what `settings list` prints), one sub-tab per table with its
  count. **Tap** a setting to edit it, **press and hold** one that is a switch (`1`/`0`, `true`/`false`, `on`/`off` or
  `yes`/`no`) to flip it, keeping the capitalisation it had, and tap **＋** to create a setting of your own in any of the
  three tables. It works through ADB, Wireless Debugging, Shizuku or Root; with none of them ready the tab says so and
  opens Working Modes.
- **Easy to find your way around.** Search matches names, values and descriptions; chips narrow the list to **Switches**,
  settings with a **Description**, or the ones **Edited** with this app; sort by name or by value. About 130 well-known
  settings carry a plain-English description (`adb_enabled` is "USB debugging"), the ones that can cut your ADB link, lock
  you out of the screen or break setup are marked ⚠️ and ask before they change, and long tables are drawn 120 rows at a
  time with **Show more**.
- **The editor.** A sheet with the value in a text box (multi-line values and anything up to 20,000 characters), quick
  values (`0` `1` `true` `false` `null` `(empty)` `-1`), **Flip**, copy of the name, the value or the exact
  `settings put` command, **Reload** from the phone, and **Delete**.
- **Honest about what happened.** Every change is checked by reading the setting back, so "saved" means the phone now
  holds that value. When Android refuses, its own answer is shown with advice (Xiaomi, Redmi and POCO phones need
  "USB debugging (Security settings)" on in Developer options for most changes). Names that can never work (empty, starting
  with `-`, containing `=`, spaces or control characters) are refused before anything runs, and values reach the shell
  quoted, so `;`, `$(…)`, quotes and `>` are stored as text and never run. One request at a time reaches the phone, in
  the order you made them, and a request nobody answers times out instead of spinning.
- **Undo, and a log of what you changed.** Each change shows a bar with **Undo**; **⋯ → Changes** lists the last 150
  changes made with this app, newest first, and **Revert** puts the old value back (or removes a setting you created).
  **Copy shown** and **Share shown** export the list you are looking at as text.
- **Back works the way you expect.** Back closes the editor, then clears the search, then leaves the tab.
- **Reviewed twice, then hardened.** Two independent reviews (the native side and the page) found and fixed: a table cut short
  by a dropped connection or a timeout is reported instead of shown as if it were complete; a refused change or delete is no
  longer taken for success when the value is the word `null`; when the link drops during a change the app says it cannot tell
  whether it went through and reads the table again; the editor opens with the value the phone holds right now, and a refused
  Save keeps what you typed; settings that are a choice (private DNS, dark theme, ringer mode, rotation …) are no longer offered
  as on/off switches; press and hold works on a touch screen however long you hold; Revert acts on the row it is shown on;
  a value too big for adb to carry is refused instead of corrupting the request; and **📋 Command** copies a command that runs
  on the phone (the old one broke when pasted into a PC terminal).

## v5.8-Pro (versionCode 580)

- **A Back button that does the sensible thing.** The system Back button / gesture now closes the sheet that is open,
  then clears a selection (apps, debloater, files), steps up a folder or out of an archive, returns to the previous
  tab, and only then warns **"Press back again to exit"**. Leaving takes a deliberate second press: a double tap
  (under 0.7 s) or a late one (over 3.5 s) just warns again, and while an install, an update download, a file job,
  a backup or a terminal command is running it asks before leaving. Nothing closes the app by accident any more.
- **Installs from Files always use ADB, Wireless Debugging, Shizuku or Root.** Installing from a file row, from inside an
  archive or right after signing never goes through the system installer: it runs on the privileged backend that is
  ready (and offers Working Modes when none is), with this app's own signature gates off so an edited or re-signed
  APK still opens. Android itself still verifies signatures (this app can't switch that off), so an APK whose signature
  Android refuses is signed with this app's key and installed, and an app signed by someone else is replaced only
  after you agree to uninstall it (its data is deleted; the copy that will replace it is prepared first, so a failure
  while preparing leaves your app alone, and with Shizuku as the backend Shizuku's own app is never the one removed).
  Failures say why and what to turn on. A key that was rotated still matches the key it replaced.
- **ℹ️ An About tab.** The new last tab shows the developer (**Bingblop**) and the GitHub repo, this build's version,
  package, device, WebView and **signing certificate** (✅ when it is the official release key), copy / share
  **debug info** for bug reports, tips, privacy and credits, and **☕ Buy me a coffee**: one tap opens PayPal to
  donate **$1**, or type any amount. Entirely optional; the app stays free.
- **Sign APKs you edited — on the device.** Editing an APK in the archive browser breaks its signature, so there is now
  a **✍️ Sign** button (and an "edited — sign it now" banner): it signs with **APK Signature Scheme v2** using a key that
  is generated inside the **Android Keystore** (in secure hardware where the phone has it, never exported). Sign in place
  or save a `-signed` copy, see the signature now / the installed copy's signer / this key's fingerprint, get a warning
  when an installed copy has a different signer, and install the result in one tap. Checked against the real
  `apksigner` for RSA and EC keys, multi-megabyte APKs and tampered files. Stored `.so` files are now 16 KB-aligned.
- **Archive browser: install, nest, compare.** **Install** an APK/APKS/XAPK from inside an archive without extracting it
  by hand, **open an archive inside an archive**, and **⚖️ Compare** two archives (or an archive and an installed app) —
  added / removed / changed files, and a **line-by-line diff** of changed text and compiled XML (a new permission shows
  up at a glance). The extract folder is remembered.
- **Terminal: history, saved scripts and pinned buttons.** Up/Down arrows and a 🕘 list recall earlier commands, ⭐ keeps
  named commands and multi-line scripts, and up to six can be **pinned as one-tap chips**. Works in the normal terminal
  and in Rish mode. The normal terminal no longer freezes the page while a command runs.
- **Logcat for one app.** Pick an app (or tap **Logs** in an app's menu) to see only the lines it wrote — across restarts,
  matched by its user ID — and **save or share** the filtered log as a bug-report text file with a header. The live view
  draws the latest 800 entries, skips redraws when nothing changed, and polls off the page's thread.
- **File manager: select many.** Press and hold a row (or tap ☑ Select), pick files and folders, then **Copy** or **Move**
  them (open the target folder, **📥 Paste here**) or **Delete** them in one go. Many files go through a few shell commands
  instead of one each, conflicts ask first, failures are listed with the reason, and system locations are protected
  (including the same storage seen through `/mnt/...` mounts and however a path is spelled, `/a/../sdcard` too). A lost
  connection stops the batch instead of waiting on every item, and a file merely *named* like an adb error isn't
  mistaken for one.
- **Rish shell hardening.** A mistake that makes some shells quit (`. missing.sh`, `export a-b=1`, `$((1/0))`) no longer drops
  you out: the shell is restarted in the same folder with a note. **STOP** is gentler (Ctrl-C first, then terminate, then
  kill — on the whole process tree, so a script's children don't linger and unrelated background jobs survive), closing
  can't leave stray processes or be undone by a restart in flight, a silent shell makes start fail instead of spinning,
  `exec >/dev/null` or `PATH=` can't hide the end of a command, a runaway background job can't flood the screen, and the
  next shell opens in the folder you left. Tested against a real mksh + toybox.
- **Archive engine fixes (from an independent review).** Editing keeps what it used to drop — **AES-encrypted** entries stay
  decryptable and Unicode names stay — and refuses (instead of silently truncating) entries of 4 GB or more; extraction
  writes to a temporary file and **checks size and CRC**, so a damaged entry can't clobber an existing file and an entry
  can never overwrite its own archive; one bad entry no longer stops a folder extraction; **CRX / self-extractor** archives
  open (view-only); duplicate names don't block unrelated edits; renaming a file to `folder/` moves it into the folder;
  non-UTF-8 names keep their bytes; odd names (`/abs`, `a//b`) are listed; a text file with mixed line endings is
  view-only instead of being silently rewritten; an unchanged text save no longer rewrites (and unsigns) the APK; compiled
  XML decoding is capped so a crafted file can't exhaust memory; temp files are never left behind.
- **Security hardening.** App labels and file names are escaped everywhere (a package whose label contained HTML could run
  script with access to the app's shell bridge), store fields from third-party catalogs are escaped, the release build is
  no longer `debuggable`, backups are off (`allowBackup=false`), WebView debugging is only on in debuggable builds, the
  WebView can't be navigated away from the app's own page, and a crashed WebView renderer is recovered.
- **Faster.** The Applications list is drawn a page at a time (250, more as you scroll) so a phone with thousands of
  packages stays responsive; typing in a long list's search waits for a pause; counts are one pass; the working-mode probe
  is cached for a moment; logcat and terminal commands run off the page's thread.
- **Fixes.** Store lists no longer show duplicates when refreshed mid-load or when switching F-Droid repos; GitHub live search
  shows every hit and reports failures without hiding the list; the storage APK search can't get stuck, pages its results
  and the installer ignores the answer for a file you picked before the current one; the **app's own "Download" update
  button** works again; categories named like JavaScript built-ins, long unbroken names and emoji initials display
  correctly; logcat's color key works from the keyboard and no longer lags on malformed lines.

## v5.7-Pro (versionCode 570)

- **Terminal: Rish mode replaces "adb devices".** Tap **🐚 Rish mode** and the app switches the working mode to
  **Shizuku** (asking for Shizuku permission if needed) and opens a **persistent Rish shell** — one long-lived
  shell running as the Shizuku shell user, so `cd`, `export` and shell variables stay put from one command to the
  next. The prompt shows the device and folder (`husky:/sdcard $`, `#` for root), output **streams in as it is
  produced**, **RUN becomes STOP** while a command runs (it ends the command and keeps the shell), and tapping
  **Exit Rish** (or typing `exit`) goes back to the normal terminal. Unbalanced quotes and other typos can't wedge
  the shell, and very chatty commands are trimmed instead of freezing the page.
- **File manager: View now opens inside .apk, .zip and other package files — without extracting them.** The
  contents appear as folders (with search, breadcrumbs and paging for huge archives). Tap a file to preview it
  (text, images, **compiled Android XML shown back as XML**, hex for binaries) and then extract it, rename or move
  it, delete it, add files, create folders, or **edit a text file in place**. Archives are rewritten safely next
  to the original and swapped in only when that worked; unchanged entries are copied as raw compressed bytes, and
  APK-style alignment is kept. Works on ZIP64, protects against path-traversal names, and installed/system
  packages open **view-only**. (An edited APK's signature no longer matches, so it must be re-signed before
  Android will install it — the app says so.) Any file can also be tried with **Open as archive**.
- **Installer: XAPK support and a storage search.** Install `.xapk` bundles (base + splits, with the game data /
  OBB files copied into place afterwards). The old **Set as default installer** button is gone — the explanation
  of how to make this app the APK handler stays at the bottom of the card — and in its place a **🔍 Find APKs on
  this device** button runs an automatic storage search for every `.apk`, `.apks`, `.apkm` and `.xapk`
  and lists them (with size, age, folder, search and type filter); tap one to load it.
- **Store: "Komi" is now "GitHub", the catalogs are complete, and every store has a category drop-down.**
  - **🐙 GitHub** (was Komi) browses the whole catalog page by page (up to 5,000 Android apps) with live search
    and direct `owner/repo` install, and falls back to a built-in list when offline.
  - **🤖 F-Droid** now lists **every app in the chosen repo** (the index is streamed, so even the very large official
    index loads), defaults to the official F-Droid repo with a repo picker, caches for 12 hours, verifies each
    download's SHA-256, and asks first on a metered connection.
  - **🪐 Orion** now shows its whole catalog (887 apps; a size cap used to cut the last 87 off).
  - A **category drop-down** filters the apps in each store by the categories they are tagged with.
- **Logcat is readable.** Every log entry is its own row (a stack trace stays together), with a **level badge and
  color for Verbose / Debug / Info / Warn / Error / Fatal**, dimmed time / tag / pid, a **color key** you can tap
  to hide or show levels (with counts), highlighted filter matches, and live updates that don't yank your scroll
  position. Copy still gives the raw log lines.
- **Debloater checkmarks match the Applications tab** — a selected row fills the whole box with the accent colour.
- **Press and hold the ✓ button to clear every selection** in the Applications tab (a normal tap still opens the
  batch actions).
- **More room in the app list** — the per-row **Force Stop** button is gone (Force Stop is still in each app's
  ⋯ menu and in the batch actions), so names and badges get the space.

## v5.6-Pro (versionCode 560)

- **The Store is now five sources, in sub-tabs.** The 🛍️ Store tab opens on **ShizuStore** and adds four
  more sub-tabs beside it:
  - **🐙 Komi** — a curated set of trusted open-source apps that publish their APK on **GitHub Releases**
    (the kind of GitHub app store [komi-store](https://github.com/komi-store/komi-store) is built for).
    Each app is resolved live to the right build for your device's ABI and installed through your active mode.
  - **🤖 F-Droid** — the known third-party **F-Droid repositories** from the community
    [known-repositories](https://forum.f-droid.org/t/known-repositories/721) list. Tap a repo to browse and
    install its apps directly, or copy its address & fingerprint to add it to your F-Droid client. (The very
    large catalogs show their add-to-client details instead of browsing in-app.)
  - **🪐 Orion** — the public [Orion Store](https://github.com/RookieEnough/Orion-Store) catalog
    (`RookieEnough/Orion-Data`), including Morphe-built apps, resolved from GitHub, Codeberg and direct links.
  - **🌌 Aurora** — an honest hand-off to **Aurora Store** for Google Play apps (the private Play API can't be
    reimplemented in-app). Google Play and APKMirror sources are intentionally not bundled.

  Every install still downloads from each app's own upstream — nothing is rehosted — and runs through this
  app's installer (ADB / Shizuku / Root, or the system installer).
- **Better patched-app detection: Morphe and same-package ReVanced builds.** The 🧩 patched detector now
  flags **Morphe** (`app.morphe.*` and the Morphe installer), and the inspector's deep scan streams each
  `classes*.dex` to recognise **ReVanced** and **Morphe** builds even when the patch keeps the app's
  original package name — the case the old signals (package, installer, manifest) missed.

## v5.5-Pro (versionCode 550)

- **Unexported activities now actually launch (ADB / Shizuku / Root).** Launching an activity that another
  app doesn't export used to fail with *"Permission Denial: … not exported from uid …"*, because the shell
  (uid 2000) isn't the activity's owner and doesn't hold `START_ANY_ACTIVITY`. The app now launches those the
  system's way: it briefly sets the target as the device **assistant**, injects **KEYCODE_ASSIST**, so the
  **system** starts the activity (which bypasses the exported check), then restores your assistant. Exported
  activities still start directly with `am start`. This is the same technique dedicated activity launchers
  use over Shizuku.

## v5.4-Pro (versionCode 540)

- **Patched / modified apps are flagged in the list.** Each app in the Applications list now shows a
  **🧩 badge** when it looks patched or repackaged by a third-party tool — **ReVanced**, an
  **Xposed / LSPosed module**, **LSPatch**, **NPatch**, or an app re-signed with a **debug key**. A new
  **🧩 Patched** filter lists only those apps. Detection in the list is free (package name, the manifest's
  `appComponentFactory` and meta-data, and the installer), so it adds no load time.
- **A definitive breakdown in the app inspector.** Opening an app (⋯ → inspector) runs a deeper scan that
  also reads the signing certificate and the APK's own entries (`assets/lspatch/`, `assets/xposed_init`,
  `META-INF/xposed/`, NPatch and ReVanced markers), and shows exactly which tool(s) touched the app and
  which installer put it there — so you can tell a genuine store build from a modified one before trusting it.

## v5.3-Pro (versionCode 530)

- **ShizuStore tab.** A new **🛍️ Store** tab (at the end) browses [ShizuStore](https://github.com/timschneeb/ShizuStore)'s
  curated catalog of Shizuku-powered apps. Search and sort by most starred, most downloaded, recently
  updated or added; open an app for its description, screenshots, star count, requested permissions and
  source link; then **Install** downloads the APK straight from the developer's own upstream (GitHub /
  GitLab / F-Droid) and installs it through your active mode (ADB / Shizuku / Root), or hands it to the
  system installer with no privileged mode. Catalog and metadata come from ShizuStore by timschneeb; APKs
  are served by each app's developer, not rehosted.
- **Optional VirusTotal scan in the Installer.** The Installer tab has a new **🛡️ VirusTotal scan** card.
  Paste your own VirusTotal API key and scan a package against 70+ antivirus engines before installing. The
  scan is a SHA-256 lookup, so **nothing is uploaded**; only if VirusTotal hasn't seen the file can you
  choose to upload it for analysis. Results show the malicious / suspicious / harmless counts with a verdict
  and a link to the full report. Entirely optional — leave the key blank to ignore it.
- **The app icon has no background.** The adaptive launcher icon is now fully transparent behind the mark,
  so it takes your launcher's own icon shape and backdrop instead of a filled square.
- **New installs start on Material You.** A fresh install now defaults to the Material You (dynamic,
  wallpaper-based) theme, falling back to the Material 3 baseline on Android 11 and older. Your saved theme
  is untouched on upgrade.

## v5.2-Pro (versionCode 520)

- **Pair over Wi-Fi from a notification.** The Wireless Debugging card has a new **🔔 Pair via Notification**
  button. It drops a high-priority notification with an inline reply box, so while Android's *Pair device with
  pairing code* dialog is on screen you can type the 6-digit code straight from the notification shade - no
  switching back to the app, so the code can't rotate out from under you. The app finds the current pairing
  port over mDNS automatically; if it can't, reply with `port code` (e.g. `37123 123456`). Pairing runs in the
  background and the notification updates with the result. If notifications aren't allowed yet, the button
  requests the permission first.

## v5.1-Pro (versionCode 510)

- **ADB cheat sheet in the terminal.** The ADB Console tab has a **📋 Cheat Sheet** button: a searchable,
  categorized reference of ~90 commands (device info, packages, app control, permissions, intents, input &
  key events, screen, connectivity, battery testing, logs, files, reboot). Tap any command to drop it into
  the input (with `<placeholders>` selected for quick editing). Commands are in on-device shell form - no
  `adb shell` prefix needed, since the terminal already runs on the device. Compiled from Pulimet's ADB gist.
  The cheat sheet can also **load the full reference live from the gist** (Pulimet's list) inside the modal.
- **The terminal input no longer auto-capitalizes.** It defaults to lower case (ADB commands are
  lower case); hold Shift to type capitals when you actually need them.
- **App icon no longer has a black background.** The adaptive launcher icon now uses a clean white
  background behind the mark.
- **First-launch permission prompt.** On the first launch the app now checks and requests the standard
  runtime permissions it uses (notifications, and legacy storage on pre-Android 11). Special-access grants
  (All-files access, usage access, overlay) are still requested in context from their own screens.

## v5.0-Pro (versionCode 500)

- **Update this app from inside the app.** The Updates tab now has a dedicated **🚀 App update** card at the
  top, just for ADB Application Manager Pro itself. It checks this project's GitHub releases, shows your
  installed version vs. the latest with the release notes, and updates in one tap. The download is verified to
  be this app, a newer version and **signed with the same key** before installing. With ADB / Shizuku / Root
  the install is seamless (the app restarts); with no privileged mode it hands the APK to the system installer
  for a normal confirmation. Checking for updates also refreshes this card.
- **Unexported activities launch reliably.** Launching an activity through ADB / Shizuku / Root now starts it
  in a **new task** (`FLAG_ACTIVITY_NEW_TASK`), with fall-backs to a plain start and `cmd activity`. Without
  the new-task flag an activity often reported success yet never appeared - the usual reason an unexported
  activity "wouldn't launch". If every method fails, each attempt's output is shown so the real error is visible.
- **Navigation tidy-up.** A 🎨 **Colors & Themes** button moved to the header (top-right), so themes are one
  tap from anywhere and no longer take a tab slot. Tabs are reordered: **Applications → Saved Lists →
  Debloater → Installer → Files → Updates → ADB Console → Logcat** (Logcat is now last).

## v4.9.5-Pro (versionCode 395)

- **The file manager resolves storage to `/storage/emulated/0`.** `/sdcard` and `/storage/self/primary`
  are symlinks, and the `self` view resolves differently for an ADB/Shizuku shell (uid 2000) than for the
  app, so a shell often can't read through them - which is why `/sdcard` could come up empty. Storage paths
  now resolve to the concrete `/storage/emulated/0`, which the app and a privileged shell read the same way,
  so storage browses correctly in every mode. The Storage shortcut and the default path use it too.

## v4.9.4-Pro (versionCode 394)

More file-manager fixes from on-device testing:

- **Fixed the `/sdcard//sdcard` doubled-path bug.** Because `/sdcard` is a symlink to the real storage
  volume, the shell fallback was listing the *link itself* as a lone entry instead of the folder's contents.
  Storage folders are now dereferenced when listed, and all paths are normalized (no more duplicate slashes),
  so `/sdcard` opens straight into its contents.
- **View and Install from the file manager no longer need a shell.** Viewing a file and installing an APK
  found in storage now read the file directly through the app's own file access (matching how listing already
  works), so they work on storage with All-files access and avoid shell-visibility limits. Privileged system
  paths still fall back to the shell.

## v4.9.3-Pro (versionCode 393)

More on-device fixes:

- **The file manager now reads storage directly.** `/sdcard` and other storage folders are listed through
  the app's own file access, which is instant and avoids the shell-visibility limits that can leave an
  ADB/Shizuku shell unable to see `/sdcard`. It falls back to the shell only for privileged system folders
  (`/data`, `/system`, ...). If storage looks inaccessible, a **Grant All-files access** button opens the
  right settings screen - after granting it, `/sdcard` lists with no privileged mode needed.
- **Logcat live tail is snappier** - shorter refresh interval and a lighter per-poll fetch while playing.

## v4.9.2-Pro (versionCode 392)

Follow-up on-device fixes:

- **File-manager folder listing is now robust across `ls` variants.** When a device's `ls -la` columns
  don't match the detailed parser, the listing falls back to a plain `ls -1p` name list (names, with a
  trailing `/` marking folders), so `/sdcard` and other folders list regardless. If a folder genuinely
  can't be read, the raw command output is shown instead of an empty screen, so the format is visible.
- **Logcat "Clear" now actually empties the on-screen terminal** (and the device buffer) and stops live
  play, instead of immediately repopulating.

## v4.9.1-Pro (versionCode 391)

On-device fixes from testing v4.9:

- **The file manager now lists `/sdcard` and other folders reliably.** The `ls -la` parser was rebuilt to
  handle both toybox and busybox date formats - it finds the time field and takes the name after it - so
  listings, names with spaces, symlink targets and sizes all parse correctly, and a permission-denied folder
  shows the error instead of looking empty. Opening the Files tab now always refreshes.
- **Logcat has a Play / Pause control.** While playing it live-updates (polls about every 1.5s) and
  auto-scrolls; it stops when you pause or leave the tab. Opening the tab always refreshes.
- **The Logcat and ADB Console panes are taller** (more terminal space).

## v4.9-Pro (versionCode 390)

Installs over v3.1 – v4.8 without uninstalling (same signing key).

- **New 📦 Installer tab — an all-in-one APK / APKS / APKM installer.** Pick a package file and the app
  reads it (package name, version, min/target SDK, size, signing certificate) before anything is installed;
  for a split bundle (`.apks` from bundletool, `.apkm` from APKMirror) it lists every split APK and lets you
  choose which to install, with the base always included and "select all splits" on by default.
- The install runs through whichever **authorizer** you pick — ADB, Shizuku, Root, or **No privilege** (the
  platform's own confirm-dialog installer, which still handles split bundles). It reuses the same
  session-based install path (`pm install-create` / `install-write` / `install-commit`) the restore flow
  already uses.
- Full install control, each mapped to the matching `pm install` flag: grant all permissions (`-g`), allow
  downgrade (`-d`), allow test packages (`-t`), install for all users (`--user all`), bypass low target-SDK
  block (`--bypass-low-target-sdk-block`, Android 14+), request update ownership (`--update-ownership`,
  Android 14+), install reason (`--install-reason`), package source (`--package-source`, Android 13+),
  installer package / install source (`-i`), and originating URI (`--originating-uri`). Privileged-only
  options are disabled automatically under the No-privilege authorizer, where the OS wouldn't honor them.
- Post-install **dex optimization** (`pm compile -m <mode>`, with an optional force recompile `-f`) and an
  optional **auto-delete** of the chosen package file once it installs successfully.
- Two safety gates before committing: **block signature mismatch** (refuse when an installed copy is signed
  with a different key, which Android would reject anyway) and **block unknown signature** (refuse an
  unsigned or unreadable package), both on by default and read straight from the APK. Packages are staged in
  the app's own cache and nothing outside that directory is ever installed.
- Toggles to show or hide the SDK, size and version readouts for the picked package.
- **Default installer.** The app now handles opening an APK file (a VIEW / INSTALL_PACKAGE intent for
  `application/vnd.android.package-archive`), so it can be set as the default APK handler. Opening an APK
  routes into the Installer tab; a privileged mode installs, and with no privileged mode it falls back to
  the normal system installer. A button deep-links to the default-apps settings.
- **Components now cover all four kinds.** The Components tab lists activities, **receivers**, **services**
  and **providers**, each with its exported / enabled / permission detail, and any component can be
  **enabled or disabled** (`pm enable` / `pm disable`). The shown state reflects the real pm override.
- **Dex optimization** is available as a single-app action (in the app menu) and as a batch action, with a
  compile-mode picker (`pm compile -m <mode>`, optional force `-f`).
- **New 📁 Files tab — a privileged file manager.** Browse any path with ADB / Shizuku / Root, view text
  files, create folders, rename, copy, move and delete, and install an APK from any location (staged to a
  readable temp, then handed to the Installer). Every path is shell-quoted.
- **New 📄 Logcat tab.** Read the device log (`logcat -d`) with level, line-count and text/tag filters
  (the filter is applied in-process, never in the shell), plus clear and copy.
- **Play Store (via Aurora) routing** in the Updates tab: detects whether Aurora Store / Play Store are
  installed, opens Aurora Store's in-app updates, and routes any installed app to its store page. (Automatic
  Play version detection is a planned follow-up, since it needs anonymous Play authentication.)
- The release workflow now builds, signs and publishes from a version tag; set the `KEYSTORE_BASE64` /
  `KEYSTORE_PASSWORD` repo secrets for a build signed with your key.

## v4.8-Pro (versionCode 380)

Installs over v3.1 – v4.7 without uninstalling (same signing key). Three more GitHub Copilot review
passes on v4.7-Pro's diff, after it had already shipped, found further genuine issues - all fixed here:

- **Unexported-activity launches from the Components tab could report success while doing nothing.**
  Android's `exported=false` denial frequently doesn't throw an exception back to a plain
  `startActivity()` call - it's just logged server-side and nothing opens - and activities without
  detailed per-activity info were defaulting to "exported" rather than "not exported". Launching an
  activity now always goes through the verifiable `am start` shell path whenever a working mode
  (ADB/Shizuku/Root) is active, regardless of the exported flag, and unknown/legacy activity info now
  defaults to not-exported instead of exported.
- Selecting multiple apps for a batch action no longer immediately covers most of the list with the
  full action panel; a small checkmark button appears in the bottom-right corner instead, which opens
  the panel on tap (and can collapse it back without losing the selection).
- **Restore no longer scans forward for `backup.json` by name.** A picked file is untrusted, and skipping
  past an unexpected entry first still fully decompresses it to find its end - a tiny zip bomb placed
  before `backup.json` would have been inflated in full before this app ever got to check anything.
  `backup.json` must now be the very first entry, or the file is refused immediately. The same
  unbounded-decompression gap applied to any other unrecognized entry during restore (not just `data.tar`,
  already fixed in v4.7-Pro, below); every entry is now read through the same byte-capped loop regardless
  of whether its contents end up kept.
- The manifest viewer's string-pool size check compared a crafted count against the whole manifest's byte
  size, which at the 32 MB cap still let a count of ~33.5 million through and allocated a reference array
  in the hundreds of MB. It's now bounded by what could physically fit in that pool's own offset table.
- Restoring app data no longer reports success when fixing the restored files' ownership or SELinux labels
  (`chown`/`restorecon`) actually failed.
- The working-mode tile considered ADB-over-TCP "ready" from an open port alone, which could select a mode
  where the device is actually offline or unauthorized; it now requires the same confirmed connection the
  other backends do.
- A backup or APK export that fails partway through writing no longer leaves the partial file behind
  looking like a finished one; it's deleted instead.
- **The update-check regex timeout could poison itself.** A timed-out match's thread is abandoned, not
  stopped, since `Matcher` can't be interrupted; running those on a small *shared* pool meant that after as
  few hostile catalog patterns as the pool had threads, every later, completely ordinary update check would
  queue behind them and never run - turning one or two bad entries into a standing outage. Matching now
  runs on its own fresh thread each time, so a stuck match only leaks that one thread instead of blocking
  everything after it. Separately, a timed-out match was indistinguishable from "didn't match", which an
  inverted asset filter could read as "nothing to exclude"; a timeout now always rejects the candidate
  instead.
- **A Root data backup could report success from a tar run that actually failed**, as long as it had
  written something to the output file first (a disk-full or I/O error partway through can do that). Only
  tar's own "some files changed while being read" code is now treated as the recoverable case it always
  was meant to be; anything else deletes the partial file and fails the backup.
- **A Root data restore deleted the app's current data before extraction had even been attempted**, so a
  failure at that point (which the preceding checks can't fully rule out) left the app with nothing instead
  of its original data plus a clean error. The current data is now moved aside instead of deleted, and
  restored if extraction or the ownership/SELinux repair after it fails.
- Editing the packages in the saved list currently used as the quick list now refreshes the Quick Settings
  tile/widget's label immediately instead of waiting for Android's next periodic update; deleting that list
  now clears it as the quick list instead of leaving a dangling reference.
- Importing a profile that lists the same package twice (even by accident) now keeps one entry for it
  instead of queuing both states' steps - including, in the worst case, contradictory ones for the same app.
- Clarified that on Android 8.0-9 (API 26-28, before `MediaStore.Downloads` existed) backups and extracted
  APKs are saved to this app's own storage instead of the public `Download/` folder, and are removed if you
  uninstall the app - this was already the actual behavior, just not previously documented.
- **Restoring permissions now applies the same dangerous-permission check backup creation already uses.**
  Without it, a crafted backup wrapped around an otherwise-legitimate, correctly-signed APK could list any
  syntactically valid permission in `backup.json` - including signature or development-level ones this
  format never actually produces - and have the privileged restore backend asked to grant it.
- A backup's filename only had minute precision; two backups of the same app and version within the same
  minute could collide on Android 8-9 specifically, where that name is opened directly as a file rather
  than through `MediaStore` (one backup silently overwriting the other, or a failed second write deleting
  an earlier valid one). Added millisecond precision.
- **Replaced the hand-rolled timeout around the two regexes matched against untrusted Obtainium catalog
  data with [RE2J](https://github.com/google/re2j), a linear-time (non-backtracking) regex engine.** Two
  earlier fixes in this same spot (a shared thread pool, then a fresh thread per attempt) both tried to
  survive `java.util.regex` hanging on a catastrophic pattern without actually being able to stop it; the
  second attempt still let a single hostile catalog entry leak an unbounded number of permanently-running
  threads over time; since `pickApk` matches the filter once per APK asset on every update check, that is
  an eventual resource exhaustion, not just a leak. RE2J has no pathological input by construction, so
  matching now runs directly with no thread or timeout machinery at all.

## v4.7-Pro (versionCode 370)

Installs over v3.1 – v4.6 without uninstalling (same signing key).

- **✨ What's new**: the changelog is bundled in the app. After an update it opens once with the changes
  since the version you last ran; **Color & Themes → About** has the button, version and links.
- **👁️ Watch a profile**: in **🗂️ Profiles**, tap **👁️ Watch** on one profile. When apps no longer match it
  (typically after a system update brings them back) a banner on the Applications tab says how many and
  opens the same reviewed Apply step. After a reboot with a new system build, a notification reminds you (Android 13+
  asks for notification permission the first time you watch a profile). Nothing is changed without your
  confirmation.
- **Quick Settings tiles and a home-screen widget** (they run without opening the app):
  - **Working mode** tile / **🔄 Mode** widget button: switches to the next mode that is ready
    (ADB TCP → Wireless Debugging → Shizuku → Root), or back to Automatic.
  - **Stop apps** tile / **🛑 Stop apps** widget button: force-stops every app in your **quick list**.
    Choose it with **🛑 Quick list** on a card in **Saved Lists**.
- **🗄️ Backup and restore**: **💾 Backup** in the app menu (and **🗄️ Backups** on the Applications tab).
  A backup is one `.adbbackup` file in `Download/ADB App Manager/Backups/` holding the APK (with splits),
  granted permissions and changed app ops. With **Root** it can also hold the app's data. Restore
  installs the APK through ADB, Shizuku or Root, re-applies permissions and app ops, and with Root puts the
  data back. You can **Share** a backup or **Choose a backup file** from another phone. Data restore
  refuses archives that would write outside the app's own folders.
  Without Root, private app data cannot be read on current Android, so backups are APK + settings only.
- **Profiles are applied properly**: a profile now remembers disabled and suspended separately, and applying
  works out the steps per app (bring back, re-enable, unsuspend, disable, suspend, uninstall), so an app that
  is in a different state than the profile wants is changed instead of being counted as matching. Undo of a
  profile run unwinds the steps newest first. Saving profiles that the app cannot keep (too large) now says so.
- Extracting or sharing an APK handles one request at a time, so a second tap cannot mix up the results; the
  share sheet gets each file from its own private folder.
- A full pass through every source file (not just this release's changes), backed by Copilot review plus an
  independent full-file audit:
  - **Restore no longer trusts a picked backup file's own claims.** `backup.json` can come from "Choose
    backup file…" and is untrusted by design; restoring used to take its stated package, version and signing
    key at face value, including on the path that brings a removed system app back. Everything now comes
    from the extracted APK itself, both for an app currently installed and for a system app only present on
    the system image.
  - A backup now correctly names the base APK `base.apk` regardless of its real on-disk name, so backing up
    a system app (whose source file is rarely actually called that) can be restored at all.
  - Restoring data now refuses archives containing FIFO or device-node entries, not just hard links and
    outward-pointing symlinks; an app-op explicitly set to Allow is backed up and restored instead of being
    treated as "nothing to restore"; skipping a backup's data when you didn't ask for it no longer
    decompresses that data anyway.
  - **Quick list entries are validated before they can reach a shell command.** The list editor accepts any
    text, and the new Stop-apps tile runs each entry with no per-entry review; a crafted entry could have run
    an arbitrary privileged command on a single tap. Every package name is now checked before anything is
    built from it.
  - Deleting a watched profile clears the setting the reboot notification reads; that notification also has a
    working baseline on the very first reboot after you turn watching on.
  - **The manifest viewer no longer risks a confusing crash on a crafted or corrupted APK** (it already
    couldn't crash the app itself): a hand-rolled binary-XML parser had several unchecked-arithmetic and
    unchecked-allocation spots reachable by viewing any installed app's manifest, including a sideloaded one.
    It also now caps how much of a manifest it will read, and closing tags are tracked properly instead of
    trusting a value a crafted file could point anywhere.
  - **Update checks no longer trust a catalog's regex unconditionally.** The Obtainium catalog is
    community-maintained; a pattern with pathological backtracking could previously hang the update check.
    Matching now has a hard timeout. The Galaxy Store download link is now required to be HTTPS, matching the
    rest of the update sources, and a download is capped in size and can no longer report an empty or
    unbounded response as a successful update.
  - A handful of smaller robustness fixes: a failure partway through generating the per-install ADB key can
    no longer leave a mismatched key pair on disk; reading a very large file no longer crashes outright.
  - A second look at that same signer check found it would skip verification entirely if either side's
    signing certificate couldn't be read at all, instead of refusing the restore; it now fails closed.
  - Package-name validation no longer rejects `"android"` itself - one of several framework-owned permissions
    and components `PackageManager` genuinely reports under that single-segment name.
  - An install through Shizuku that fails partway through writing or committing its session no longer leaves
    it open. Backing up an app whose app-ops can't be read now says so in the backup instead of silently
    recording an empty, misleadingly-successful-looking override list.
- README: debloat-flow animation, light-theme screenshots and the new features.

## v4.6-Pro (versionCode 360)

Installs over v3.1 – v4.5 without uninstalling (same signing key).

- **Copy buttons and share sheet**: 📋 Package / Version / Name chips and 📤 Share in the app menu;
  **Copy Packages** and **Share List** for a batch selection; **Share CSV** for the app list; **Share**
  for the manifest and the terminal output; **Share APK** extracts an app and opens the share sheet.
  Files go through a private, non-exported provider (`ShareProvider`) with one-off read grants.
- **Search with highlight**: the manifest viewer and the terminal highlight every match, show
  "2 / 7 matches" and jump with ▲ ▼. The manifest viewer keeps "Matches only" (with line numbers) and can
  show the whole file in context instead.
- **🗂️ App profiles**: save the disabled / suspended / uninstalled apps under a name, preview what
  applying would change, apply (recorded in History, so it can be undone), share as
  `.adbprofile.json` and import on another phone.
- README rewritten with screenshots, code snippets and the full feature list.

## v4.5-Pro (versionCode 350)

Installs over v3.1 – v4.4 without uninstalling (same signing key).

- **Select and copy text**: long-press any text (app names, package names, versions, permission and
  activity names, the manifest viewer, terminal output, debloat descriptions and history) to
  highlight it and use the system Copy menu. Buttons, pills and toggles stay unselectable, so taps
  still work as before.
- Dragging to select text on an app row no longer selects or expands the row by accident.

## v4.4-Pro (versionCode 340)

Installs over v3.1 – v4.3 without uninstalling (same signing key).

### App list
- **Sort** by name, last updated, install date, size, or updates first. When sorting by date or
  size, each row shows that value. The choice is remembered.
- **🆕 Updated 7d** filter for apps updated in the last week (handy for spotting a bad update).
- **📤 Export** saves every app with its version, install and update dates, type, state, APK size and
  any known update as CSV to `Download/ADB App Manager/`.

### App menu
- **Sizes**: APK size (and how many parts a split app has); data and cache too once **usage access**
  is allowed. "Show data usage" grants it through ADB/Shizuku/Root, or opens the settings page.
  Sorting by size then uses total storage instead of APK size.
- **📦 Extract APK** saves the app to `Download/ADB App Manager/APKs/`: a single `.apk`, or for split
  apps a `.apks` bundle (base + splits) that split-APK installers such as SAI can install.

## v4.3-Pro (versionCode 330)

Installs over v3.1 – v4.2 without uninstalling (same signing key).

- **Install and update dates** in the app menu, under the version
  ("Installed Jan 15, 2024 • Updated Sep 20, 2026").
- **Versions in the app list**: each app shows its version on the badge row. The **🔢 Versions**
  pill hides or shows them, and the choice is remembered.
- **Update hints**: when the Updates tab has found a newer version, the app list shows
  "v26.0 → 27.1" and the app menu shows "→ 27.1 available on Galaxy Store" with an **Update**
  button (or Download / Release for updates that install elsewhere).

## v4.2-Pro (versionCode 320)

Installs over v3.1 – v4.1 without uninstalling (same signing key).

- The app menu shows the app's **version** right under its package name, as
  "Version <name> (<version code>)". Apps uninstalled for your user show their version too.

## v4.1-Pro (versionCode 310)

Installs over v3.1 – v4.0 without uninstalling (same signing key).

### Open-source updates for sideloaded apps
- The Updates tab now checks sideloaded apps (not from the Play Store or Galaxy Store) against, in
  order: **your sources**, your **imported Obtainium list**, the **Obtainium community catalog**
  (looked up online, honouring its APK filter / version rules), **IzzyOnDroid** and **F-Droid**.
  Apps installed through an F-Droid client are checked against F-Droid first.
- Releases are read from **GitHub** (API, falling back to the public release pages when the
  60-checks-an-hour limit is reached; an optional **GitHub token** raises it to 5,000) and
  **Codeberg**. The APK matching the phone's architecture is picked automatically.
- **📥 Import Obtainium List** reads an Obtainium export (Settings → Export) so every app you track
  in Obtainium is checked here too.
- Apps whose releases live somewhere only Obtainium can track get **Ⓞ Open in Obtainium**; sideloaded
  apps with no known source are listed under **Not tracked** with **＋ Set source**.
- Before installing, the download's **signing key is compared** with the installed app. A mismatch
  (for example an F-Droid build over a developer build) is stopped with a clear explanation instead
  of a failed install.
- Update All covers every installable source; releases without an APK for this phone link to their
  release page.

## v4.0-Pro (versionCode 300)

Installs over v3.1 – v3.9 without uninstalling (same signing key).

### ⬆️ Updates tab
- Checks the **Galaxy Store** for updates to Samsung system apps and apps installed from the Galaxy
  Store, using Samsung's public update service, and checks this app's **GitHub Releases**.
- **Update** per app, or **Update All** (one at a time). Each update is the official APK from the
  Galaxy Store, verified to be the same package and a newer version, then installed through ADB,
  Shizuku or Root. Failures show Android's reason with a Retry button.
- Updates for this app open in the browser so Android's installer handles them.
- The tab shows how many updates are pending. Play Store apps aren't covered: Google provides no way
  for other apps to check them, and the Play Store keeps updating them itself.

### 📜 Debloat history
- Every Debloater run, batch action and single-app freeze/uninstall/suspend (and update) is logged
  with time, packages and result. **Undo** reverses a run (reinstall what was uninstalled, enable
  what was disabled, and so on). **Copy Log** and **Clear History** included. Kept between launches
  (last 150 entries).

### Remembered filters
- The Applications filter and all Debloater filters (levels, vendor, state, brand) are restored
  when the app starts.

## v3.9-Pro (versionCode 290)

Installs over v3.1 – v3.8 without uninstalling (same signing key).

### Debloater: filter by brand
- New brand filter row in the Debloater. Your phone's own brand comes first (📱, detected from the
  phone's manufacturer), followed by the other brands found on the phone (Google, Meta, Microsoft,
  carriers, chip makers...), each with a package count that follows the other filters.
- UAD-NG has no brand field, so the maker is worked out from the package name (`com.samsung.*` /
  `com.sec.*` → Samsung, `com.facebook.*` → Meta, `com.oplus.*` → Oppo, and so on), with a
  fallback to the organisation part of the name. Each package also shows its brand as a badge,
  and search matches brand names.

## v3.8-Pro (versionCode 280)

Installs over v3.1 – v3.7 without uninstalling (same signing key).

### Debloater tab
- New **🧹 Debloater** tab powered by the
  [Universal Android Debloater Next Generation](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/wiki)
  (UAD-NG) community list: descriptions, removal levels and dependency notes for 5,000+ packages.
- The list (`uad_lists.json`, GPL-3.0) is **downloaded from UAD-NG's GitHub at runtime** and cached
  on the phone, not bundled. It refreshes automatically when older than a week, or with **Update List**.
- Shows only packages found on your phone, including ones already uninstalled for your user.
- Filters: removal level (Recommended by default; Advanced, Expert, Unsafe), vendor list (OEM, Google,
  AOSP, Carrier, Misc) and state (on device, enabled, disabled, uninstalled), plus search across
  package, name and description.
- Tap a package to read its full description and dependencies; packages that other installed
  packages need are flagged.
- **Uninstall** (for your user, reversible), **Disable**, **Restore** and **Save to List**. Every action
  is reviewed first, with a prominent warning when Expert or Unsafe packages are included.
- **Removal Levels** explains the four levels (from the UAD-NG wiki); **UAD-NG Wiki** opens the wiki.

## v3.7-Pro (versionCode 270)

Installs over v3.1 – v3.6 without uninstalling (same signing key).

### Suspend / Unsuspend
- The app menu has **Suspend** (for active apps) and **Unsuspend** (for suspended ones).
  A suspended app stays installed with its data, but can't be opened until it is unsuspended.
- Suspended apps are marked in the app list with a **⏸ SUSPENDED** badge and a greyed-out icon,
  and a new **⏸️ Suspended** filter shows only them. Suspension is detected in every mode,
  including Read-Only.
- The batch sheet has **Suspend** (asks for confirmation, like Freeze and Uninstall) and
  **Unsuspend**.

## v3.6-Pro (versionCode 260)

Installs over v3.1 – v3.5 without uninstalling (same signing key).

### Security: private ADB key per install
- Versions up to 3.5 shipped the same ADB key inside every APK (and in this public repo), so anyone
  with that key could connect to a phone that had approved it with ADB TCP open. **The bundled key is
  removed.** Each install now generates its own 2048-bit RSA key on the phone.
- On first launch after updating, the old shared key is replaced automatically. Your phone asks
  **"Allow debugging?"** once on the next ADB connection, and Wireless Debugging must be paired again.
  To fully remove the old key, tap **Revoke USB debugging authorizations** in Developer Options.
- Working Modes shows the key's fingerprint (it matches what the "Allow debugging?" prompt shows),
  with **Copy Fingerprint** and **Regenerate Key**.

### Appearance
- **Schedule**: a fourth Appearance option. Light and dark start times (default 07:00 / 19:00,
  ranges may cross midnight). The theme switches on time while the app is open.
- **Pure black in dark mode**: true #000000 backgrounds for AMOLED screens, with cards lifted just
  enough to stay visible.

### Builds on GitHub
- New **Build APK** workflow builds and signs the APK on every push and pull request and uploads it as
  a downloadable artifact. Add the `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD` secrets to sign with
  your release key (see README).

## v3.5-Pro (versionCode 250)

Installs over v3.1 – v3.4 without uninstalling (same signing key).

### Light and dark mode
- New **Appearance** switch at the top of Color & Themes: **Light**, **Dark** or **System**
  (follows the phone's dark mode, and re-themes instantly when it changes).
- Every palette has a light version: **Material 3** uses the official M3 light scheme,
  **Material You** reads both light and dark schemes from the wallpaper, and the six classic
  palettes get a generated light scheme with accents darkened to readable contrast on white.
- Status and navigation bar icons switch to dark on light themes.
- The whole UI is now theme-aware (sheets, inputs, buttons, toasts, avatars, terminal); text on
  accent-colored buttons picks black or white automatically.
- Color picker tweaks are saved separately for light and dark, with a **Reset tweaks** button.

### Defaults
- New installs start on **Material 3** with Appearance set to **System**. Existing users keep
  their current palette in Dark mode.

### Material You
- Follows wallpaper changes: the dynamic colors are re-read when the wallpaper changes and whenever
  you return to the app.

### Fixes
- ADB Console output keeps its line breaks.

## v3.4-Pro (versionCode 240)

Installs over v3.1 – v3.3 without uninstalling (same signing key).

- Color & Themes: **Material 3** is now the first palette and **Material You** the second, with the
  six classic palettes below them.

## v3.3-Pro (versionCode 230)

Installs over v3.1 / v3.2 without uninstalling (same signing key).

### Components
- The Components tab now lists **every** activity, including **unexported** and disabled ones, with
  badges (exported / unexported / disabled / protected), a count, search and filters
  (All / Exported / Unexported).
- Every activity has a **Launch** button:
  - Exported activities start with a normal intent (works in any mode).
  - Unexported activities start with `am start -W -n` through the active privileged mode
    (ADB TCP, Wireless Debugging, Shizuku or Root). Read-Only mode asks you to set one up first.
  - The full `am start` output is shown, so when Android refuses a launch (for example a
    "Permission Denial" for the shell user, or a disabled activity) you see the exact reason.
    Root can usually start activities the shell user cannot.
- Component names are validated and single-quoted in shell commands, so inner classes with `$`
  launch and stop correctly.

## v3.2-Pro (versionCode 220)

Installs over v3.1 without uninstalling (same signing key).

### App menu (single app)
- **Permissions** now has its own list: search, filters (All / Granted / Denied / Changeable /
  Install-time), protection badges (runtime, development, normal, signature, app op) and permission
  labels. Runtime and development permissions toggle between Granted/Denied; install-time
  permissions are shown locked because `pm grant/revoke` cannot change them.
- **App Ops** is a separate list: search, filters (All / Allowed / Ignored-Denied / Foreground) and
  one-tap modes per op (Allow, Foreground, Ignore, Deny, Reset). Uid-level modes are labeled. Any
  other op can be set by name (e.g. `RUN_ANY_IN_BACKGROUND`).
- New **Manifest** viewer: decodes the app's binary `AndroidManifest.xml` into readable,
  syntax-highlighted XML, with resource references resolved to names. **Find** shows matching lines
  with line numbers. **Copy** puts the XML on the clipboard; **Save to Downloads** writes
  `Download/ADB App Manager/<package>_AndroidManifest.xml`.
- The menu shows **Freeze** for enabled apps and **Enable** for frozen ones.

### App list
- Each app row now has only **App Settings**, **Force Stop** and the **Menu** button.
  Freeze/Enable moved to the menu. App Settings works in every mode, including Read-Only.

## v3.1-Pro (versionCode 210)

### Material Design 3
- New **Material 3** palette: the M3 baseline dark scheme (primary `#D0BCFF`, surface `#141218`,
  surface container `#211F26`, on-surface text `#E6E0E9`).
- New **Material You** palette: M3 dynamic color generated from the wallpaper on Android 12+
  (falls back to the M3 baseline on older versions).
- Palettes now also theme text, muted text, surfaces and the secondary color, and the status and
  navigation bars follow the theme background.
- The selected theme is now restored on launch (it was saved but never loaded before).

### Working modes
- **Fixed: modes could not be changed while ADB over TCP 5555 was enabled.** Every status refresh
  re-connected port 5555 and forced the saved mode back to ADB TCP. Status checks are now read-only
  and the selected mode only changes when you pick one.
- New **Automatic** mode card and a **Use This Mode** button on every mode.
- The header badge shows when a pinned mode is selected but not ready (orange), instead of silently
  falling back to another backend.
- Disconnecting ADB returns to Automatic, so Shizuku or Root can take over.
- `tcpip 5555` now targets the wireless debugging connection when one exists (avoids
  "more than one device").
- Root is no longer picked automatically (it caused su prompts); select it explicitly.

### Wireless Debugging
- **Fixed: IP addresses could not be typed.** Address fields were `type="number"`, which blocks `.`
  and `:` after the first few digits. They are now text fields that accept `IP:port`, `[IPv6]:port`
  or just a port.
- Added an IP field to Step 2 (Connect), plus **Use My Wi‑Fi IP** and **Auto-Detect Ports** (adb mDNS).
- Pairing accepts `IP:port` too.

### Shizuku
- **Fixed: Shizuku could not authorize the app.** The manifest was missing Shizuku's
  `ShizukuProvider` and the `moe.shizuku.client.V3_SUPPORT` marker, so Shizuku could not deliver its
  binder or show the permission prompt. Authorization now uses the official Shizuku API (13.1.5).
- Approving the prompt switches to Shizuku mode immediately, even with ADB TCP 5555 enabled.
- Commands run through the Shizuku remote process API (faster), with `rish` as fallback.
- New **Open Shizuku** button and clearer statuses (not installed / not running / update needed).

### Other fixes
- "Select All" in the batch bar called a function that did not exist.
- `adb devices` parsing now checks the exact target and its `device` state.
- Batch action results now include each command's output.
