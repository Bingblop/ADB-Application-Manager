# v7.12.9 quick phone check (about 10 minutes)

The short version of [DEVICE-TEST-SCRIPT.md](DEVICE-TEST-SCRIPT.md) and section 19 of [DEVICE-TEST-CHECKLIST.md](DEVICE-TEST-CHECKLIST.md): the items most likely to break on a real phone, in the order that finds problems fastest. Each step says what to tap, what you should see, and an `adb` command that reads the result from outside the app.

**Nothing here has been run on a phone by the people and tools that wrote it.** A different result is worth reporting, not necessarily a bug: write down the phone model, the Android version, the working mode (standard, adb, Shizuku, root) and what you saw.

Set up once, on a computer with `adb` connected to the phone (a POSIX shell: Linux, macOS, Termux, WSL or Git Bash):

```sh
export PKG=com.bloatware.bingblop
adb devices
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
```

## 1. Install over the old version (an update, not a fresh install)

```sh
adb install -r ADB_Application_Manager_Pro-v7.12.9-arm64-v8a.apk
adb shell dumpsys package $PKG | grep -E "versionName|versionCode" | head -2
```

- Expect `versionName=7.12.9-Pro` and `versionCode=851`. "Signatures do not match" means the file is signed with a different key: stop, do not uninstall (you would lose the app's data), and report it.
- Open the app. **About** says 7.12.9; your settings, saved lists and theme are still there.

## 2. Private adb socket

```sh
adb shell cat /proc/net/unix | grep -i bingblop
adb shell cat /proc/net/tcp | grep -i ":13B2"
```

- The first command should show the app's socket; the second (port 5042) should print nothing. A line in the second means the phone refused the private socket and the app fell back to the old loopback port: adb still works, but report the phone.

## 3. Keys survive a restart (Keystore vault)

1. **Settings**, set a VirusTotal key (any text), save.
2. Swipe the app away from the recents list, open it again.
3. Expect the key to still be accepted ("saved"), and no "could not be stored securely" message.

## 4. File Manager keeps working, and refuses the app's own private files

1. Open the **File Manager**, go to **Download**, open a text file and a picture: both open.
2. Long-press a file, **Share**: the share sheet opens. **Open with** works too.
3. Go to `/data/data/com.bloatware.bingblop/shared_prefs` (use the path field of the File Manager if your build has one; otherwise walk there from the storage list). Tap any file. If the app cannot list that folder on your phone, skip this step and write SKIP.
4. Expect a refusal ("part of this app's own private data"), not the file's text. Try the same for a file under `files/` (for example the adb key folder): refused.
5. A patched APK (Morphe Patcher tab, Patched APKs) can still be shared, and the Helper's downloads still open.

If the folder listing itself shows names, that is expected (names only). The check is that **no content** is shown and **nothing can be written, copied or deleted** there.

## 5. Full-screen terminal: hang-up and paste

1. Open the full-screen terminal. Run `sh -c "trap '' HUP; exec sleep 600"`.
2. Close the terminal.
3. On the computer, within a second:

```sh
adb shell "ps -A | grep '[s]leep 600'"
```

Expect nothing (the program ignoring SIGHUP is killed 0.5 s after the hang-up). If the line is still there after 2 seconds, report it and then clean up with `adb shell pkill -f "[s]leep 600"`.

4. Reopen the terminal, run `yes`, paste about 100 KB of text. The terminal must not freeze; Ctrl-C stops `yes`.
5. Press **Restart** three times quickly, type `echo ok` each time: the answer appears once and the terminal accepts input.

## 6. Connected Devices: adb options that move the server are refused

In **Connected Devices**, with one device connected, run in its console:

- `adb devices`: works.
- `adb -P 5037 devices` and `adb kill-server`: refused with a message about another server.

## 7. Installs, in your working mode

- **Standard mode:** install a downloaded APK from the Updater. The system installer asks its own question.
- **adb, Shizuku or root mode:** the install of a downloaded app is **not** confirmed by the app in this release (the native confirmation, #109, is not in v7.12.9). Do not install anything you do not trust through the store tabs.

## 8. Archives still extract

Extract a small `.zip` and a small `.tar.gz` from the File Manager to Download: the files appear with the right sizes, and a `.part` file is not left behind.

## Report

For each step write PASS, FAIL or SKIP, and keep the output of any failing command. Put anything unexpected into `docs/COPILOT_CLAUDE_HANDOFF.md` as a new row, or open an issue.
