# Phone test script for v7.12.9 (adb commands)

This is section 19 of [DEVICE-TEST-CHECKLIST.md](DEVICE-TEST-CHECKLIST.md) turned into steps you can paste from a computer.
The checklist says what to look for on the phone; this script adds the `adb` commands that read the result from outside the app, so
you are not trusting the app's own words about itself.

**Nothing in this script has been run on a phone yet.** If a command prints something different from what a step says, that
is a result worth reporting, not necessarily a failed app. Android versions and makers differ, so write down what you saw.

## 0. Before you start

Connect the phone to a computer with `adb` (USB, or Wireless Debugging) and run once:

```sh
adb devices                                         # the phone is listed as "device", not "unauthorized"
export PKG=com.bloatware.bingblop                   # this app
export APP=com.example.replace.me.app1             # REPLACE with the package of a normal app you can freeze (find one: adb shell pm list packages -3)
export APP2=com.example.replace.me.app2            # REPLACE: a second app you do not mind (only for the batch test, step 9)
export TESTPKG=com.example.testapp                  # REPLACE: package of the test APK with a different signer (only for step 5)
export TESTAPK=./different-signer.apk               # REPLACE: path of that test APK on the computer (only for step 5)
adb shell getprop ro.product.model                  # write these three down for the report
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
```

The commands are for a POSIX shell (Linux, macOS, Termux, or WSL / Git Bash on Windows): they use `grep`, `head`, `tail`, `sha256sum` and `$PKG` / `$APP`. They are **not** PowerShell commands; on Windows run them in WSL or Git Bash.

The `com.example.replace.me.*` values are deliberately not real packages: a command run without replacing them fails harmlessly instead of touching a real app. Pick `APP` and `APP2` carefully: apps you do not mind freezing, standby-bucketing and dex-optimizing (not a system app, your launcher, keyboard or phone app).

Check that both exist before you go on: `adb shell pm path $APP` and `adb shell pm path $APP2` must each print a `package:` line.

Mark each step PASS, FAIL or SKIP and keep the output of the commands for a failure.

---

## 1. Private adb socket (checklist: Private adb socket)

1. On the phone, start the app and connect in ADB mode as usual (Wireless Debugging or ADB over TCP). Open the Apps tab. **The list must load.**
2. From the computer, look for the old loopback port 5042 on the phone:

   ```sh
   adb shell "ss -ltn | grep -E ':5042([[:space:]]|$)'"
   ```

   If the phone has no `ss` (the error `ss: inaccessible or not found` or `not found` appears), use the kernel table directly (5042 is `13B2` in hex;
   the pattern requires exactly that local port and state `0A`, listening):

   ```sh
   adb shell "cat /proc/net/tcp /proc/net/tcp6 | grep -iE '^ *[0-9]+: [0-9A-F]+:13B2 [0-9A-F]+:[0-9A-F]+ 0A '"
   ```

   Errors are left visible on purpose. A probe that printed an error (command not found, permission denied) proved nothing: it is **not** a pass. Only
   a probe that ran without an error and printed nothing counts as "nothing listening".

3. Read the result:
   - **Nothing printed and no error message from the probe** = the private socket is in use. (Do not rely on `echo $?`: for a pipeline it is the status of the last `grep`, which is 1 both for a clean miss and for a probe that failed before it. The visible error text is the signal.) This is the intended result: **PASS**.
   - **A line with `LISTEN` / state `0A`** = this phone refused the private socket and fell back to loopback port 5042. adb works, but
     the old exposure is unchanged on this phone. Record it as its own result (model, Android version), **not** a pass.
   - Nothing printed *and* the app list did not load = the check says nothing; fix the connection first.

## 2. Keystore vault, upgrade (checklist: Keystore vault, upgrade)

Needs the previous release (v7.12.8) installed with a GitHub token and a VirusTotal key already saved.

