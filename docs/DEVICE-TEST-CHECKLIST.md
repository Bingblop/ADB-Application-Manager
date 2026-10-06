# On-device test checklist (v7.9.14 to v7.10.0)

The automated tests run the page in a browser with a pretend phone. They cannot prove the parts that talk to Android:
icons, the file chooser, added storage, shell commands and the haptic feedback. This list is for those. Tick a box when
it works; write down what you saw when it does not.

**Before you start**

- [ ] Install the APK over the old version (it must keep your data). About shows the version you expect.
- [ ] A working mode is on (ADB over TCP, Wireless Debugging, Shizuku or Root). Most items below need one.
- [ ] Use a phone you do not mind changing: some items disable apps or change app ops. Everything can be undone from the same screens.

## 0. New in v7.10.0

**Header, banner, guide, looks**

- [ ] The header has no icon: the title "Full ADB/Root System Manager" starts at the left edge in two fitted lines. Two smaller uppercase lines show the manufacturer/model and Android version, plus One UI on Samsung. The gear and working-mode badge have matching heights. Try a narrow screen and a larger system font: no title, subtitle, or button is cut off.
- [ ] Without a working mode the orange Read-Only banner shows with an X in its top right corner; the X puts it away; closing the app completely and opening it again shows it again.
- [ ] About, **Help Guide** (next to GitHub): opens without a network, with 80 topics in six groups. Tapping a topic opens it on its own; Previous/Next and Contents work. Search shows matching topics with snippets. Back closes the guide, and reopening continues where you were.
- [ ] Bottom sheets show a small grab handle; scrollbars are thin; a tab fades in when opened. Nothing is cut off or moved.
- [ ] **Granular Color Pickers**: all seven buttons open the popup. Both Colors and Custom have the fixed # prefix; typing or pasting `#00dfff` produces `00DFFF`. Hue/saturation/brightness sliders and both hex boxes stay in sync. Apply is disabled until six hex digits are present. Cancel and Android Back change nothing; Apply and Reset tweaks work in both light and dark mode.

**Latest Application Manager changes**

- [ ] The button row has Share CSV, Profiles, Backups, and the Action Button chooser, with no Export button. The Versions pill and versions on app rows are gone; version information is still available in the app menu.
- [ ] Choose an Action Button in the Apps tab: row actions change and Settings shows the same choice. Change it in Settings and check the Apps chooser. Force-close and reopen the app: both choosers and the row actions restore the saved choice.
- [ ] With the UAD-NG list downloaded, listed apps show their classification chip on the row. Tap one: its description and dependencies open without selecting the row or opening the app menu. An unlisted app has no chip, and the chip in the app menu still works.

**The app menu and Settings**

