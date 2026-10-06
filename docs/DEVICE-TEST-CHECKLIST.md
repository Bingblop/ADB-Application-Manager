# On-device test checklist (v7.9.14 to v7.9.19)

The automated tests run the page in a browser with a pretend phone. They cannot prove the parts that talk to Android:
icons, the file chooser, added storage, shell commands and the haptic feedback. This list is for those. Tick a box when
it works; write down what you saw when it does not.

**Before you start**

- [ ] Install the APK over the old version (it must keep your data). About shows the version you expect.
- [ ] A working mode is on (ADB over TCP, Wireless Debugging, Shizuku or Root). Most items below need one.
- [ ] Use a phone you do not mind changing: some items disable apps or change app ops. Everything can be undone from the same screens.

## 1. Start and Apps list (v7.9.18, v7.9.19)

- [ ] Force-close the app, open it again: it is on **Application Manager** with the **Total Installed** card lit, even if you had left another filter on.
- [ ] Each row shows the app's icon on the left, no letter box, and the checkbox sits to the right of the "..." button.
- [ ] Tap a row's name: it selects. Tap the checkbox: it selects. Selecting shows the round checkmark button.
- [ ] **Hold** a row (not the icon, not a button) for half a second: you feel a tap and that app's menu opens. Letting go does not also select the row. No text gets highlighted.
- [ ] **Hold an icon**: "Icon saved to Download/ADB App Manager/Icons" and the PNG is there.
- [ ] The Export, Share CSV, Profiles and Backups buttons have an outline and light up when pressed; every button gives a short vibration.
- [ ] Hold the round checkmark button: the selection clears.

## 2. Icon pack (v7.9.17, v7.9.18)

Needs an icon pack installed (Nova, ADW, Apex or Go style).

- [ ] Settings, **Icon pack** lists the installed packs. Choosing one redraws the list's icons with it.
- [ ] An app the pack does not cover shows its own icon on the pack's background/shape when the pack has one.
- [ ] "Default icons" goes back. Updating the pack or an app refreshes its icon.
- [ ] **Clear icon cache** says it cleared and the icons come back.

## 3. Action Button (v7.9.18)

Settings, **Action Button**, after Icon pack. For each choice, look at the icon on a row and tap it:

- [ ] **App Settings**: Android's App info opens.
- [ ] **Force Stop**: the app stops (a toast says so).
- [ ] **App Launcher**: the app opens.
- [ ] **Enable / Disable**: the icon is a check circle on a frozen app and a block sign on a normal one; tapping flips it and the icon follows.
- [ ] **Install / Uninstall**: Uninstall asks first; an uninstalled app shows the install arrow and Reinstall brings it back.
- [ ] **Unsuspend / Suspend**: same idea; the icon follows the state.
- [ ] **Permission Manager** and **Activity Launcher**: see sections 4 and 5.
- [ ] **None**: no extra button on any row; the "..." button stays.
- [ ] The button also shows on **UAD-NG Debloater** rows and follows the app's state there.
- [ ] **Hold the button**: a sheet of actions for that app opens (the hold does not also run the button). Picking one runs it.
- [ ] Settings, Action Button, **Hold menu**: move actions with the arrows, switch some off, Reset. The hold sheet follows. The last action cannot be switched off.

## 4. Permission Manager (v7.9.18)

- [ ] Each permission shows its label, description, group, protection level, granted or not, and whether it can be changed.
- [ ] Filters (Granted, Denied, Changeable, Locked) and search work.
- [ ] Tap a changeable permission's button: it grants or revokes (check it in Android's own app permissions).
- [ ] **Grant all shown** / **Revoke all shown** act on exactly what the filter shows, ask first, show progress, list any failure.
- [ ] The **App Ops** chip lists the app's app ops; Allow / FG / Ignore / Deny / Reset change them (check with `appops get <package>` in the terminal). A permission with an app op shows "App op NAME: mode".

## 5. Activity Launcher (v7.9.18)

