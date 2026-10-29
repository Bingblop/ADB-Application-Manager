# Phone script for v7.19.0

About 12 minutes, one pass or fail per step. v7.19.0 is the owner's list of eight fixes and enhancements (`CHANGELOG.md`, `## v7.19.0-Pro`). Steps 1 to 4 need Super User on the phone (Root as the working mode with su granted); the rest work in any working mode unless they say so.

**Before you start:** install the `arm64-v8a` file of v7.19.0 over v7.18.3.

## Working mode (Super User first, fallback to Automatic)

1. Open the working mode sheet. **Pass:** the Automatic card reads "Super User → Shizuku (UID 0 or 1000) → ADB TCP → Wireless Debugging → Shizuku (UID 2000)".
2. Choose **Automatic** with su granted to this app. **Pass:** the badge reads "Auto • Root" (or Super User), and Root Features unlock.
3. Choose **Super User**, then in the root manager revoke su for this app (or deny the next request) and come back to the app. **Pass:** within a few seconds the message *Super User is no longer available. The working mode is back on Automatic.* appears once, and the working mode shows Automatic using the next ready mode.
4. Grant su again and choose **Super User**. **Pass:** it stays on Super User.

## Kill Apps (2 minutes)

5. Put about 15 apps in the Kill Apps quick list, including **System UI** if the list editor allows it. Tap the **Kill Apps** tile, then turn the screen off for 10 seconds. **Pass:** the **Stopping apps** notification counts to the end ("15 of 15") and the final message appears; System UI (if listed) is stopped after all the other apps of the list.
6. Run it again and press Back right after the tap. **Pass:** the run still finishes.

## App ops and permissions (2 minutes)

7. Open a large app (Google Play Services, or Samsung's One UI Home) → **Permissions**. **Pass:** permissions are listed (not "No permissions declared").
8. Open an app that asks for **Display over other apps** (SYSTEM_ALERT_WINDOW) → Permissions. **Pass:** that row has a GRANTED / DENIED button without a padlock; tapping it flips it, and Settings → Apps → that app → Display over other apps shows the same.
9. Same app → **App Ops** → set **SYSTEM_ALERT_WINDOW** to **Ignore**, then **Allow**. **Pass:** each time the lit button follows; if Android keeps another mode, the message says *Android kept … at …*.

## Connected Devices (3 minutes, needs a second device with Wireless debugging)

10. On the other device, turn Wireless debugging on, pair it from **+ Add device** with the pairing code. **Pass:** after *Paired. Looking for its connect port...* it connects by itself.
11. On the other device, turn Wireless debugging off and on again (it gets a new port), then refresh Connected Devices. **Pass:** it shows under **Found on this Wi-Fi** with its IP and the new port; **Connect** connects.
12. Open **Saved devices**. **Pass:** that device is listed once, not once per port.

## Small ones (1 minute)

13. **System → DNS**: **Sort by speed** is on. Turn it off, close and reopen the app. **Pass:** it is still off. Turn it back on as you like.
14. **App Stores** and **App Updater**: **Pass:** each starts with a slim one-line *on mobile data* switch, with no description under it.
