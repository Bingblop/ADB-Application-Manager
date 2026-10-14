# ADB Application Manager 7.13.0: Extra Settings tab, bottom navigation, Console tab

*Draft of the GitHub Discussion post for the v7.13.0 release. The images are the test harness's screenshots in `docs/screenshots/v7.13.0/` (a 360 px phone viewport, dark theme, mock data); they show the page as the UI tests see it, not a phone.*

---

**Release:** https://github.com/Bingblop/ADB-Application-Manager/releases/tag/v7.13.0 · **Changelog:** [v7.13.0-Pro (versionCode 854)](../CHANGELOG.md)

7.13.0 is a feature release on top of 7.12.11. It installs over any earlier version and works with ADB, Wireless Debugging, Shizuku or Root, as before. Here is what changed.

## A bottom navigation bar

The tab bar moves to the bottom. Four pinned slots (Application Manager, Installer, Extra Settings and Console by default) and **More**, which opens a sheet with every other tab. Holding a slot for half a second opens a menu of up to four shortcuts for that tab; Settings > Navigation chooses the shortcuts per slot, and whether you want the bottom bar, the old top bar, or both. On a wide screen the bar becomes a rail on the left.

<p>
<img src="https://raw.githubusercontent.com/Bingblop/ADB-Application-Manager/main/docs/screenshots/v7.13.0/01-apps-bottom-bar.png" width="210" alt="Application Manager with the bottom bar">
<img src="https://raw.githubusercontent.com/Bingblop/ADB-Application-Manager/main/docs/screenshots/v7.13.0/02-more-sheet.png" width="210" alt="The More sheet">
<img src="https://raw.githubusercontent.com/Bingblop/ADB-Application-Manager/main/docs/screenshots/v7.13.0/08-shortcut-menu.png" width="210" alt="Long-press shortcuts on the Installer slot">
</p>

## An Extra Settings tab

A new tab, right after Hidden Settings, for the phone settings that are not about apps:

- **Private DNS** with fifteen services as tiles (Google, Cloudflare, Quad9, AdGuard, OpenDNS, CleanBrowsing, NextDNS, DNS.SB and their filtered variants), plus Automatic, Off and your own host name. **Test speed** measures each service with a real DNS-over-TLS lookup and marks the fastest; **Sort by speed** reorders the tiles. The card moved here from the gear Settings page, where a tester could not find it.
- **Animation speed** sets the three Developer-options scales together, from Off to 10×, with a preview before Apply.
- **Brightness bias** shifts adaptive brightness darker or brighter without switching it off.
- **Device telemetry** shows RAM, storage and battery rings and spec chips, refreshed while the tab is on screen, with a Copy report button. It only reads.
- **Power & reboot**, **Doze & power governor** and **Zram & memory**: reboot tiles that name their command and ask first (the dangerous ones need a press-and-hold), a Doze timeline with Force / Step / Wake, Performance / Balanced / Ultra saver profiles, and zram stats with root-only cache drops.

Every section that changes the phone reads it back before it says anything, and is locked with a reason in Read-Only mode.

<p>
<img src="https://raw.githubusercontent.com/Bingblop/ADB-Application-Manager/main/docs/screenshots/v7.13.0/03-extra-settings-dns.png" width="210" alt="Private DNS tiles">
<img src="https://raw.githubusercontent.com/Bingblop/ADB-Application-Manager/main/docs/screenshots/v7.13.0/04-extra-settings-animation.png" width="210" alt="Animation speed">
<img src="https://raw.githubusercontent.com/Bingblop/ADB-Application-Manager/main/docs/screenshots/v7.13.0/05-extra-settings-telemetry.png" width="210" alt="Device telemetry">
</p>

## A Console tab

The Command-Line Interface tab is split in two. **Console** has **ADB Shell** (a command box, Run, and the history as newest-first cards with Copy and Run again) and **Live Logcat** (the former Logcat Viewer, read only while on screen). **Terminal** keeps the full-screen pty terminal, the three shells, Termux sync and the Coding Agents. Saved tab choices are migrated.

## Connected Devices: Freeze and Frozen

The Connected Devices page now uses the same words as the Application Manager: the badge reads **FROZEN** and the row button, batch bar and app menu read **Freeze** (in blue). The command is unchanged.

<p>
<img src="https://raw.githubusercontent.com/Bingblop/ADB-Application-Manager/main/docs/screenshots/v7.13.0/06-console.png" width="210" alt="Console tab, ADB Shell">
<img src="https://raw.githubusercontent.com/Bingblop/ADB-Application-Manager/main/docs/screenshots/v7.13.0/07-connected-devices-frozen.png" width="210" alt="Connected Devices with the Frozen badge">
</p>

## Also in this release

- **Application Updater:** the check is rebuilt. Every source is queried at once, the page streams rows as they are found, there is Cancel and Retry failed, a host that is down is named once instead of failing every app, the result is saved across a restart, and it never says "up to date" when apps could not be checked.
- **App Stores:** every ShizuStore row has an Install button, like the other stores.
- **Permission sheet:** a DUMP row, and **Copy grant commands** copies the `adb shell pm grant` lines for what is still missing.
- **SD Maid:** the accessibility cache clearer no longer stalls on One UI's App info page, and a **Copy screen report** button collects diagnostics when it does.
- **Morphe Patcher:** sources are labelled Official and Custom.
- Install follow-ups: Update and Update All work again after Read-Only mode, and a new Update All question never reuses an older yes.

## What was and was not tested on a phone

Everything above is covered by the UI and Java test suites on a computer. On a phone, only three checks have been run so far: the owner applied AdGuard DNS, Off and Automatic in Private DNS on 7.12.11 and each read back correctly. The speed test, the bottom bar, the Console tab and the rest of Extra Settings have not been run on a device by the people and tools that wrote them. If you try them, sections 21 and 23 to 27 of [DEVICE-TEST-CHECKLIST.md](DEVICE-TEST-CHECKLIST.md) are the steps, and a report in this thread, good or bad, is welcome.

## Install

Download the arm64 APK from the [release page](https://github.com/Bingblop/ADB-Application-Manager/releases/tag/v7.13.0) and install it over your current version. The signer is unchanged (SHA-256 `9461870f7cd2406406a119f4d37533c23bb7d1ed850506bad7ea3e6b4bd26d17`), so it updates in place; the README says how to read the certificate from an APK.