- [ ] Activities show exported or not, enabled or not, and the permission they need.
- [ ] **Launch** on an exported activity opens it; on an unexported one it works in a working mode or says why not.
- [ ] Tick several, **Disable selected**: asks first; they show "disabled". **Enable selected** brings them back.

## 6. Batch menu (v7.9.18, v7.9.19)

Select three or four apps you do not mind changing, open the round checkmark.

- [ ] The menu is taller, has a fourth row (Batch Ops, Show Apps, Command), scrolls if needed, and has a **X** at the top right (like the app menu) that minimizes it. "Clear" clears the selection.
- [ ] **Show Apps** lists the selected apps; **Remove** takes one out (its checkbox in the list unticks).
- [ ] **Batch Ops**: tick two app ops with different values, **Apply**: progress bar and Stop work, the result lists each app. Check one app with `appops get <package>`.
- [ ] Presets: **Privacy lockdown** ticks ten ops; apply it, then **Undo Privacy lockdown** puts them back to default.
- [ ] Save your own preset, rename it (pencil), duplicate it (copy icon), delete it (X).
- [ ] **Command**: type `echo $package`, the preview shows the first app's name. Run: each app's output shows its own package name.
- [ ] **Dry run**: switch it on, run `pm clear $package`: it lists the commands and runs nothing. "Run it for real" reopens the sheet.
- [ ] **Run it on** a saved list (from Saved Applications): only the list's apps that are installed are used, the rest are counted as skipped.
- [ ] Save, rename, duplicate and delete a saved command.
- [ ] A results dialog's **Share CSV** opens the share sheet with a .csv that opens in a spreadsheet.
- [ ] **Run again** repeats the run; **Run again on the N that failed** appears when something failed (try `am start -n $package/.Nope`).

## 7. Export and import of presets and commands (v7.9.19)

- [ ] Batch Ops, **Export presets** opens the share sheet with a .json file; Command, **Export saved commands** does the same. Save one to Downloads.
- [ ] **Import...** opens Android's file chooser; picking that file again says "already here" and adds nothing.
- [ ] Delete a preset, import the file: it is back. Rename one in the app, import the file: the old name comes back as "(imported)".
- [ ] Import a text file that is not ours: "That is not a presets file from this app".

## 8. Press and hold guide (v7.9.18)

- [ ] Settings, last card, lists what can be held. Try each item it lists (app row, icon, Action Button, checkmark, file row, found APK, 1/0 setting, overlay row) and check it does what the guide says.

## 9. File Manager and added storage (v7.9.18)

- [ ] The toolbar has no Up button; the ".." row at the top of a folder goes up. Buttons have an outline and vibrate.
- [ ] **Add storage** an SD card or USB drive (or another app's folder). Open a folder, create a folder and a file, rename, delete.
- [ ] Select several items there (hold a row), **Copy**, open another folder, **Paste**: the copies appear. **Move** works too. Try a copy from phone storage into the added storage and back.
- [ ] A name clash asks Replace / Skip / Keep both. Stop works on a big copy.
- [ ] Open a picture, a PDF, a font and a zip from added storage: each shows in its viewer. **Open with** and **Share** work.
- [ ] **Extract** a zip from added storage: it goes to Download.
- [ ] Search inside added storage (a name, then `ext:jpg size:>1m`): it finds files. A `content:` search says it is skipped there.

## 10. Earlier v7.9.x features (quick pass)

- [ ] **Expressive Animations** (Settings, Motion): on by default, sheets and buttons spring a little; off makes them plain; the phone's "remove animations" setting always wins.
- [ ] **Termux** (Command-Line Interface, Termux shell): "Match my Termux environment" makes your aliases work; **Sync with Termux** opens a Termux session and grants storage access.
- [ ] Batch actions (Freeze, Enable, Suspend, Force Stop...) show progress and Stop, and the page stays smooth.
- [ ] The false "failed" fix: launch an app whose name contains "error" or "failed": no failure is reported.

## Report

Note the phone model, Android version, working mode and the item number of anything that failed, then open an issue
at https://github.com/Bingblop/ADB-Application-Manager/issues.