```sh
adb shell dumpsys package $PKG | grep -E "versionName|versionCode"      # note the old version
adb install -r ADB_Application_Manager_Pro.apk                          # install this build over it (same signing key)
adb shell dumpsys package $PKG | grep -E "versionName|versionCode"      # must now read 7.12.9
```

If `adb install -r` reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE` the APK is signed with a different key than the installed one; do **not**
uninstall (that deletes the saved keys and makes this test meaningless). Install from the real release instead.

On the phone: Settings. The **GitHub Token** button must still be ticked and the VirusTotal key card must still read "approved" (or ask you to
test it once). Nothing may ask you to type either again.

That only shows the old values are still *readable*; the app also keeps returning an old plain value when the vault refuses to take it. To check that
they were really **moved** into the vault, look for the old plain entries now, before step 3 overwrites them (debuggable build or a rooted phone, see
step 3 for the commands): none of `github_token`, `kv_vt_key`, `kv_vt_key_ok`, `kv_vt_api_key` may still hold a value in `adb_app_manager_prefs.xml`.
**PASS** if nothing asks for the keys again and (when the check can run) the old entries are gone; with the check skipped, write PASS (readable) and
SKIP (moved).

## 3. Keystore vault, new value (checklist: Keystore vault, new value)

1. On the phone: enter a new GitHub token, then a new VirusTotal key and tap **Test key**. Both must work. **Leave both saved for now.**
2. While they are still saved, check that the old plain place does not hold them. The app used to keep them in the plain preferences file
   `adb_app_manager_prefs.xml` under these names: `github_token`, `kv_vt_key`, `kv_vt_key_ok`, `kv_vt_api_key` (this needs a **debuggable build**, where
   `run-as` works, or a **rooted phone**; on a normal release build on an unrooted phone `run-as` is refused and the cross-check is skipped):

   ```sh
   # debuggable build:
   adb shell run-as $PKG cat shared_prefs/adb_app_manager_prefs.xml | grep -oE '<[a-z]+ name="(github_token|kv_vt_key|kv_vt_key_ok|kv_vt_api_key)"( */)?>'
   # rooted phone, release build (the folder may be /data/user/0/$PKG on some phones):
   adb shell "su -c 'cat /data/data/$PKG/shared_prefs/adb_app_manager_prefs.xml'" | grep -oE '<[a-z]+ name="(github_token|kv_vt_key|kv_vt_key_ok|kv_vt_api_key)"( */)?>'
   ```

   The command prints only the opening tag of a matching entry, never its value, so a failure is safe to paste into a report. **PASS** if it prints
   nothing (the file exists and none of the four names is in it). **Any** line printed is a **FAIL**: the old plain entry was not removed after the
   move into the vault (Android may write an empty string as `<string name="x"></string>`, so an empty entry cannot be told from a secret without printing it). (The vault's own non-secret hint for the GitHub token is stored under a different name and does not match.) If `run-as` is refused, `su` is not
   available or the file is missing, write SKIP; the on-phone result still counts.
3. Now clear both: the GitHub button loses its tick and the key card says no key. Run the command from step 2 again: still nothing.

## 4. Download safety (checklist: Download safety)

The SHA-256 card shows the hash of every completed download. By default the Updater saves into the app's private storage, which `adb` cannot read, so
the hash can be shown but not cross-checked from the computer. For this step, **before** you download, choose the shared destination so that it can be:
in the Application Updater box, set the save location to **Downloads/App Updater**.

On the phone: in the Updater, download any app from APKMirror or F-Droid. The download must complete and the SHA-256 card must show.
Then download a Morphe bundle: it must complete.

Cross-check the hash the card shows against the file in the shared folder:

```sh
adb shell 'ls -lt "/sdcard/Download/App Updater/"'            # newest first: the file you just downloaded is at the top
adb shell 'sha256sum "/sdcard/Download/App Updater/FILENAME-FROM-THE-LISTING"'       # must equal the SHA-256 card
```

If the folder does not exist, the save location was not switched: write SKIP for the cross-check (the on-phone result still counts).

## 5. Install checks (checklist: Install checks)

1. Install a downloaded app over the installed one **from the same source**: it must install.
2. Try a test APK signed with a **different key** than the installed copy of the same package. The app must warn that the package is installed with a
   different signing key and that Android refuses the update. It then offers to uninstall the installed copy (and its data) first: **cancel that
   offer**. Accepting it tests a replacement, not the guard. After cancelling, the installed copy must be unchanged (same version, still installed:
   `adb shell pm path $TESTPKG` still prints its path, and its data is still there).

To check that the two copies really have different signing certificates (so the refusal is correct, not just the app being strict), compare the
`apksigner` digest of both. `dumpsys package` shows only an internal signature hash, not the SHA-256 certificate digest, so do not use it for this:

```sh
adb shell pm path $TESTPKG                                  # prints package:/data/app/.../base.apk
adb pull /data/app/.../base.apk installed-copy.apk                # use the path printed above
apksigner verify --print-certs installed-copy.apk | grep "certificate SHA-256"
apksigner verify --print-certs "$TESTAPK"      | grep "certificate SHA-256"
```

The two SHA-256 certificate digests must differ for step 2 to be a valid test. **PASS** if step 1 installs, and in step 2 the signer warning appears and, after you cancel the uninstall offer, the installed copy is unchanged.

## 6. VirusTotal upload (checklist: VirusTotal upload)

Step 3 cleared the VirusTotal key. Before this step, enter a key again in Settings and tap **Test key** until the card reads approved.

On the phone: scan a small APK with the VirusTotal card. The hash lookup must show. If the file is unknown to VirusTotal and an upload is offered,
accept: it must upload and a result must come back.

Command-line help: take the hash you expect to be looked up and compare with the card.

```sh
adb shell 'sha256sum "/sdcard/Download/small-test.apk"'      # replace small-test.apk with the file you scanned
```

## 7. Dex optimization: space (checklist: Dex optimization, space)

On the phone: app menu of `$APP` > **Dex optimization** > mode **space** > **Apply**. The progress window closes and a toast reads **Optimized** (a failure reads **Failed**, with the phone's own message in the log).

Before and after, read the dexopt state from the phone:

```sh
adb shell cmd package dump $APP | grep -iA6 "dexopt state"
```

Note the `status=` values (`verify`, `speed`, `speed-profile`, `space`, `run-from-apk` ...) before and after. If nothing changed, copy both outputs into the report (some Android versions refuse the Force option or ignore a mode).

## 8. Dex optimization: reset (checklist: Dex optimization, reset)

On the phone: choose mode **reset**. The Force switch must be hidden, the note must say the result depends on the Android version, and the subtitle must
read "Reset the dex optimization of ...". Apply: the toast reads **Optimized**.

Afterwards:

```sh
adb shell cmd package dump $APP | grep -iA6 "dexopt state"
adb shell dumpsys package dexopt | grep -i "$APP" | head
```

Record the Android version (step 0) and what the two commands print. After a reset the status is normally back to the install-time value
(`verify` or `run-from-apk`) or an empty profile; a different outcome is not automatically a failure, but report it.

## 9. Dex optimization: batch (checklist: Dex optimization, batch)

On the phone: select two apps (use `$APP` and `$APP2`), batch menu > **Dex optimization** > **speed-profile**. The progress bar
must move and **Stop** must work.

```sh
adb shell cmd package dump $APP2 | grep -iA6 "dexopt state"       # status should now read speed-profile after a finished run
```

That is the finished run. To test **Stop**, first put the second app back to a state that is *different* from speed-profile, for example
`adb shell cmd package compile --reset $APP2` (or `-m verify -f $APP2`), and note its status. Then start the batch again with the second app
last in the list and tap **Stop** while the first app is being done. The second app's status must still be the baseline you noted (not speed-profile).
If you cannot be fast enough to tap Stop before the second app, write SKIP rather than PASS.

## 10. Standby bucket: read (checklist: Standby bucket, read)

On the phone: app menu of `$APP` > **Standby**. The sheet opens with the bucket the phone reports. Compare:

```sh
adb shell am get-standby-bucket $APP
```

`am` prints a number or a name (`10` = active, `20` = working set, `30` = frequent, `40` = rare, `45` = restricted; names on newer versions). The
sheet and the command must agree. On a phone with no working mode the sheet must not open and must say why.

## 11. Standby bucket: set (checklist: Standby bucket, set)

On the phone, for `$APP`, apply the three values **one after the other**, checking from the computer after each Apply (repeating the read command alone
changes nothing). Each Apply must toast "Standby bucket changed".

1. In the Standby sheet pick **rare**, Apply, then:

   ```sh
   adb shell am get-standby-bucket $APP        # -> rare (40)
   ```

2. Open the sheet again, pick **restricted**, Apply, then:

   ```sh
   adb shell am get-standby-bucket $APP        # -> restricted (45)
   ```

3. Open the sheet again, pick **active**, Apply, then:

   ```sh
   adb shell am get-standby-bucket $APP        # -> active (10)
   ```

Then open the sheet on the current launcher (find it with `adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`).
The bucket must be greyed out there and a set must be refused with the phone's words.

Put the app back the way it was afterwards: `adb shell am set-standby-bucket $APP working_set` if the first read was working_set (use the value from step 10).

## 12. Task Manager (checklist: Task Manager)

1. On the phone, open the Task Manager tab and wait ten seconds.
2. From the computer, note the process while it runs:

   ```sh
   adb shell pidof $PKG
   ```

3. Close the app from Recents (swipe it away). **Immediately after the swipe** and before anything else touches the app, optionally reset the battery
   statistics, so that everything recorded afterwards happened after closing (they add up since the last charge, so without a reset the ten seconds of
   Task Manager activity from step 1 stay in the numbers). This clears the phone's battery history; it builds up again as you use the phone. Skip the
   reset if you do not want that, and write SKIP for the last item below.

   ```sh
   adb shell dumpsys batterystats --reset
   ```

   Now wait 30 seconds and check the process:

   ```sh
   adb shell pidof $PKG                                         # prints nothing, or a cached process that does no work
   adb shell dumpsys activity services $PKG | head -20          # no running service from this app
   adb shell "top -b -n 1 | grep -i bloatware"                  # no line with real CPU use (the whole list is searched, not only the busiest tasks)
   ```

   **PASS** if no activity of the app is running (a cached/empty process with 0% CPU is fine).

4. Only if you reset in step 3: leave the app closed and the phone idle for 10 minutes (do not open the app), then dump:

   ```sh
   adb shell dumpsys batterystats $PKG > after-close.txt
   ```

   Read the whole file for this app's entry (do not cut it with `head`). Because the statistics started after the app was closed, any wakelock, job or
   alarm listed for it happened after closing. **PASS** if there are none; a few seconds of "cached" process time with no wakelocks, jobs or alarms is fine.

## 13. Command output in the log (checklist: Command output in the log)

Release build only (a debuggable build logs the whole line by design).

1. Clear the log, then run a privileged action in the app (for example **Freeze** `$APP`):

   ```sh
   adb logcat -c
   # (do the Freeze on the phone)
   adb logcat -d -s ADBAppManager
   ```

2. The tag lines must show the **mode and sizes** of a command, not its text or output. Prove the text is absent:

   ```sh
   adb logcat -d -s ADBAppManager | grep -c "pm disable-user"      # must print 0
   adb logcat -d -s ADBAppManager | grep -c "$APP"                 # must print 0 (the package name is part of the command text)
   ```

   **PASS** if both print `0` while the first command shows at least one line from the freeze (otherwise the app logged nothing and the test proved nothing).

3. Un-freeze `$APP` again in the app (or `adb shell pm enable $APP`).

---

## Report

Send the model, Android version, working mode, and the step number plus the printed output of anything that failed, as an issue at
https://github.com/Bingblop/ADB-Application-Manager/issues. A result of "this phone fell back to port 5042" (step 1) is useful on its own.
