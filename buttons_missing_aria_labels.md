# Icon-only buttons missing aria-labels

Here is the list of icon-only buttons or buttons lacking text that require `aria-label`s, which should be added through the i18n system as per the user's instructions:

1. `⋯` Menu buttons:
   - `<button class="btn-mini row-menu" title="Menu" onclick="openInspector(this.closest('.app-card').dataset.pkg)">⋯</button>`
   - `<button class="perm-toggle-btn locked" onclick="arcMenu(${i})">⋯</button>`

2. `⋮` Device menu buttons:
   - `<button class="btn-mini cd-dev-more" title="Device menu" onclick="cdDeviceMenu()">⋮</button>`
   - `<button class="btn-mini row-menu" title="Menu" onclick="cdAppMenu(this.closest('.app-card').dataset.pkg)">⋮</button>`
   - `<button class="btn-mini" title="Menu" onclick="event.stopPropagation(); cdFmMenu(' + i + ')">⋮</button>`

3. `✕` Close / Clear / Dismiss buttons:
   - `<button class="mode-alert-btn" onclick="dismissDrift()" style="margin-left:6px;">✕</button>`
   - `<button class="batch-tool-link" onclick="fmSelExit()">✕</button>`
   - `<button class="batch-tool-link" onclick="fmClipClear()">✕</button>`

4. Terminal icon buttons (`.term-icon-btn`, `.tx-key-base`):
   - `<button class="term-icon-btn" id="txFullBtn" onclick="txFullToggle()" aria-pressed="false">Full screen</button>` (This uses text but acts as an icon toggle in some contexts).
   - `<button class="term-icon-btn" id="txRealBtn" onclick="rtOpen()">Real terminal</button>`
   - `<button class="term-icon-btn" onclick="txClearScreen()">Clear</button>`
   - `<button class="term-icon-btn" onclick="txCopyScreen()">Copy</button>`
   - `<button class="term-icon-btn" onclick="openCheatSheet(true)">Cheat Sheet</button>`
   - `<button class="term-icon-btn" onclick="txOpenTermux('')">Open in Termux</button>`
   - `<button class="term-icon-btn" id="txNewChatBtn" onclick="txNewChat()" style="display:none;">New chat</button>`
   - `<button class="term-icon-btn" id="txRestartBtn" onclick="txRestartShell()">Restart shell</button>`
   - `<button translate="no" data-k="esc" class="tx-key-base" title="Clear the line">ESC</button>`
   - `<button translate="no" data-k="tab" class="tx-key-base" title="Complete a file name">TAB</button>`
   - `<button translate="no" data-k="ctrlc" class="tx-key-base tx-key-stop" title="Stop what is running">CTRL</button>`
   - `<button translate="no" data-k="home" class="tx-key-base" title="Start of the line">HOME</button>`
   - `<button translate="no" data-k="end" class="tx-key-base" title="End of the line">END</button>`

5. Various other small buttons (`.btn-mini`, `.term-mini`, `.perm-toggle-btn`):
   - `<button class="term-mini run" onclick="termHistRun(${x.i})" title="Run">Run</button>`
   - `<button class="term-mini" onclick="termHistSave(${x.i})" title="Save">Save</button>`
   - `<button class="term-mini run" onclick="termSavedRun(${i})" title="Run">Run</button>`
   - `<button class="term-mini${x.pinned ? ' pinned' : ''}" onclick="termSavedPin(${i})" title="Pin as a chip">${x.pinned ? 'Unpin' : 'Pin'}</button>`
   - `<button class="term-mini" onclick="termEditStart(${i})" title="Edit">Edit</button>`
   - Action buttons without text generated via `abSvg(sp.icon)`.

6. Others without text (relying on title or context):
   - `<button type="button" class="bn-item" data-more="1" aria-haspopup="dialog" aria-expanded="false" onclick="bnavMoreOpen()"></button>`
   - `<button type="button" class="xq-btn" id="xqSheetCancel" onclick="xqSheetClose()"><span id="xqSheetCancelT"></span></button>`
   - `<button type="button" class="xq-btn is-primary" id="xqSheetOk" onclick="xqSheetGo()"><span id="xqSheetOkT"></span></button>`
   - `<button type="button" class="xq-btn is-primary xq-hold" id="xqSheetHold"><span class="xq-ring" aria-hidden="true"></span><span id="xqSheetHoldT"></span></button>`

## Done in v7.19.3

Four of these had neither text nor a `title`, so a screen reader had no name to say. They now have an `aria-label` from a string that is already translated (the page's language engine translates `aria-label` like `title`):

- Drift banner `✕` (`dismissDrift()`): *Dismiss*
- File Manager selection bar `✕` (`fmSelExit()`): *Cancel*
- File Manager copy/move bar `✕` (`fmClipClear()`): *Cancel*
- Archive viewer row `⋯` (`arcMenu(i)`): *Menu*

The others need nothing:
- The `⋯` / `⋮` menu buttons and the terminal keys have a `title`, which is their accessible name.
- The terminal and history buttons show their words.
- The action buttons (`abSvg`) carry `aria-label` and `title`.
- The bottom bar's More button and the hold-to-confirm sheet's buttons get their words when the page draws them.
