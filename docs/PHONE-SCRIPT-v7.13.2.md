# Phone script for v7.13.2 (one page)

The same checks as section 29 of [DEVICE-TEST-CHECKLIST.md](DEVICE-TEST-CHECKLIST.md), in the order that finds problems fastest. About 25 minutes. Nothing here has been run on a phone by the people and tools that wrote it, so a step that fails is the useful result: write down the step number, the phone model, the Android and One UI version and the working mode (ADB, Wireless Debugging, Shizuku or Root), and open a [phone bug report](https://github.com/Bingblop/ADB-Application-Manager/issues/new?template=phone-bug-report.yml).

**Before you start:** install the `arm64-v8a` file of v7.13.2 over your current version (an update, not a fresh install). About says 7.13.2. Pick a working mode in Settings. Steps marked **(2 devices)** need a second phone or a watch listed in Connected Devices.

## A. SD Maid AppCleaner starts its accessibility service (5 minutes)

1. SD Maid tab, give the consent when asked. Android Settings > Accessibility: switch the app's service **off**.
2. Back in the app, AppCleaner: tick two or three apps with cache, run **Delete**. **Pass:** the progress line says it is starting the accessibility service; afterwards the service is **on** in Android's accessibility settings; the cleaning ran without you opening those settings. **Fail:** the old message asking you to turn it on, or nothing happens.
3. Android Settings > Apps > this app > **Force stop**. Open the app again and run step 2 once more. **Pass:** it starts the service again by itself. (Android removes the service when an app is force-stopped; this is the case the change is for.)
4. Switch to **Read-Only** mode and run AppCleaner Delete. **Pass:** nothing is switched on by itself and you see the old "turn it on" message.
5. SD Maid > AppCleaner > Exclusions: **this app** and the WebView packages (`com.google.android.webview`, `.beta`, `.dev`, `.canary`, `com.android.webview`) are listed. Turn **Force-stop before clearing** on and clean **all** apps. **Pass:** the app stays open, Android System WebView is not force-stopped, the cleaning is not cut short, and both show as excluded (not failed) in the result.
6. Remove one of those exclusions, then tap **Restore default exclusions**. **Pass:** it comes back.

## B. Connected Devices keeps to the device you chose **(2 devices)** (8 minutes)

7. Files tab on device A, tap **Delete** on a file and leave the question open. Switch A's wireless debugging off so the tab moves to device B. Answer **Delete**. **Pass:** nothing is deleted on B and nothing says Deleted. Repeat for **Rename** and **New folder**.
8. Files tab on A: **Send a file here**, choose three files, and make A drop out after the first. **Pass:** the rest are not sent to B and the toast says the push did not finish. Same for pulling an app's APK from the Apps menu.
9. Files tab: pull a folder that holds a file the shell cannot read (a folder with a file under `Android/data` on a phone that blocks it). **Pass:** you see adb's error or the skipped count, not "Saved to ...".
10. (1 device is enough) Send tab > **Choose package files**: pick an APK. **Pass:** it appears in the queue. Files tab > **Send a file here**: the picked file is sent. The folder you chose for Saved lists backups and for the Updater's downloads is unchanged.

## C. The rest (10 minutes)

11. **Backups:** Console tab, `pm uninstall -k --user 0 <package>` for a user app, then restore its backup. **Pass:** it installs, no "signed with a different key", and you are asked once.
12. **Real terminal:** open it in Termux or a working-mode shell and type `ls` and Enter at once. **Pass:** the output appears once the shell is up. With the keyboard open run `stty size`, then `vim`. **Pass:** the size matches the visible area and vim's status line is visible.
13. Open the real terminal (app shell), close it with X, then use the Termux setup's **Open the real terminal** button. **Pass:** it restarts in Termux and the dropdown and `echo $PREFIX` agree. Reopen it with the plain button: nothing changes.
14. **Morphe Patcher**, no working mode: patch an app with **Install when finished** and **Delete the APK after installing** on, then cancel the system installer. **Pass:** the result says the installer was opened (not "Installed") and the patched APK is still in Patched APKs.
15. Start a patch, press Back to leave the app, open it again. **Pass:** the Morphe tab shows the patch still going with its steps and Stop, and the result appears when it ends. Start a second patch while the first runs: **Pass:** it says the patcher is busy at once and the first one carries on.
16. **Updater:** Installer > Application Updater > **Check for Updates** on a phone with many apps. **Pass:** the "N of M" count and the bar only go forward and nothing returns to "checking" after the summary.

## D. The full code review and the pairing check (10 minutes)

17. **Pairing (hardening):** turn on Wireless debugging > Pair device with pairing code. In the app, pair with the notification's reply (type the code alone, then once more as `ip:port code`). **Pass:** both forms pair; a code with letters or a wrong-looking address is refused with a message and nothing is run.
18. **Wrong device (2 devices):** run `sleep 600` in the Connected Devices console on A and unplug A. **Pass:** the command on A is cancelled, Run shows again, and the next command goes to B. Play live Logcat on A, unplug A: **Pass:** it stops (Play) instead of showing B's log.
19. **Downloads:** start two Helper downloads with the same file name. **Pass:** both finish intact.
20. **Installer:** start installing a large XAPK, then open another APK. **Pass:** "An install is already running" and the first install finishes.
21. **Morphe:** with "Stop when a patch fails" off, patch with a patch that fails on that app. **Pass:** "Patching failed", no new file in Patched APKs, no install prompt.
22. **SD Maid:** scan AppCleaner with ADB or Shizuku, turn Wireless debugging off, delete an Android/data item. **Pass:** it stays listed with "could not be deleted" and History shows nothing deleted.
23. **Archives:** extract a `rar a -p` archive. **Pass:** the password prompt appears and the right password extracts.

## Report

Reply on the release with the step numbers that **passed** and the ones that **failed**, and the phone details above. A short "steps 1-6 pass on Pixel 8, Android 15, Shizuku" is enough.
