# Phone script for v7.13.4

One page, about 10 minutes, one pass or fail per step. v7.13.4 moves Backup from an app's menu to the batch menu and lets it back up several apps; nothing else changes. Nothing in it has been run on a phone by the author. Any working mode will do; Root is needed only for step 4.

**Before you start:** install the `arm64-v8a` file of v7.13.4 over your current version (an update, not a fresh install). About says 7.13.4. Pick two or three small user apps (not the ones you rely on).

## A. The menus (2 minutes)

1. Open an app's ⋯ menu: the buttons are Launch … Extract APK, Optimize, Standby, Logs. **Pass:** there is no Backup button, and the sheet is not cut off.
2. Select one app (tap its icon), then tap the round button. **Pass:** the batch menu has **Backup** next to Batch Ops and Command, and no Share List. **Copy Packages** still copies the package names.

## B. Backing up (5 minutes)

3. Select three apps and tap **Backup**. **Pass:** the Backups box opens with a card "Back up 3 apps" and their names, the switch **Include app data** and **Create backup**. Tap **Create backup** with the switch off. **Pass:** the line says "Backing up (name) (1 of 3)...", moves to the next app, and ends with "Saved 3 of 3 apps to Download/ADB App Manager/Backups/". The three files are listed below the card.
4. (Root only) Tick **Include app data** and back up two apps. **Pass:** both are saved "with data" in the list; each app is force-stopped while its data is copied, so do not do this with an app you are using.
5. Switch the working mode to Read-Only, select an app, tap Backup, then Create backup. **Pass:** "Working Mode Required" opens and nothing is saved. Switch back to your mode afterwards.

## C. Restore still works (3 minutes)

6. In the Backups box tap **Restore** on one of the new backups, then **Confirm restore**. **Pass:** the app is installed from the backup (or the same version is skipped) and the note shows the result. **Share** opens Android's share sheet; **Delete** removes the file after a question.
7. With no backups at all, the empty list says "Select apps, open the batch menu and tap Backup, or choose a backup file from another phone."

Report any step that fails with the step number, the working mode, and the phone model.
