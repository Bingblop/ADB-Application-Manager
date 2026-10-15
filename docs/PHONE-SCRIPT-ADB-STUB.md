# Phone script: the ADB Stub for Wear OS watches

One page, about 17 minutes, one pass or fail per step. The ADB Stub is a small app the phone installs on a Wear OS watch from Connected Devices: while it
runs, the watch keeps its screen on and its Wi-Fi connected, and its one screen shows the state of the connection. Disconnect on the phone stops it. Nothing
in it has been run on a phone or a watch by the author.

**Before you start:** a build with the ADB Stub on the phone, a Wear OS watch on the same Wi-Fi with Developer options, **ADB debugging** and **Debug over
Wi-Fi** on, and the watch connected in **Connected Devices** over Wi-Fi (Add device). Put the watch on its charger if you can: the screen stays on.

## A. Install and start (3 minutes)

1. Open **Connected Devices** with the watch chosen. **Pass:** under the device card (badge **Wear OS**) there is a card **ADB Stub** with the line "Keeps the
   watch screen on and its Wi-Fi connected while this phone is connected." and an **Install** button. A phone or tablet has no such card.
2. Tap **Install**. **Pass:** the card says Installing..., then Starting..., the toast says "ADB Stub started on the watch", and the card shows **Running**
   with **Stop**. On the watch, the ADB Stub screen opens: **Connected** in green, the Wi-Fi address, "Screen: kept on", "Since" with the time.
3. Leave the watch alone for two minutes without touching it. **Pass:** the watch screen stays on (it may dim on some watches; it must not go to the watch face
   or turn off), and **Apps** on the phone still lists the watch's apps (the connection did not drop).
4. On the watch, swipe the notification shade down. **Pass:** an "ADB Stub" notification with **Stop** is there (skip if the watch hides it).

## B. Disconnect gives the watch back (3 minutes)

5. On the phone, open the device menu (three dots) and tap **Disconnect**. **Pass:** the ADB Stub screen on the watch closes within a few seconds, the
   notification goes away, and the watch screen turns off on its normal timeout.
6. On the watch, check Settings > Connectivity > Wi-Fi a minute later. **Pass:** Wi-Fi behaves as before the test (on many watches it turns itself off again
   while the phone is near over Bluetooth). Write down what you see.

## C. Starts on its own (4 minutes)

7. Connect the watch again (Saved devices, or Add device). **Pass:** without tapping anything the stub starts again (the watch shows its screen; the card
   shows **Running**).
8. Device menu: **Start ADB Stub on connect: On** is there. Tap it (it turns Off), Disconnect, connect again. **Pass:** the card shows **Installed, not
   running.** with **Start**, and the watch does not open the stub. Tap **Start**: it opens. Switch the menu item back On.
9. Tap **Stop** on the card. **Pass:** the stub closes on the watch and the card shows **Start**.

## D. Lets go by itself when the phone is gone (7 minutes)

10. Tap **Start** on the card, then close the app on the phone (swipe it away from the recent apps) without Disconnect. **Pass:** about 5 minutes later
    the ADB Stub screen on the watch closes by itself, the notification goes away and the watch screen turns off on its normal timeout. Write down how
    long it took.

Report any step that fails with the step number, the watch model and Wear OS version, and how it was connected (Wi-Fi or Bluetooth link).