- [ ] Open an app's menu: the pills are Permissions, App Ops, Activities, Components, Manifest, Raw, Features, Configurations, Signatures, Libraries (swipe the row sideways; the picked pill slides into view). No "Copy version" chip. **Activities** lists the screens (LAUNCH, DISABLE / ENABLE); **Components** lists receivers, services (STOP) and providers.
- [ ] **Features**: a game shows OpenGL ES with "on this phone"; the Not on this phone chip lists what the phone lacks. **Configurations**: minimum / target / compiled Android versions are right for an app you know. **Signatures**: v2 / v3 are lit for a recent app; the SHA-256 matches what `apksigner verify --print-certs` (or the app's maker) says; tapping it copies it. **Libraries**: a Flutter or game app lists its .so files under arm64-v8a.
- [ ] Uninstall, Clear Data and Rem Updates in the app menu ask first (Cancel changes nothing); Freeze and the others do not. The batch Freeze question reads "Confirm Freeze Selected Apps".
- [ ] Settings, last card: **Trim Caches in All Applications**. Without a working mode it shows a padlock. With one: the question, then "Trimming caches…", then a report with free space before and after (a heavily used phone frees a few hundred MB or more). Apps, logins and files are intact.
- [ ] The permissions sheet has **Install unknown apps** (Allow opens Android's screen, or grants at once with a working mode). Working Modes, Root: with Magisk / KernelSU set to deny this app the tag reads DENIED and the badge says "not ready"; after allowing it and re-selecting Root it reads ACTIVE.
- [ ] The small **?** on the first card of a tab (Debloater, Installer, Files, Connected Devices, Settings, ...) opens the Help Guide at that tab's topic.
- [ ] **Connected Devices**: Uninstall a system app on a watch that refuses with "only root can delete system app" (a Samsung watch, for example): the app is removed anyway and `/data/local/tmp` has no `adbam_unin_*.jar` left (`adb shell ls /data/local/tmp`). Reinstall brings it back.

**APK variants (needs one 32-bit phone and one 64-bit phone)**

- [ ] The release has three APKs and a `SHA256SUMS.txt` that lists them. `-arm64-v8a` installs on the 64-bit phone and its working modes work as before.
- [ ] `-armeabi-v7a` installs on the 32-bit phone, ADB over TCP / Wireless Debugging connect (the 32-bit adb starts), and Connected Devices can list devices. Write down the model and Android version if it does not start.
- [ ] `-universal` installs on both. The self-update (About, Check for update) offers the matching variant.
- [ ] A phone without a working mode: **APK Installer**, Authorizer **No privilege**, installs a small APK (Android asks once to allow installs from this app).

**Connected Devices** (needs a Wear OS watch, or a second Android phone, on the same Wi-Fi)

- [ ] On the watch: Developer options, ADB debugging and Debug over Wi-Fi on. **Add device**, Wi-Fi tab: Pair (address:port and the 6-digit code from the watch), then Connect. The watch shows in the picker as Connected with its model, Android version, screen, battery and Wear OS.
- [ ] **Nearby**: Scan lists the watch; Pair / Connect from the list work.
- [ ] **Bluetooth** (a watch paired with the Wear OS app, Debugging over Bluetooth on in the app and on the watch, ADB on in this app): Connect over Bluetooth adds a device with the Bluetooth link badge; allow it on the watch.
- [ ] A device that has not allowed this phone says so; after tapping Allow on it and Refresh it becomes Connected.
- [ ] **Apps**: the list loads, filters and counts work, Disable then Enable a user app, Uninstall and Reinstall a system app you do not need (then put it back), Pull APK saves into Download/ADB App Manager/Devices.
- [ ] **Send**: an APK, an APKS or XAPK, and "From this phone" install on the watch; the progress bar moves, Stop works, every package shows Installed or the reason. Bluetooth sharing opens the Bluetooth app's picker.
- [ ] **Console**: `getprop ro.product.model` answers; `adb pull /sdcard/x /sdcard/Download/` runs through adb; `adb kill-server` is refused.
- [ ] **Logcat**: Play follows the log, Clear empties it. **Files**: browse, view a text file, pull one, send one here, rename, delete a test file. **Hidden Settings**: read, change and put back one value you can see (screen_off_timeout).
- [ ] **Display**: pick another density, Apply: the watch changes at once and Keep / Put it back counts down 15 s; leave it alone and it goes back by itself. Reset to default and the size box work.

## 1. Start and Apps list (v7.9.18, v7.9.19)

- [ ] Force-close the app, open it again: it is on **Application Manager** with the **Total Installed** card lit, even if you had left another filter on.
- [ ] Each row shows the app's icon on the left, no letter box, and the checkbox sits to the right of the "..." button.
- [ ] Tap a row's name: it selects. Tap the checkbox: it selects. Selecting shows the round checkmark button.
- [ ] **Hold** a row (not the icon, not a button) for half a second: you feel a tap and that app's menu opens. Letting go does not also select the row. No text gets highlighted.
- [ ] **Hold an icon**: "Icon saved to Download/ADB App Manager/Icons" and the PNG is there.
- [ ] Share CSV, Profiles and Backups have an outline and light up when pressed; every button gives a short vibration. Export was removed in v7.10.0; the Action Button chooser is at the end of this row.
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

## 11. Filters (v7.9.20, v7.9.21)

- [ ] The top of Application Manager has Total installed, Running and Bloatware on one row, then two boxes below: **Enabled | Frozen** and **3rd Party | System**. Each half shows its own count.
- [ ] Tap **Running**, then **3rd Party**: the list shows your running user apps, both buttons are lit, and a line under Sort names the filters with a **Clear filters** button.
- [ ] Tap **Enabled**, then **Frozen**: Frozen replaces Enabled (a pair is one filter); tap it again and neither is on.
- [ ] Tap **Enabled** then **System** (and the pills in the row below): you see only your enabled system apps; boxes and pills light together.
- [ ] **Clear filters** turns every filter off and the Total installed box lights again. With only one filter on, the button is not shown.
- [ ] Debloater: the "Also show only" row combines with Removal, vendor and state; with two or more on, **Clear filters** appears and clears that row only.
- [ ] Saved Applications: pills apply inside each list (counts update, lists with no match hide); **Clear filters** shows with two or more on and brings every list back.

- [ ] Freeze an app, then uninstall it (Debloater, Uninstall): it leaves the **Frozen** box and filter and shows under **Bloatware (Uninstalled)** only. Reinstall it: if it is still disabled it is **Frozen** again.
- [ ] Files, search for a word (for example `jpg`), then tap **Images**, **Over 10 MB**, **Last 7 days**: the list narrows, the count says how many are shown, and **Clear filters** brings everything back.
- [ ] Added storage (SD card or USB): search `content:word` finds text files that hold the word; `archive:` says it is skipped.
- [ ] Settings, Lists, **Remember my filters** on: turn on Running + 3rd Party, force-close the app, open it again: the same filters are on. Off: it opens on Total Installed.

- [ ] With no working mode on, open an app's menu: the permission toggles, the App Ops modes (on the mode that is set) and every Enable / Disable, Stop and Launch button of activities, services, receivers and providers show the lock and look dimmed. Turn a working mode on and open the menu again: only install-time permissions keep the lock.

## Report

Note the phone model, Android version, working mode and the item number of anything that failed, then open an issue
at https://github.com/Bingblop/ADB-Application-Manager/issues.
