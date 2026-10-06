# Changelog

## v7.9.18-Pro (versionCode 808)

- **Added storage (SD card, USB drive, another app's folder) does more.** Copy and Move now work there: pick them from a
  file's menu or select several items, then open the target folder and tap Paste - in, out of, or between added
  storages, with the usual "name already taken" choices and a Stop button. Multi-select is on in added storage too.
  Pictures, PDFs, fonts and archives open in their viewers, and Open with, Share and Extract work: the file is copied
  once to the app's cache first (up to 300 MB) and reused until the original changes. Search works there as well, by name,
  type, size and date (content: and archive: look inside files, so they are skipped in added storage).
- **Icon packs now dress apps they do not cover.** When the pack ships a background, mask or overlay for apps it has no
  icon for, such an app's own icon is shown on that background, scaled the way the pack asks, so the list looks uniform.
  Settings, Icon pack also has a "Clear icon cache" button.
- **Action Button setting.** A new "Action Button" card in Settings, right after Icon pack, chooses what the extra
  button on each Apps list row does: App Settings (as before), Force Stop, App Launcher, Enable / Disable, Install /
  Uninstall, Unsuspend / Suspend, Permission Manager, Activity Launcher or None (no button). It is always an icon, and the three
  Enable / Disable, Install / Uninstall and Unsuspend / Suspend choices change their icon and action to match the
  app's current state. Permission Manager and Activity Launcher open a sheet like the app menu, listing every
  permission (with its label, description, group, protection level, whether it is granted and whether you can change it)
  or every activity (exported or not, enabled, the permission it needs, with Launch and Enable / Disable), each with a
  search box and filters. The Permission Manager can also grant or revoke, in one go, every changeable permission shown
  by the current filter and search (it asks first and lists any that failed). The button also appears on the
  Debloater rows, and holding it opens a sheet with every action for that app, whichever one is chosen in Settings
  (Settings, Action Button, Hold menu chooses which actions that sheet lists and in what order). The Permission Manager
  has an App Ops chip too (every app op with Allow / FG / Ignore / Deny / Reset, and a permission shows its app op), and
  in the Activity Launcher you can tick several activities and Enable or Disable them at once.
- **More in the batch menu.** The batch menu is taller and has a fourth row: **Batch Ops** lists every app op, lets you
  tick the ones to change and pick a value for each (Allow, FG, Ignore, Deny or Reset), and sets them all on every
  selected app; **Show Apps** lists the selected apps and lets you remove any of them from the selection; **Command**
  runs a shell command of your own on every selected app, one after another, replacing `$package` with each app's real
  package name (the instructions, a live preview for the first app and a few examples are on the sheet; it runs
  exactly as typed and has no undo). Batch Ops has presets (Privacy lockdown, No location, Stop background activity,
  Silence notifications and an Undo for the lockdown) and lets you save your own sets of app ops by name. Command can
  save commands under a name, run on the apps of a saved list instead of the selection (the ones not on the phone are
  skipped and counted), and every batch result dialog has a Share CSV button (app, package, OK or failed, the
  command's output, the time and the command that was run). The little arrow at the top of the batch menu and of the app menu is now a larger,
  outlined button in the middle.
- **Press and hold guide.** A new card at the end of Settings lists everything that can be pressed and held (an app row,
  an app's icon, the Action Button, the selection checkmark, file rows, found APKs, Hidden Settings and overlay rows) and
  what each one does.
- **A tidier start and buttons.** The app now always opens on the Application Manager tab with "Total Installed"
  selected, even if a filter was left on last time. In the Apps list the checkbox sits to the right of the "⋯" menu,
  and its top-bar buttons share the File Manager's new outlined look (the File Manager's Up button is gone, the ".."
  row does that) with a haptic tap on every button.
- **Press and hold an app row to open its menu.** Holding anywhere on a row (the icon keeps saving itself, the buttons and
  checkbox are left alone) opens that app's single-app menu. App rows no longer start a text selection.
- **Fewer false "failed" results.** An app launch or app action is only reported as failed when the output says so at the
  start of a line ("Error: ...", "Error type 3", an exception, "No activities found"), so an app whose name or package
  contains "error" or "failed" no longer shows as a failure. The file manager's own commands now confirm success with a
  marker on a line of its own instead of searching the output for "OK".

## v7.9.17-Pro (versionCode 807)

- **Icon pack setting.** A new "Icon pack" card in Settings, right under Font, lists the icon packs installed on the
  phone (the ADW, Nova, Apex and Go theme convention). Pick one and the list's icons are drawn from it; an app the
  pack does not cover keeps its own icon, "Default icons" goes back, and each pack's icons are cached separately
  (refreshed when the pack or the app updates). Press and hold still saves the icon shown.

## v7.9.16-Pro (versionCode 806)

- **App icons in the list.** Every row now shows the app's own icon to the left of its name, package and badges, and the
  letter box is gone, which gives the text more room. Icons are drawn off the page's thread and cached on the phone
  (re-drawn only when an app updates), so later launches show them straight away. Press and hold an icon to save it as a
  PNG to Download/ADB App Manager/Icons.

## v7.9.15-Pro (versionCode 805)

- **Expressive Animations.** A new "Motion" card in Settings, right below the theme cards, holds one switch (on by
  default): Material 3 Expressive's springier, overshooting motion for the app's own transitions - modals, sheets,
  buttons, switches and the like - instead of a plain fade or slide. The few width-based progress fills (backup,
  storage scans, the self-update check) are left at a plain ease, where an overshoot would visibly pass 100% before
  settling back, and the whole thing steps aside automatically when the phone's own reduced-motion setting is on,
  whatever this switch says.

## v7.9.14-Pro (versionCode 804)

- **Terminal: sync the Termux shell with your real Termux.** Two new pieces under Terminal settings' Shell
  section (and in the Termux setup checklist): a "Match my Termux environment" switch, on by default, that
  sources ~/.bashrc when a Termux shell starts here too - so aliases, functions and PATH additions you keep
  there work the same way in this app, not just the PATH/profile the existing "Use my Termux login profile"
  switch already read; and a "Sync with Termux" button that turns that switch on if it was off, restarts any
  already-running Termux session so the change applies right away, and opens Termux to run
  termux-setup-storage - so granting it access to shared/phone storage is one tap instead of a command typed
  by hand.

## v7.9.13-Pro (versionCode 803)

- **Saved Applications: the "Saved" tag no longer crowds the title.** Each saved list's name and app count now have
  the card's full header to themselves; the "Saved" badge moved down to sit right above its own action-button row
  (Recall, Quick list, Edit, Copy, Delete), the same "identity on its own row, controls clustered together"
  layout the Apps list just got, adapted to how this card is actually built (no checkbox or icon to move).

## v7.9.12-Pro (versionCode 802)

- **Apps list: a cleaner row layout.** The app's name, package and badges now start flush against the left edge
  instead of sharing space with the checkbox and icon; the checkbox, the letter icon, the gear and ⋯ buttons all
  sit together on the right instead. No change to what anything does - Debloater/UAD-NG rows are unaffected.

## v7.9.11-Pro (versionCode 801)

- **Coding Agents: "Always allow" for file changes, not just commands.** A proposed `<run>` command already had
  an "Always allow in this chat" option next to Run and Skip; a proposed file write or edit only ever offered
  Apply and Skip, so every single change needed its own tap, however many steps a task took. The same "Always
  allow" button is now on that card too - once pressed, the rest of the chat's file changes go through with no
  further asking (reading outside the working folder and running commands are unaffected and still need their
  own say-so, unless those were separately allowed too).

## v7.9.10-Pro (versionCode 800)

- **Terminal: no more "Shell Command Result" popup after every command.** It hardcoded success regardless of
  what the command actually did, and duplicated output already printed right there in the terminal pane one
  line above it - per the command-result audit's own nuisance finding, the single most disruptive, lowest-value
  prompt in the app. Removed outright; a command run from another tab still gets a toast when it finishes.

## v7.9.9-Pro (versionCode 799)

- **Dex optimization no longer freezes the page.** Recompiling an app (or several) ran a `pm compile` call
  straight from the page's own thread, exactly the freeze batch actions had before v7.9.6 - now it runs off the
  page's thread with a live progress bar and a Stop button, for one app or many alike.
- **Fewer redundant "it worked" popups.** A single-app ⋯ menu action and a single app's Dex optimization used to
  show a toast *and* a full result dialog for a routine, successful action - now, like every other action in the
  app (toggling a component, a file-manager operation, launching an activity...), the dialog only shows up when
  something needs explaining; a failure still gets it.
- **Applying a Profile** now runs the same way batch actions do - off the page's thread with the progress bar
  above, instead of freezing the page for however long the whole plan took - and the Profiles sheet stays open
  behind the result dialog afterwards instead of closing first, matching every other flow in the app.
- Single-app menu: the Copy package / Copy version / Copy name row no longer wraps to a second line on a narrow
  phone; Share sits further from the ✕ so a slightly careless tap doesn't land on the wrong one.
- Terminal: the Ctrl+C key now just reads "CTRL" (still stops whatever is running).
- Credits: added a mention of [RohitKushvaha01/TaskManager](https://github.com/RohitKushvaha01/TaskManager),
  which the Task Manager tab is modeled on.

## v7.9.8-Pro (versionCode 798)

- **File Manager: Add storage.** Bring in an SD card, a USB drive, or a folder another app is willing to share,
  through Android's own document-tree picker - no working mode, root or All-files access needed, since the OS
  grants this app that one tree directly. Added roots show as quick-switch buttons above the file list and are
  remembered across launches; removing one only drops this app's access, nothing on the storage itself. Inside
  one: full browsing (with its own Up, since this kind of storage has no parent folder to look up - an added
  root's own breadcrumb trail is kept instead), new file/new folder, rename, delete, and viewing/editing a text
  file in place all work the same as internal storage. Copy, move, compress, Open with, Share and multi-select
  are not there yet for this kind of storage and are hidden or refused with a clear message rather than silently
  failing - they're next.

## v7.9.7-Pro (versionCode 797)

- **About tab: Device Specs**, under Handy to know - hardware (model, chipset, CPU cores and clock range, RAM,
  Vulkan/OpenGL ES, screen, storage), software (Android version, security patch, build ID), system (uptime,
  locale, timezone), battery, network, camera and sensors, all in one place. Refreshed automatically when the
  About tab opens, plus its own Refresh button. Every reading is a public, context-free one (BatteryManager,
  world-readable /proc and /sys nodes, CameraManager/SensorManager characteristics) - no working mode needed.

## v7.9.6-Pro (versionCode 796)

- **Batch actions no longer freeze the page while they run.** The live progress added in v7.9.5 still blocked the
  page for the whole loop - each app's action is a synchronous call into the native side, and that call was still
  being made from the page's own thread, which is also the WebView's. The batch now runs through a dedicated
  native batch runner instead, off the page's thread entirely, so the page can actually repaint and respond while
  it works; the Stop button and the live per-app progress behave exactly as before.
- **Apps list: version numbers are off by default.** A less cluttered list row - the "Versions" filter pill above
  the list still turns them back on, and the single-app menu and command results always show the version either
  way.
- **Logcat Viewer starts playing on its own.** Opening the tab used to need a tap on Play; it now scrolls to the
  top and starts live-tailing immediately.
- **Terminal: a larger input box**, and a Shift key on the extra-keys row that swaps in a second row of symbols
  (backtick, dollar, semicolon, colon, quotes, backslash, braces, parentheses, asterisk, ampersand, hash) a
  phone keyboard usually buries behind its own symbols layer.
- **Terminal: a Cheat Sheet button**, with a different set of commands depending on which of the three shells is
  picked - common bash for Termux, file/text basics for this app's own sandbox shell, and the existing ADB
  reference for the privileged working mode.
- **Single-app menu: Share moved to the top-right corner** of the sheet, next to ✕ - the Copy row underneath now
  fits on one line, and everything below it shifts up.

## v7.9.5-Pro (versionCode 795)

- **Live progress for batch actions.** Running a command (Freeze, Force Stop, Uninstall, ...) across several
  selected apps used to just show a spinner with no detail until it was all done. The batch panel now shows which
  app is currently being processed (name and package), a fill bar for how many are done, and a Stop button that
  ends the run after whichever app is already in flight finishes - nothing past that point is touched. Closing the
  sheet (✕ or tapping outside) while a run is active does the same thing, instead of silently hiding a job that
  would otherwise keep running unseen.
- **More command-result prompts now reflect what actually happened**, instead of assuming success or scanning
  output for a fixed set of failure words: Task Manager's "Kill" and GPU renderer switch, stopping a component's
  service, granting/revoking a permission, and changing an app op all now read the real result (exit code, or the
  native call's own outcome) the same way the rest of the app's actions already did. Optimize (Dex compile) also
  picks up a keyword gap ("failed") the old scan was missing.
- **Apps tab: the Sort/Export/Share CSV/Profiles/Backups row** no longer wraps "Backups" onto its own near-empty
  line on a phone-width screen - the row scrolls sideways like the filter pills above it instead, with everything
  below it shifted up to fill the gap.

## v7.9.4-Pro (versionCode 794)

- **Fixed the systemless-uninstall fallback actually reporting failure after it succeeded.** It passed a plain
  Java dynamic proxy as the callback Android calls back into with the real result; a proxy has no native Binder
  behind it, so that callback could never be delivered - the app removal went through, but this app's own process
  timed out waiting for a result it was never going to receive, and showed the uninstall as failed. The callback
  is now backed by a real `android.os.Binder`, which the system can actually call back into.

## v7.9.3-Pro (versionCode 793)

- **"Keep selection after running"** in the Apps tab's batch panel. Running a batch command (Freeze, Suspend,
  Uninstall, ...) used to always clear the selection once it finished, so running a second command on the same
  apps meant picking every one of them again. Ticking this new switch leaves the selection as it is - the panel
  sits behind the result dialog the same way the single-app sheet does - so another command can run on the same
  apps right away; it's remembered between launches, and the existing "✕ Clear" button and long-press on the
  floating checkmark still clear the selection by hand at any time.

## v7.9.2-Pro (versionCode 792)

- **Systemless uninstall for system apps.** When `pm uninstall --user 0` is refused with Android's own "only root
  can delete system app for a particular user" line, the app now automatically retries through a direct Binder
  call to `IPackageManager.deletePackageAsUser` - run as a standalone `app_process` under whichever privileged
  shell (ADB or Shizuku) is already active - the same technique App Manager and Canta use for the same refusal.
  This removes the app for the current user; it stays in the system partition, the same result a normal
  uninstall gives for a non-system app, and only runs when the active mode isn't already Root (which removes it
  directly, no fallback needed).
- **Removed the "Uninstall (System)" button.** It handed the removal to Android's own uninstall dialog, which on
  many phones did nothing useful for a preloaded system app - the automatic fallback above actually works instead,
  so the button (and the single-app sheet's own line it used to take up) is gone.
- **Several command results that said "Success" had actually failed.** Freeze/Enable, Suspend/Unsuspend, Force
  Stop, Clear Data, Uninstall and Remove Updates (single app, batch, Debloater, Undo and Profiles alike) used to
  decide success by scanning the command's own output for words like "error" or "failed" - an empty answer, or an
  OEM shell's own reworded refusal, read as success either way. They now read the shell's real exit status instead,
  which no amount of rewording or silence can hide.
- **Three more permissions on first launch, once a working mode is active**: Read/Write External Storage, Write
  Secure Settings and Access Restricted Settings (AppOps) - granted straight through the privileged shell, since
  none of them has an Android settings screen of its own. They join the existing All files access / Usage access /
  Display over other apps sheet, and (like those three) are also under About → Permissions.
- **The single-app sheet stays open behind its result dialog.** Running an action from the app menu (⋯) used to
  close the sheet outright; it now stays open underneath the result dialog, the same way the Installer and Signer
  sheets already behave, and its Freeze/Suspend/Uninstall buttons catch up once the result is dismissed.

## v7.9.1-Pro (versionCode 791)

- **App Stores: a successful install now offers Launch Application and Application Settings**, the same result sheet
  the APK Installer tab already shows - for every catalog (ShizuStore, GitHub, F-Droid, Orion, and a typed owner/repo).
  Before, a store install only ever showed a plain "Installed" toast with no way to open the app or its settings from
  there. The real, installed package name (read from the downloaded APK itself, not the catalog's own tracking key -
  a GitHub repository has no package name until its APK is read) is what the two buttons act on, so they are correct
  even when the catalog's listing didn't know the package name ahead of time.

## v7.9-Pro (versionCode 790)

- **The tab is now "Command-Line Interface"** (still Terminal first, ADB Console next to it, same big switch and the same
  two panes as v7.8 - only the tab's own name changed).
- **Three new API-key agents**: **Grok (SpaceX)**, **Muse (Meta)** and **Deepseek**, each with its own address, its own key
  page, and its own masked key hint. **Kilo Code** is listed too, in Free Open-Source - it is an editor extension with no
  confirmed phone-terminal CLI, so it is explained (website and source) rather than wired up to chat here, the same way
  DroidMind and Leon.ai already are. OpenCode was already here and stays as it was.
- **Effort**, next to Model: **Low, Balanced or High**, for how hard an agent is asked to think. Balanced is the first
  choice, the same way the Model list's own first (recommended) entry already balances speed and cost. It actually changes
  the request for the agents whose own API has that knob - Claude's extended-thinking budget, ChatGPT and Grok's
  reasoning_effort, Gemini's thinking budget - and is simply kept, with no effect, for the others (Deepseek, Muse, the
  official sign-in tools in Termux). Settings gets a matching Effort column next to each agent's default model.
- **The AI agents can help in the ADB Console too**, not just the Terminal: a new **$ / AI** button next to its input
  switches it to ask the Coding Agent picked in the Terminal (the same agent, model and effort - no separate picker here)
  for ADB/shell syntax and code help. The agent answers inline in the console's own output, and may propose **one command**
  at a time, shown with **Run** / **Skip** (or run straight away when "Ask before running commands" is off) through the
  console's own execution path - never a parallel shell of its own. Its own small memory is separate from the Terminal's.
- **Translations**: the new agents, the Effort words and the console's AI help are still English-only in the other 12
  languages for now (to translate later); everything already translated is unaffected by the tab rename.

## v7.8-Pro (versionCode 780)

- **The ADB Console tab is now "Terminal / ADB Console"**, with a big switch at the top between the two. **Terminal** comes
  first and the tab remembers which one you used last; **ADB Console** is the console you know (Rish mode, cheat sheet,
  saved commands), unchanged.
- **Terminal: a Termux-style command line.** A black screen with colours, Termux's row of extra keys (ESC, TAB, CTRL-C,
  arrows, HOME, END, | / - ~), command history, TAB completion of file names, progress bars that redraw in place, and
  **three shells**, each with its own screen that stays open while you use other tabs (cd and export carry over):
  - **This app (sandbox)**: always there, no setup.
  - **Working mode**: the shell user through ADB or Shizuku, or root with Root.
  - **Termux**: your own Termux, with bash and everything installed with pkg. A **Termux setup** checklist walks you through
    the one-time steps (install from F-Droid or GitHub, `allow-external-apps=true`, the "Run commands in Termux environment"
    permission, a connection test). Full-screen programs (nano, vim, top, a Python prompt) open in a real Termux window.
    **Once Termux is set up, the Terminal opens on it by default** (bash, more compatible with the coding agents' own CLI
    tools than the sandbox or Working mode); "Automatic" in Settings explains the rule.
- **Coding Agents.** A drop-down with **None** first, then **API Key Required** (Gemini, Claude, ChatGPT, Cursor, Copilot,
  Perplexity) and **Free Open-Source** (DroidMind, OpenCode, Leon.ai, Jan.ai, AnythingLLM, and Ollama on the phone itself),
  plus a **Model**
  drop-down. Choosing an agent asks for what it needs and **tests it first**: a refused key is explained in plain words with
  the provider's own message. Chat in the Terminal: the agent answers as it types, and it can **run commands, read files,
  write files and edit parts of files** in the current shell, **each step shown first** with Run / Apply, Skip, or Always
  allow for the rest of the chat. Edits show a line-by-line diff, `/undo` takes a change back, STOP (or CTRL-C) ends it all.
  **Switch agents or models in the middle**: the conversation carries over. Slash commands: /help, /new, /agent, /model,
  /models, /shell, /chat, /undo, /stop, /login, /settings; `!command` runs a command from the chat.
- **API keys stay safe**: sealed with a key that never leaves the phone's secure hardware, sent only to their own provider's
  address (the app adds them; the page never sees them again), and never shown to an agent (keys in output are masked).
- **Subscriptions (website sign-in)** go through the providers' **official command-line tools** in Termux, because Anthropic
  and Google do not allow other apps to use a Claude or Google login: Claude Code, Gemini CLI, Codex (ChatGPT), Copilot CLI
  and Cursor CLI. The Connect sheet installs the tool (into a Debian container in Termux when it has no Android build),
  opens its own sign-in in Termux, and the Terminal then chats through it.
- **Cursor** connects to Cursor's Cloud Agents with an API key (they work in Cursor's cloud, optionally on a Git repository
  you name); **Copilot** runs through GitHub's Copilot CLI, since GitHub retired GitHub Models in July 2026.
- **Perplexity** connects with an API key from its own website (its Sonar models; no official sign-in tool, so it is an
  API key only, with no "Sign in with subscription" tab).
- **Free options**: **Jan.ai** and **Ollama** are found on the phone by themselves when they run there (or enter a computer's
  address); **AnythingLLM** asks for its server's key; **Ollama** can be installed into Termux with a small coding model in
  one go; **OpenCode** is installed in Termux. **DroidMind** and **Leon.ai** are explained (tools for other agents and a
  computer assistant) with their install or website.
- **Terminal settings** (the gear in the Terminal): your keys by their hint only, the agent and model to start with, the
  default shell, whether agents must ask before running commands or changing files, and whether the conversation is kept.
  **Help** explains everything, with a link to every agent's website.
- **MCP button**: a new sheet (next to Help) to add a Model Context Protocol connector - a preset (filesystem, fetch,
  memory, GitHub, and more, each with its own note) or a custom one (name, stdio command or remote URL, extra environment
  or header) - to any of the six CLI-based agents (Claude Code, Gemini CLI, Codex, Copilot CLI, Cursor CLI, OpenCode),
  **two or more agents at once**, so the same server reaches all of them. Written into each agent's own config file in its
  own shape (`.claude.json`, `.gemini/settings.json`, `~/.codex/config.toml`, and so on); a remote server's token is kept
  as an environment variable for the agents that want it that way, never written into the file itself. Already-added
  servers are listed with which agents they went to, and can be forgotten (removed from all of them at once).

- **Safety**: "Always allow in this chat" applies only to the shell and user it was given in (a grant in the sandbox does not let
  commands run as root); a read through a link that leads out of the working folder (Termux's `~/storage`) is asked about;
  files are read for edits between nonce markers with a size check; edit markers are whole lines; a file that is not UTF-8
  text is not edited; edited files keep their permissions; an answer cut off mid-stream is reported as an error; a key with a
  control character is refused and keys are masked in any error that goes back to the page.
- **Translations**: the agent and model drop-downs and the Shell options are translated (agent and model names stay as they
  are); optgroup headings are translated app-wide (the Compress dialog's "One file only" too); the File Manager search help
  showed `size:&gt;10mb` in every language and now shows `size:>10mb`. About 70 of the Terminal's own status lines are still
  in English in the other languages.

## v7.7-Pro (versionCode 770)

- **Task Manager → GPU: a Renderer switch.** A dropdown showing the HWUI backend currently in use (Vulkan, OpenGL, or
  "Default" when nothing has overridden it) - only offering Vulkan when the device actually supports it. Picking a
  different one runs `setprop debug.hwui.renderer skiavk`/`skiagl` and then restarts System UI (`am crash
  com.android.systemui`, which Android relaunches immediately) so the new renderer actually takes effect - a quick
  flicker of the status and navigation bars is expected and not a bug. Needs a working mode; reads and writes the
  property only on request (opening the tab, switching to it, or picking a value), never on the auto-refresh tick, so
  it can't rebuild itself out from under an open selection.

## v7.6-Pro (versionCode 760)

- **Uninstall (System).** A second button next to Uninstall on the app sheet, for a system app that needs root the direct
  way: it hands the removal to Android's own uninstall screen instead of a shell command. That runs with the system's
  own privilege rather than shell's, so it can go through Android's standard no-root soft-removal for a preloaded app
  (shows as **Not installed** in Settings afterward) in cases where the direct Uninstall button's `pm uninstall --user 0`
  hits the hard "only root" wall. No working mode needed to open it; the system's own confirmation dialog decides the
  rest. The failed-uninstall note now points at this button by name.
- **Fixed:** an app that's uninstalled-for-this-user (shows under the Uninstalled filter) was listed by its bare package
  name instead of its real name. It now reads the name off the system-app stub that's still on the device, the same way
  Android itself would, falling back to the package name only when nothing more is left to read.
- **Task Manager: a Information section on every tab**, reusing data the phone already exposes, no new permission on any
  of it:
  - **CPU** — Processor Information (SoC, architecture, ABI, scaling governor, an estimated temperature) and System
    Statistics (process and thread counts, uptime), plus a card per CPU cluster with its core count and min/current/max
    clock speed. Thread counts and process counts need a working mode, same as the Processes list; everything else reads
    straight from `/sys` and needs nothing.
  - **RAM** — Free, Buffers, Cached, Swap free, Swap cached and the kernel's page size, alongside the existing Used /
    Available / Swap figures.
  - **GPU** — Vulkan support and API version, and the OpenGL ES version, from plain `PackageManager` /
    `ActivityManager` queries (no GL context is created, so this never touches the GPU driver).
  - **Battery** — Technology, current power source, charge in mAh, an estimated capacity and an estimated time left
    while discharging, charge cycle count (Android 14+, else "N/A"), and the actual wattage (voltage × current,
    correctly converted from the raw µA/mV the phone reports).
  - **Network** — total bytes received and sent since boot, alongside the existing live ↓/↑ rates.

## v7.5.1-Pro (versionCode 751)

- **A plain-language note on a failed Uninstall / Uninstall (keep data).** Removing a system app for one user has always
  needed actual **root** — plain ADB or Shizuku run without root were never enough, on any Android version or phone
  brand, regardless of what the raw `pm` output says. When that is why it failed, the result now says so plainly and
  points at **Freeze** as a no-root alternative (the app disappears from the launcher and stops running; its file stays
  on the device either way, since `/system` is read-only regardless of which one is used). A device policy or a user
  restriction blocking removal is also explained instead of shown as a raw `DELETE_FAILED_...` line.
- **Fixed:** a failed Freeze / Suspend / Uninstall / Clear Data / Force Stop could show its result as "Success" when the
  raw `pm` answer was `Failure [...]` — the check only looked for the word "failed", not "failure".

## v7.5-Pro (versionCode 750)

A new **Task Manager** tab, after Logcat Viewer: processes, CPU, RAM, GPU, battery and network, live, with a graph for each.

- **Processes.** A sortable list (by CPU or memory) of what is running, each row with its RSS and CPU%; a resolved app shows its real name.
  **✕** force-stops it right away, with no confirmation — the same one-tap `force_stop` already used everywhere else in the app. Needs a
  working mode (ADB / Wireless Debugging / Shizuku / Root): listing processes is a shell `ps`.
- **CPU, RAM and Network work with no working mode at all.** They read `/proc/stat`, `/proc/meminfo` and `/proc/net/dev` directly — those
  are world-readable — so the graphs, the per-core breakdown, and the used/available/swap and ↓/↑ figures all show up even on a phone with
  no ADB, Shizuku or Root connected.
- **Battery needs nothing either.** Percent, temperature, voltage, current, charge state and health come straight from `BatteryManager` and
  the system's own battery broadcast — no shell, no special permission. Temperature and current have a unit switch (°F/°C, mA/µA).
- **GPU is best-effort.** Busy-time and utilization sysfs paths are tried first (Adreno, Mali-style); failing those, a clock-speed ratio is
  shown as an approximation, labelled as such. Needs a working mode; on a phone that exposes none of these paths, it says so plainly instead
  of guessing.
- **Two independent settings.** **Auto-refresh** (off, 1–10 s) is how often a fresh reading is fetched — the expensive part, a shell round
  trip when privileged. **Graph speed** (150–1000 ms) is only how smoothly the already-fetched readings are redrawn from a small in-memory
  buffer — cheap, no extra reading. **Refresh now** asks for one reading right away. All of this, plus the chosen units and process sort,
  is remembered between visits to the tab.
- **For developers:** five small, pure-Java engines with no `android.*` imports (`CpuStats`, `MemStats`, `NetStats`, `ProcStats`, `GpuStats`),
  each with its own suite (`cpustats`, `memstats`, `netstats`, `procstats`, `gpustats`) run on a desktop JVM. The page side has
  `tests/ui/t87.js`.
- **Not in this release:** the tab only reads while it is open — there is no background monitoring, no alerts (high CPU, low battery, …),
  and the graphs start over empty each time the tab is reopened (nothing is kept across visits or an app restart).

## v7.4-Pro (versionCode 740)

Archive formats: 7z, rar and the tar family join zip, with passwords, and a Compress dialog to make new archives. The file manager's
editor, viewer and archive preview now show code in colour.

- **7z, rar and the tar family open, browse and extract like a zip.** The archive browser, **Extract…**, search's `archive:` and Find
  APKs' duplicate check now work on `.7z`, `.rar`, `.tar`, `.tar.gz`, `.tar.bz2`, `.tar.xz`, `.tar.zst`, `.tar.lz4`, and on a bare `.gz`,
  `.bz2`, `.xz`, `.zst` or `.lz4` (shown as the one file inside). A solid 7z or rar, and a compressed tar, say so in the header; extracting
  one unpacks it in a single pass instead of reopening the file per entry. RAR is read only (browse, preview, extract); 7z and the tar
  family can also be edited in place (delete, rename, replace, add, new folder) the same way a zip can.
- **Passwords.** Opening a 7z or rar whose names themselves are encrypted, or reading an entry that is encrypted while its name is not
  (a zip, or a 7z with content-only encryption), asks for a password in a dialog of its own; a wrong one says so and asks again. The
  password is kept only for that open archive, only in memory, and is asked again next time. Zip supports the traditional ZipCrypto and
  WinZip AES (128/192/256); 7z supports its own AES-256 (and encrypts the file names too, not only the data); rar's passwords are for
  reading only, since rar cannot be created here.
- **A Compress dialog.** **Compress…** on a file's sheet, or **Compress** on a selection, makes a new archive: zip, 7z, a tar format, or
  (one file only) a bare gz/bz2/xz/zst/lz4. A name, a packing level where the format has one, where to save, and what to do if that name
  is already there (keep both or replace). **A password is optional** — leave it empty for none. Zip offers AES-256 (default), AES-128 or
  the weaker-but-everywhere ZipCrypto; 7z is always AES-256 (and hides the file names); tar and the single-file formats have no password
  of their own.
- **Code and markup shown in colour.** The file manager's text editor, the plain file viewer, and an archive's text/XML preview now colour
  comments, strings, numbers, keywords and tags by default, for the usual languages (C-family, Java/Kotlin, JavaScript/TypeScript, Python,
  shell, PHP, Ruby, Perl, Go, Rust, Swift, Dart, Lua, R, SQL, batch, Gradle, smali) and markup (XML/HTML with nested `<script>`/`<style>`,
  JSON, YAML, TOML, INI, Markdown, diff); a file it does not recognise, or one over 250,000 characters, is shown plain. **Word wrap is on
  by default** in the editor; a **Colors** switch sits next to **Wrap lines**, and both choices are remembered for next time.
- **For developers:** `ArchiveIo` (7z and the tar family, plain Java, no Android classes) and `RarReader` (rar, read only) are the new
  engines, each with its own suite (`archiveio`, `rarreader`, checked against 7-Zip, py7zr, tar/gzip/bzip2/xz/zstd/lz4, and real RAR4/RAR5
  archives from the `rarfile` and `libarchive` projects' own test suites); `ZipCrypt` (zip's own passwords) and `ZipWriter` (new zips) have
  theirs too (`zipcrypt`, checked against Info-ZIP and pyzipper; `zipwriter`). The page side has `tests/ui/t85.js` (syntax colours and word
  wrap) and `t86.js` (the password and Compress dialogs).
- **Not in this release:** rar cannot be created or edited (no free, well-tested way to write it); multi-volume rar is not supported, nor
  is strong (PKWARE) zip encryption; the Hidden Settings backup/restore and the Task Manager tab are still ahead.
- **Known limits.** A `.gz`'s exact size is known only below 4 GB (bzip2 never gives one; zstd/lz4 only when the file itself carries it).
  Zstd decoding uses a third-party pure-Java decoder; if it cannot run on a given device (an unusual JVM that refuses `sun.misc.Unsafe`),
  `.zst`/`.tar.zst` are reported as unsupported there rather than failing partway through — nothing else is affected. 7z's own key
  stretching, and reading a very large archive's list, can take a noticeable moment. A password is asked for only once reading reaches an
  entry that needs it (not up front for the whole archive), so on a mixed archive a retry after the password re-starts the job; files
  already written by then are handled by the conflict choice (Replace, Skip or Keep both) like any other extraction.

## v7.3-Pro (versionCode 730)

File manager, part two: search, the extract dialog and long jobs that keep going; and a cleanup for the package files the Installer finds.

- **Search in the file manager.** A search bar under the path: type a name, or use the filters. **Where to search** is a drop-down: this folder,
  Internal storage, Downloads, Camera and photos, Pictures, Documents, Music, Movies, an SD card or USB drive, or the whole phone. Two boxes
  next to it: **Include subfolders** (off: only what is directly in the place chosen) and **Search inside archives** (zip, apk, jar, docx and
  the like). The answer is a list that takes the place of the folder until **Back to the folder**: the name, the folder it is in, size and
  date, the line that matched for `content:`, and for an archive entry the archive and the entry. Tap a result to open it (a file gets its
  usual sheet, a folder opens, an archive entry opens the archive and filters it to that name); the arrow button shows it in its folder.
  While it runs a line shows the folder, how many files were looked at and how many were found, and the button says **Stop**. Search help
  folds out under the bar. **Show hidden files** applies to the search too.
  - words in a name (all must match; `*` and `?` are wildcards; `-word` leaves a name out; `"two words"` in quotes);
  - `ext:jpg,png` or `.pdf`; `type:image|video|audio|text|doc|archive|apk|font|folder|file`;
  - `size:>10mb`, `size:<1k`, `size:1k..5m` (b, k, m, g, t); `date:today|yesterday|week|month|year|7d|2w|3m`, `date:2025-03-01`,
    `date:2025-03-01..2025-03-31`, `date:<2024-01-01`, `date:>=2025-06-01` (the phone's own time zone);
  - `content:word` looks inside text files up to 4 MB (every word given must be in the file; the first matching line is shown);
  - `archive:name` looks at the names of the entries inside archives (the whole path in the archive, wildcards allowed).
  What could not be understood is said, and the rest of the query still works. The walk stops at 1,000 results, 400,000 files or 90 seconds,
  never follows a link to a folder, and skips pipes and devices; the result line says when a limit stopped it. It reads what the app can read
  (All-files access); folders only a working mode reaches are not searched.
- **An extract dialog.** **Extract…** on a zip-format file in the file manager (and **Extract** inside the archive browser, for the whole
  archive, a folder or a file) opens a dialog: **Extract into** this folder, a new folder named after the archive (`data.tar.gz` gives
  `data`), or another folder you type; **If a file is already there**: Replace, Skip or Keep both (`name (1).ext`; the old extraction
  simply overwrote); and, for a whole archive on storage, **Delete the archive afterwards** (asked again before it starts; the archive
  goes only when every file was written, with nothing skipped or left alone). The choices are remembered. The result says how many files,
  how many were left as they were, and that the archive was deleted.
- **Long jobs keep going and say how they are doing.** A copy, move, delete or extraction shows **percent, speed and time left** (the speed
  is smoothed, so the time does not jump about), on the page and in a notification of its own; the job runs in a foreground service, so it
  continues when you leave the app, and the notification has **Cancel**. If nothing has moved for 20 seconds (a slow cloud file, a drive
  that stopped answering) the line and the notification say so and that Cancel stops it. Cancel also works from the page: copy, move,
  delete and extraction have a Cancel button.
- **Find APKs: duplicates and older versions.** After a search the app reads the package name and version of each `.apk` and compares the
  contents of files of the same size: a second copy is flagged **Duplicate** (and says which file it is the same as; the newest is kept,
  then the one with the shorter path), and a version below another one of the same package is flagged **Older version** (and says which
  file is newer). The package and version are shown on each row.
- **Delete several at once, with one Undo.** **Select** in the Find APKs list shows boxes; **All**, or **Select duplicates and older
  versions** to pick exactly the flagged files; **Delete selected (N)** asks once (count and size), moves every file to the hidden trash
  folder and gives **one Undo bar for the whole batch** (the files go for good when the bar does).
- **After an install**, the result sheet offers **Delete the installer file** (a file on the phone's storage; not one picked through
  Android's chooser, not after a failed install, not when "Auto-delete package after install" already did it). It goes to the trash with an
  Undo. A new switch in the install options, **Offer to delete the file afterwards**, turns the offer off.
- **A workflow that runs the tests on every pull request** (`.github/workflows/tests.yml`): the UI scripts in headless Chromium and the
  Java suites (with a real Android shell and the framework classes) as two jobs; the output of a failed UI run is kept as an artifact.
- **For developers:** `FileSearch`, `ProgressMeter`, `JobTicker` and `ApkFlags` are plain Java with their own suites (`filesearch`,
  `progressmeter`, `jobticker`, `apkflags`), `fileops` also covers extraction with the three rules; the page side has `tests/ui/t83.js`
  (search and extract dialog) and `t84.js` (Find APKs cleanup and the offer after an install).
- **Not in this release:** archive formats other than zip-family (7z, rar, tar and its family, with passwords) are the next release.
  Search reads only folders the app itself can read.
- **Behaviour worth knowing.** In Find APKs the selection follows the filter (files hidden by a filter are un-picked and the count and the
  button follow what is shown), flags are recomputed after deletes and Undo, and an older version is compared only against the same app
  with the same signer and ABIs. Several long jobs at once share one notification.
- **Known limits.** A single-file extraction cannot be cancelled mid-file; **Delete the archive afterwards** deletes permanently (not to the
  trash) and ignores empty-folder entries; replacing a folder through a working mode (shell) can differ slightly from the direct route;
  a few place names and native problem messages are still English.

## v7.2-Pro (versionCode 720)

File manager, the first part of its overhaul.

- **A ".." row at the top of every list.** A folder drawn in CSS and "..": tap it to go up one folder. It is there in an empty folder
  and when a folder cannot be listed, so there is always a way out; the **Up** button stays. At the root of the phone there is
  nothing above, so no row. Every row now has a picture in front of it: a folder, a page, or the small picture of an image or video.
- **＋ File** next to **＋ Folder**: asks for a name (no slash, not "." or "..", not one that is taken, hidden files included),
  creates an empty file and opens the editor on it when it is a text file.
- **Show hidden files**: a box under the title (off at the start, kept between launches). Names that begin with a dot are left out
  until it is ticked, and the list says how many it is not showing. They are dimmed when shown.
- **Thumbnails, cached.** Images and videos show a small picture in place of the page. The app makes each one once (a 96 px JPEG),
  keeps it in its cache keyed by path, size and modified time, and trims the cache to 48 MB by removing the least recently used.
  **Show thumbnails** switches it off; **Clear thumbnail cache** empties it and says how much was freed.
- **When a name is already taken** (Paste here), a sheet offers **Replace** (a folder is merged with the one there), **Skip** (what is
  there stays; a folder still receives the files that are new) or **Keep both** (the new one becomes `name (1).ext`, `archive (1).tar.gz`,
  `folder (1)`). A copy pasted into its own folder offers it too, so it makes a duplicate. A move onto the same folder does nothing.
  The toast counts what was copied, skipped and failed.
- **Cancel** on a running copy, move or delete: it stops after the item it is on. A copy is written to a temporary name and renamed
  when whole, so a stopped or failed copy never leaves half a file and never costs the file it was going to replace.
- **No working mode needed for files the app can reach.** With All-files access, copy, move, delete, rename, ＋ Folder and ＋ File
  run in the app itself; ADB / Shizuku / Root are used only for what the app cannot reach (and for naming rules there, one command
  per item). The batch operations and the single ones both do this.
- **Open with…** and **Share** on any file: a copy (up to 400 MB) is handed to the app you choose through the app's share folder.
  **View** on a video or a sound does the same.
- **A text editor.** **Edit** on a text file (or one with an ending the app does not know; a file that is not text, or over 2 MB, is
  refused with the reason): the whole text, Wrap lines, a line and column, Save, and a question before closing with unsaved
  changes. A file changed by something else since it was opened is not overwritten unasked. Saving writes a temporary file and
  renames it, so a failed save keeps the old text; a file the app cannot write opens read only.
- **Viewers.** **View** on a picture shows it (up to 1600 px, Fit / actual size), on a **PDF** shows its pages one at a time with
  Previous and Next (Android's own renderer), on a **.ttf / .otf** shows a sample set in that font.
- **For developers:** `FileOps` (copy, move and delete with the three rules, links, cancel, atomic writes, and the same rules as an
  `sh` script for the shell route) and `ThumbCache` are plain Java with their own suite (`node java/run.js fileops`, 64 checks); the
  page side has `tests/ui/t82.js` with a small virtual file system (`tests/ui/lib/fm_mock.js`).
- **Not in this release** (next ones): archives other than zip (7z, rar, tar and its family, with passwords), search by name, date,
  size and type with `content:` and `archive:`, the extract dialog with a destination chooser, and the foreground service with progress,
  speed and time left. Video and sound are not played inside the app; **Open with…** hands them to a player.

## v7.1-Pro (versionCode 710)

- **A language setting.** **Settings** (the gear at the top) now starts with **Language**, a drop-down with English (the default) and
  thirteen more, each named in its own letters: Español, Français, Deutsch, Português (Brasil), Italiano, Русский, 简体中文, 日本語,
  한국어, العربية, हिन्दी, Türkçe and Bahasa Indonesia. The language changes at once, with no restart: tabs, buttons, filters,
  sheets, messages, the text in sentences with bold words or links, tool tips, input hints, and the dialogs of the browser. It is
  kept between launches and the app starts in it. Names of apps, packages, files, folders, settings and colors, paths, and the
  output of commands, logs and the terminal are left as they are, so an app called "Settings" keeps its name. Dates and numbers
  follow the language. Arabic runs from right to left. A language whose file cannot be loaded changes nothing and says so. The
  words of the notifications, the quick tiles and the home-screen widget are still English.
- **A font for the app.** **Settings > Font** (after the colors): **Use system font** or a font of your own, only for the text of this
  app (the rest of the phone is not touched). **Find fonts on this phone** searches storage for .ttf and .otf files with a progress
  bar and lists them with their real names (read from the font file, also for a variable font); **Choose a file…** opens Android's
  file chooser instead and needs no access to storage. Tap a font to see it on a sheet (a sample set in it) and then **Use this
  font**. A refused file says why (not a font, a collection, over 12 MB). The font is kept by the app and loaded again at each
  start; if it cannot be loaded the system font stays and the choice is dropped. Names, paths and commands in code-style boxes keep
  their own monospace font. Variable fonts need a WebView of Chrome 62 or newer. Known limits: a very large list can take a
  moment to translate, and the saved font is read at start-up.
- **Saved App Lists is now Saved Applications** (the tab, its first card and the quick-list tile text).
- **A new app icon:** the lightning bolt of the header on the same cyan-to-violet tile, as an adaptive icon.
- **For people who translate:** `tests/i18n/` holds the list of every string of the page (`keys.json`), the translator's brief,
  and the tools that cut a language into files, check them (changing parts, tags, line breaks, script) and put them together;
  `node i18n/check.js` checks all dictionaries in `assets/lang/`. A second tool (`audit.js`) finds names or paths that a word of
  a dictionary would have translated.
- **Tests.** Two new UI scripts: `t80` (the font: search, progress, list, sheet, use, system font, next launch, a font that cannot be
  loaded, the chooser, no access, an empty list, a variable font) and `t81` (the language: the drop-down, switching at once and back,
  kept between launches, later texts, dialogs, names left alone, right to left, a dictionary that is missing); a Java suite for the font
  search (`fontscan`: which files are fonts, what a font calls itself in every encoding, the bounded walk, the checked copy). 81 UI
  scripts and 18 Java suites in all.

## v7.0-Pro (versionCode 700)

- **New tab names, order and two-line labels.** The tab bar now reads Application Manager, Saved App Lists, UAD-NG Debloater, APK
  Installer, File Manager, ADB Console, Hidden Settings, RRO/Monet Customization, App Updater, App Stores, Logcat Viewer and About.
  App Updater moved to just before App Stores and Logcat Viewer to just before About. Names that are long break into two lines
  (Application / Manager) so more of the bar shows at once; ADB Console and About stay on one. The title of each tab's first card
  follows the new name.
- **A settings gear instead of the colors button.** The button at the top right now opens **Settings**: Appearance, the theme
  palettes and color pickers (what the colors button used to open) and, after them, the new Feature List. The gear lights up while
  Settings is open.
- **Feature List.** In Settings, every tab except Application Manager and About has a switch and two arrows. A switch off takes
  the tab out of the tab bar (a link inside the app that points at it says that it is off and where to turn it on, and does
  nothing else: no sheet closes, no download starts; only an APK opened from outside brings APK Installer back). The arrows
  move it up or down, and the bar follows at once; the row stays under your thumb and the keyboard focus stays on the control
  you used. **Reset to Default** switches them all on and puts them back in the first order. Application Manager always stays
  first and About last. The choice is kept between launches; tabs a later version adds are placed after the tab that precedes
  them by default, so a saved choice keeps working.
- **No emoji.** Tabs, filters, buttons, sheets, toasts, the home-screen widget and this changelog no longer carry any. The only
  ones left are the two settings gears: the one in the header (the app's settings) and the one on every app in the list (that
  app's settings). The text in the tabs and in the filters is a little larger to make up for it. Buttons that were only a
  picture now say what they do (Copy, Share, History, Saved, Run, Pin, Edit, Delete, Refresh, Install); the copy chips in an
  app's menu read Copy package / Copy version / Copy name; folders in the file manager and in the archive browser end in a
  slash; a store card says "10k stars" and "500k downloads". The terminal's History and Saved buttons moved under the command
  box, so the box keeps its width.
- **The Application Manager search bar sits under all the filters,** directly above the list, with a **menu (three dots) at its
  right end**. Its three options, all on at the start: **Include application names**, **Include package names** and **Use regex
  matching** (a line under it says that, with it off, applications and packages are matched exactly: the whole name has to be
  typed). The choices are kept between searches and launches. One of names and packages is always on: turning off the one that is
  left turns the other on. A pattern that is not valid (for example an open bracket), or that could freeze the list (a repeat inside
  a repeat, like (a+)+), is searched as plain text and a note says so. The box says what it searches, and a change redraws the
  list at once. The menu opens in view and the batch button steps aside while it is open; it closes when you tap elsewhere, type,
  go into the box or leave the tab (a scroll leaves it open). The bars that stick under the header follow its real height, which
  wraps on a narrow screen.
- **The Aurora sub-tab of App Stores is gone.** The Play Store card on the Updates tab still opens Aurora Store.
- **Error lines say that they are errors.** A failed store load, install, search for packages, package read or VirusTotal scan now
  starts with words ("Could not load: ...", "Install failed: ...", "The search failed: ...") and is in the failed color; before,
  the warning sign was the only thing that said so. The buttons the pictures used to tell apart are told apart again: Uninstall
  and Delete are in the failed color, the main action (Install, Restore, Reinstall) is tinted, a pinned command in the terminal
  has the accent color, a permission that cannot be changed (install-time) has a "locked" badge, and a component that is launched through the
  shell says LAUNCH (SHELL). The terminal's find box keeps room for its hint at 320 px, the sub-tabs of Hidden Settings and App
  Stores and the log level chips are a little larger, the filled accent buttons are readable in the dark theme, and sentences
  that named Files or Applications now name File Manager and Application Manager.
- **Tests.** Four new UI scripts: `t76` (the tab registry, the Feature List, saved choices from older versions, links into a tab
  that is off), `t77` (the search bar and its menu, the freeze guard), `t78` (no emoji in the sources or in anything the page draws,
  also when written as an escape) and `t79` (the findings of the review of the emoji removal); the others are updated for the new
  names. 79 scripts in all.

## v6.1-Pro (versionCode 610)

- **A bigger theme button.** The button at the top of the screen (it opens Colors & Themes) is now 44 × 36 px with a bigger
  icon, where it was about 40 × 25 px, so it is easier to hit.
- **The big counters show the list you are looking at.** On the Applications tab, the cards at the top (Total installed, Running,
  Frozen / disabled, User apps, System apps, Bloatware) light up with a colored border and glow for the filter that is on. They
  follow the filter pills below them (and the pills follow them), and the last filter is lit again when the app opens. A filter
  with no card of its own (Suspended, Updated 7d, Patched …) lights none. The cards also work from a keyboard and for screen
  readers: each is a button with a pressed state.
- **A tip under Export and Share CSV.** It says to scroll the filter pills sideways for more filters. It goes as soon as you
  scroll them (or tap its ✕) and stays gone.
- **Permissions on first launch.** The first time the app opens, a sheet offers the three accesses Android keeps in its
  settings: **All files access**, **Usage access** and **Display over other apps**. **Allow** opens that screen of Android's
  settings; **Allow all** walks through the ones still missing; with ADB, Shizuku or Root the app switches the last two on by
  itself, with no screen. Skip any of them with **Not now**: they are also under **About → Permissions**. After this update
  the sheet comes once, if one of the three is missing. It waits for What's new to be closed first.
- **File access is asked for when an action needs it.** When opening, editing, saving, adding or deleting a file, reading a
  package from storage, listing a storage folder you chose, or searching storage fails for want of All-files access, a sheet
  says what was being done and has an **Allow** button. When the access arrives the sheet closes and the action carries on by
  itself: the folder is listed again, the search starts again, the package is read again (an action asked for more than five
  minutes ago is left alone). **Not now** cancels it. The failures that follow straight away (one action, many files) do not ask
  again, but a button you press yourself (Go in the file manager, Find APKs) asks every time. The Files tab and the search the
  Installer starts when it opens only show a note with a button, not a sheet. With ADB, Shizuku or Root the storage is read
  without the access, so nothing is asked. A failure on a path the access cannot help with (system folders, other apps' data)
  asks for nothing. On Android 10 the app now also asks Android for the old file access (`requestLegacyExternalStorage`), which
  its storage permission needs there to reach files at all.
- **Launch Application and Application Settings after an install.** When the Installer reports a success, its result
  dialog has two more buttons above **Done**: **Launch Application** opens the app that was just installed (a toast says it
  went through; only a failure says why) and, under it, **Application Settings** opens Android's page for it. The dialog is
  taller to make room, and the output box keeps its height. On a short screen the dialog scrolls instead. Both buttons act on
  the package that was installed, even if you opened another one while the install ran.
- **The Installer scrolls to the package.** After you pick a file, also from the Find APKs list, the page glides down to the
  box with the package's name, version and signature (it does not move if you have left the tab, and it does not animate when
  your phone asks for reduced motion).
- **A progress bar for Find APKs on this device.** A bar under the status line shows how far the search is with the number of
  files found so far. Where the search cannot tell (the one shell command of a working mode) the bar sweeps instead.
- **Delete a found package from the phone, with Undo.** Press and hold a file in the Find APKs list and confirm: the file is
  removed from storage and an **Undo** bar stays for eight seconds. A tip under the list says so. While the bar is there the
  file waits in a hidden folder on the same storage; it is removed for good when the bar goes, when you leave the Installer or
  at the next start (if the app cannot reach storage then, at the one after). If a file with the same name has appeared in the
  meantime, Undo restores yours as "name (2)" rather than replacing it, and an Undo that fails is offered again. Only regular
  package files can be deleted this way, never a folder, and two files deleted at once cannot replace each other in the trash.
- **The splits that fit this phone are ticked.** Opening a package with splits ticks the base, the one CPU split the phone runs
  best, the screen-density split Android itself would pick for the phone's density and the phone's languages. The rest
  stay off, each with a note (another CPU, another language, a feature module …) so you decide whether to add it. A **Match this
  phone** link ticks them again after you changed things.
- **▾ Common installers and requesters.** The **installer source (-i)** box and the **requester / originating URI** box each have
  a small ▾ button at their end. It lists common values and fills the box when you pick one: Google Play, F-Droid, Aurora Store,
  Amazon Appstore, Samsung Galaxy Store, Huawei AppGallery, Xiaomi, OPPO / realme, vivo and HONOR stores, APKMirror Installer,
  Obtainium, Droid-ify, Neo Store, Accrescent, Aptoide, APKPure, Uptodown, itch.io and more for the first; Play, F-Droid,
  APKMirror, GitHub, itch.io and other addresses (with the package filled in) for the second. A **Clear this box** entry empties it.
- **Keyboard and screen readers.** The found files (Enter loads one, Delete opens its delete sheet), the ✕ of the tip, the
  colors button, the three Allow buttons (each named for what it allows) and the two ▾ lists work from a keyboard and say what they are.
  Pressing and holding a found file no longer starts Android's own text selection, and the permission prompt is painted over
  every other sheet, so it cannot hide under the one that raised it.
- **"Select all splits by default" is off by default.** The switch at the bottom of the Installer's options starts off (only
  the base and the splits that fit the phone are ticked). It still ticks every split when you turn it on.

## v6.0.4-Pro (versionCode 604)

- **A launch that works shows no dialog.** In an app's **Components** tab, **Launch** now just opens the activity and a toast
  says it went through. The sheet with Android's answer opens only when the launch fails, so a launch that worked no longer leaves
  a dialog to close.

## v6.0.3-Pro (versionCode 603)

- **The app menu is a little taller.** The sheet that opens from ⋯ on an app now stands 93% of the screen high instead of
  85%, so the lists under its buttons (Permissions, App Ops, Components, Manifest) get more room: 64 px more on a 360 × 800
  screen. Only this sheet changed; the others keep their height, and the strip above it still closes it when you tap it.

## v6.0.2-Pro (versionCode 602)

- **The Share APK button is gone from the app menu.** Tap ⋯ on an app and the action grid no longer has it. **Extract APK**
  is still there and still saves the app's `.apk` (or an `.apks` bundle for a split app) to Downloads and tells you where.
  Nothing else in the menu changed.

## v6.0.1-Pro (versionCode 601)

- **The Settings tab is now called Hidden Settings.** "Settings" sounded like this app's own settings, but the tab reads
  and edits Android's own **Global**, **Secure** and **System** settings tables, so it now says so
  (its card reads "Android's hidden settings"). Nothing else about it changed, and what you had saved (the log of
  changes with its Revert buttons, the table, filter and sort you last used) is kept.

## v6.0-Pro (versionCode 600)

- **An Overlays tab: recolor Android and switch its overlays.** A new tab right after Settings with two sub-tabs.
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

- **A Settings tab: read and edit Android's hidden settings.** A new tab before Store lists every setting in the
  phone's **Global**, **Secure** and **System** tables (what `settings list` prints), one sub-tab per table with its
  count. **Tap** a setting to edit it, **press and hold** one that is a switch (`1`/`0`, `true`/`false`, `on`/`off` or
  `yes`/`no`) to flip it, keeping the capitalisation it had, and tap **＋** to create a setting of your own in any of the
  three tables. It works through ADB, Wireless Debugging, Shizuku or Root; with none of them ready the tab says so and
  opens Working Modes.
- **Easy to find your way around.** Search matches names, values and descriptions; chips narrow the list to **Switches**,
  settings with a **Description**, or the ones **Edited** with this app; sort by name or by value. About 130 well-known
  settings carry a plain-English description (`adb_enabled` is "USB debugging"), the ones that can cut your ADB link, lock
  you out of the screen or break setup are marked **careful** and ask before they change, and long tables are drawn 120 rows at a
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
  a value too big for adb to carry is refused instead of corrupting the request; and **Command** copies a command that runs
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
- **An About tab.** The new last tab shows the developer (**Bingblop**) and the GitHub repo, this build's version,
  package, device, WebView and **signing certificate** (when it is the official release key), copy / share
  **debug info** for bug reports, tips, privacy and credits, and **Buy me a coffee**: one tap opens PayPal to
  donate **$1**, or type any amount. Entirely optional; the app stays free.
- **Sign APKs you edited — on the device.** Editing an APK in the archive browser breaks its signature, so there is now
  a **Sign** button (and an "edited — sign it now" banner): it signs with **APK Signature Scheme v2** using a key that
  is generated inside the **Android Keystore** (in secure hardware where the phone has it, never exported). Sign in place
  or save a `-signed` copy, see the signature now / the installed copy's signer / this key's fingerprint, get a warning
  when an installed copy has a different signer, and install the result in one tap. Checked against the real
  `apksigner` for RSA and EC keys, multi-megabyte APKs and tampered files. Stored `.so` files are now 16 KB-aligned.
- **Archive browser: install, nest, compare.** **Install** an APK/APKS/XAPK from inside an archive without extracting it
  by hand, **open an archive inside an archive**, and **Compare** two archives (or an archive and an installed app) —
  added / removed / changed files, and a **line-by-line diff** of changed text and compiled XML (a new permission shows
  up at a glance). The extract folder is remembered.
- **Terminal: history, saved scripts and pinned buttons.** Up/Down arrows and a History list recall earlier commands, Save keeps
  named commands and multi-line scripts, and up to six can be **pinned as one-tap chips**. Works in the normal terminal
  and in Rish mode. The normal terminal no longer freezes the page while a command runs.
- **Logcat for one app.** Pick an app (or tap **Logs** in an app's menu) to see only the lines it wrote — across restarts,
  matched by its user ID — and **save or share** the filtered log as a bug-report text file with a header. The live view
  draws the latest 800 entries, skips redraws when nothing changed, and polls off the page's thread.
- **File manager: select many.** Press and hold a row (or tap Select), pick files and folders, then **Copy** or **Move**
  them (open the target folder, **Paste here**) or **Delete** them in one go. Many files go through a few shell commands
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

- **Terminal: Rish mode replaces "adb devices".** Tap **Rish mode** and the app switches the working mode to
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
  of how to make this app the APK handler stays at the bottom of the card — and in its place a **Find APKs on
  this device** button runs an automatic storage search for every `.apk`, `.apks`, `.apkm` and `.xapk`
  and lists them (with size, age, folder, search and type filter); tap one to load it.
- **Store: "Komi" is now "GitHub", the catalogs are complete, and every store has a category drop-down.**
  - **GitHub** (was Komi) browses the whole catalog page by page (up to 5,000 Android apps) with live search
    and direct `owner/repo` install, and falls back to a built-in list when offline.
  - **F-Droid** now lists **every app in the chosen repo** (the index is streamed, so even the very large official
    index loads), defaults to the official F-Droid repo with a repo picker, caches for 12 hours, verifies each
    download's SHA-256, and asks first on a metered connection.
  - **Orion** now shows its whole catalog (887 apps; a size cap used to cut the last 87 off).
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

- **The Store is now five sources, in sub-tabs.** The Store tab opens on **ShizuStore** and adds four
  more sub-tabs beside it:
  - **Komi** — a curated set of trusted open-source apps that publish their APK on **GitHub Releases**
    (the kind of GitHub app store [komi-store](https://github.com/komi-store/komi-store) is built for).
    Each app is resolved live to the right build for your device's ABI and installed through your active mode.
  - **F-Droid** — the known third-party **F-Droid repositories** from the community
    [known-repositories](https://forum.f-droid.org/t/known-repositories/721) list. Tap a repo to browse and
    install its apps directly, or copy its address & fingerprint to add it to your F-Droid client. (The very
    large catalogs show their add-to-client details instead of browsing in-app.)
  - **Orion** — the public [Orion Store](https://github.com/RookieEnough/Orion-Store) catalog
    (`RookieEnough/Orion-Data`), including Morphe-built apps, resolved from GitHub, Codeberg and direct links.
  - **Aurora** — an honest hand-off to **Aurora Store** for Google Play apps (the private Play API can't be
    reimplemented in-app). Google Play and APKMirror sources are intentionally not bundled.

  Every install still downloads from each app's own upstream — nothing is rehosted — and runs through this
  app's installer (ADB / Shizuku / Root, or the system installer).
- **Better patched-app detection: Morphe and same-package ReVanced builds.** The patched detector now
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
  **badge** when it looks patched or repackaged by a third-party tool — **ReVanced**, an
  **Xposed / LSPosed module**, **LSPatch**, **NPatch**, or an app re-signed with a **debug key**. A new
  **Patched** filter lists only those apps. Detection in the list is free (package name, the manifest's
  `appComponentFactory` and meta-data, and the installer), so it adds no load time.
- **A definitive breakdown in the app inspector.** Opening an app (⋯ → inspector) runs a deeper scan that
  also reads the signing certificate and the APK's own entries (`assets/lspatch/`, `assets/xposed_init`,
  `META-INF/xposed/`, NPatch and ReVanced markers), and shows exactly which tool(s) touched the app and
  which installer put it there — so you can tell a genuine store build from a modified one before trusting it.

## v5.3-Pro (versionCode 530)

- **ShizuStore tab.** A new **Store** tab (at the end) browses [ShizuStore](https://github.com/timschneeb/ShizuStore)'s
  curated catalog of Shizuku-powered apps. Search and sort by most starred, most downloaded, recently
  updated or added; open an app for its description, screenshots, star count, requested permissions and
  source link; then **Install** downloads the APK straight from the developer's own upstream (GitHub /
  GitLab / F-Droid) and installs it through your active mode (ADB / Shizuku / Root), or hands it to the
  system installer with no privileged mode. Catalog and metadata come from ShizuStore by timschneeb; APKs
  are served by each app's developer, not rehosted.
- **Optional VirusTotal scan in the Installer.** The Installer tab has a new **VirusTotal scan** card.
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

- **Pair over Wi-Fi from a notification.** The Wireless Debugging card has a new **Pair via Notification**
  button. It drops a high-priority notification with an inline reply box, so while Android's *Pair device with
  pairing code* dialog is on screen you can type the 6-digit code straight from the notification shade - no
  switching back to the app, so the code can't rotate out from under you. The app finds the current pairing
  port over mDNS automatically; if it can't, reply with `port code` (e.g. `37123 123456`). Pairing runs in the
  background and the notification updates with the result. If notifications aren't allowed yet, the button
  requests the permission first.

## v5.1-Pro (versionCode 510)

- **ADB cheat sheet in the terminal.** The ADB Console tab has a **Cheat Sheet** button: a searchable,
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

- **Update this app from inside the app.** The Updates tab now has a dedicated **App update** card at the
  top, just for ADB Application Manager Pro itself. It checks this project's GitHub releases, shows your
  installed version vs. the latest with the release notes, and updates in one tap. The download is verified to
  be this app, a newer version and **signed with the same key** before installing. With ADB / Shizuku / Root
  the install is seamless (the app restarts); with no privileged mode it hands the APK to the system installer
  for a normal confirmation. Checking for updates also refreshes this card.
- **Unexported activities launch reliably.** Launching an activity through ADB / Shizuku / Root now starts it
  in a **new task** (`FLAG_ACTIVITY_NEW_TASK`), with fall-backs to a plain start and `cmd activity`. Without
  the new-task flag an activity often reported success yet never appeared - the usual reason an unexported
  activity "wouldn't launch". If every method fails, each attempt's output is shown so the real error is visible.
- **Navigation tidy-up.** A **Colors & Themes** button moved to the header (top-right), so themes are one
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

- **New Installer tab — an all-in-one APK / APKS / APKM installer.** Pick a package file and the app
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
- **New Files tab — a privileged file manager.** Browse any path with ADB / Shizuku / Root, view text
  files, create folders, rename, copy, move and delete, and install an APK from any location (staged to a
  readable temp, then handed to the Installer). Every path is shell-quoted.
- **New Logcat tab.** Read the device log (`logcat -d`) with level, line-count and text/tag filters
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

- **What's new**: the changelog is bundled in the app. After an update it opens once with the changes
  since the version you last ran; **Color & Themes → About** has the button, version and links.
- **Watch a profile**: in **Profiles**, tap **Watch** on one profile. When apps no longer match it
  (typically after a system update brings them back) a banner on the Applications tab says how many and
  opens the same reviewed Apply step. After a reboot with a new system build, a notification reminds you (Android 13+
  asks for notification permission the first time you watch a profile). Nothing is changed without your
  confirmation.
- **Quick Settings tiles and a home-screen widget** (they run without opening the app):
  - **Working mode** tile / **Mode** widget button: switches to the next mode that is ready
    (ADB TCP → Wireless Debugging → Shizuku → Root), or back to Automatic.
  - **Stop apps** tile / **Stop apps** widget button: force-stops every app in your **quick list**.
    Choose it with **Quick list** on a card in **Saved Lists**.
- **Backup and restore**: **Backup** in the app menu (and **Backups** on the Applications tab).
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

- **Copy buttons and share sheet**: Package / Version / Name chips and Share in the app menu;
  **Copy Packages** and **Share List** for a batch selection; **Share CSV** for the app list; **Share**
  for the manifest and the terminal output; **Share APK** extracts an app and opens the share sheet.
  Files go through a private, non-exported provider (`ShareProvider`) with one-off read grants.
- **Search with highlight**: the manifest viewer and the terminal highlight every match, show
  "2 / 7 matches" and jump with ▲ ▼. The manifest viewer keeps "Matches only" (with line numbers) and can
  show the whole file in context instead.
- **App profiles**: save the disabled / suspended / uninstalled apps under a name, preview what
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
- **Updated 7d** filter for apps updated in the last week (handy for spotting a bad update).
- **Export** saves every app with its version, install and update dates, type, state, APK size and
  any known update as CSV to `Download/ADB App Manager/`.

### App menu
- **Sizes**: APK size (and how many parts a split app has); data and cache too once **usage access**
  is allowed. "Show data usage" grants it through ADB/Shizuku/Root, or opens the settings page.
  Sorting by size then uses total storage instead of APK size.
- **Extract APK** saves the app to `Download/ADB App Manager/APKs/`: a single `.apk`, or for split
  apps a `.apks` bundle (base + splits) that split-APK installers such as SAI can install.

## v4.3-Pro (versionCode 330)

Installs over v3.1 – v4.2 without uninstalling (same signing key).

- **Install and update dates** in the app menu, under the version
  ("Installed Jan 15, 2024 • Updated Sep 20, 2026").
- **Versions in the app list**: each app shows its version on the badge row. The **Versions**
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
- **Import Obtainium List** reads an Obtainium export (Settings → Export) so every app you track
  in Obtainium is checked here too.
- Apps whose releases live somewhere only Obtainium can track get **Open in Obtainium**; sideloaded
  apps with no known source are listed under **Not tracked** with **＋ Set source**.
- Before installing, the download's **signing key is compared** with the installed app. A mismatch
  (for example an F-Droid build over a developer build) is stopped with a clear explanation instead
  of a failed install.
- Update All covers every installable source; releases without an APK for this phone link to their
  release page.

## v4.0-Pro (versionCode 300)

Installs over v3.1 – v3.9 without uninstalling (same signing key).

### Updates tab
- Checks the **Galaxy Store** for updates to Samsung system apps and apps installed from the Galaxy
  Store, using Samsung's public update service, and checks this app's **GitHub Releases**.
- **Update** per app, or **Update All** (one at a time). Each update is the official APK from the
  Galaxy Store, verified to be the same package and a newer version, then installed through ADB,
  Shizuku or Root. Failures show Android's reason with a Retry button.
- Updates for this app open in the browser so Android's installer handles them.
- The tab shows how many updates are pending. Play Store apps aren't covered: Google provides no way
  for other apps to check them, and the Play Store keeps updating them itself.

### Debloat history
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
- New brand filter row in the Debloater. Your phone's own brand comes first (outlined in the accent color, detected from the
  phone's manufacturer), followed by the other brands found on the phone (Google, Meta, Microsoft,
  carriers, chip makers...), each with a package count that follows the other filters.
- UAD-NG has no brand field, so the maker is worked out from the package name (`com.samsung.*` /
  `com.sec.*` → Samsung, `com.facebook.*` → Meta, `com.oplus.*` → Oppo, and so on), with a
  fallback to the organisation part of the name. Each package also shows its brand as a badge,
  and search matches brand names.

## v3.8-Pro (versionCode 280)

Installs over v3.1 – v3.7 without uninstalling (same signing key).

### Debloater tab
- New **Debloater** tab powered by the
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
- Suspended apps are marked in the app list with a **SUSPENDED** badge and a greyed-out icon,
  and a new **Suspended** filter shows only them. Suspension is detected in every mode,
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
