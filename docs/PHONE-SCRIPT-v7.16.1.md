# Phone script for v7.16.1

About 15 minutes, one pass or fail per step. v7.16.1 changes one thing: the **Root Features** tab and the per-app **component Enable / Disable** now unlock when the **Shizuku** server runs as the Android **system user (UID 1000)** or as **root (UID 0)**, not only in Root mode with su granted. Shizuku started over ADB (the shell user, **UID 2000**) still keeps them locked. The root theme card (a fabricated colour overlay) still needs **UID 0** and stays locked under UID 1000. The Shizuku badge now names the server's real UID (`CHANGELOG.md`, `## v7.16.1-Pro`). Nothing in this script has been run on a phone by the author. Section A and B work on any phone, including one that cannot be rooted; section C needs a phone whose Shizuku can run as UID 1000 or root (a rooted phone). The longer checks are `docs/DEVICE-TEST-CHECKLIST.md` (the Root and Component items).

**Before you start:** install the `arm64-v8a` file of v7.16.1 over v7.16.0 (an update, not a fresh install).

## A. Start (2 minutes)

1. Open the app. **Pass:** About says 7.16.1; your settings, saved lists, keys and pinned tabs are still there.

## B. Ordinary ADB Shizuku, UID 2000 — the lock is unchanged (6 minutes; any phone)

2. Connect ADB TCP or Wireless Debugging in Working Modes and start Shizuku the usual way (over ADB). The working-mode badge on the header. **Pass:** the badge reads **Shizuku (UID 2000)** (or, if the UID cannot be read, the old **Shizuku ADB (UID 2000)**).
3. Open the **Root Features** tab (before About) in this mode. **Pass:** the Super User for apps and App components rows are locked with *Needs Super User (Root)*, as before — UID 2000 does not unlock them.
4. Apps > any app's menu > Components tab. Tap **DISABLE** on one part. **Pass:** the button carries a padlock (*Needs Super User (Root)*) and a tap offers to switch to Root or opens Root Features; nothing is sent to Android.
5. RRO/Monet Customization tab, the **Root-only theme controls** card. **Pass:** the rows are locked with *Needs Super User*, as before.

## C. Shizuku as the system user (UID 1000) or root (UID 0) — the new unlock (7 minutes; a rooted phone)

> This is the part v7.16.1 adds. It needs a Shizuku server running as UID 1000 or UID 0 — that is, started with root rather than over ADB (on a rooted phone, start Shizuku from a root shell, or use a root-started Shizuku/Sui). An unrootable phone cannot reach this state; section B is the whole of what it can show.

6. Start Shizuku as root (or as the system user) and choose the Shizuku working mode. The header badge. **Pass:** the badge names the real UID — **Shizuku (UID 0)** for root, **Shizuku (UID 1000)** for the system user.
7. Open the **Root Features** tab. **Pass:** it is unlocked — the chip is not DENIED/NOT FOUND for this reason, and the Super User for apps and App components rows are not locked with *Needs Super User (Root)*.
8. App components: open a user app's menu > Components, tap **DISABLE** on one part, then **ENABLE**. **Pass:** each is read back and takes effect (the part switches off, then on). This is Android allowing the system user (UID 1000) and root (UID 0) to change one component, which it refuses to the ADB shell (UID 2000).
9. **At UID 1000 only:** the **Root-only theme controls** card on the RRO/Monet Customization tab. **Pass:** it stays locked with *Needs Super User* — writing a fabricated overlay needs UID 0, which the system user is not. **At UID 0:** the same card is usable (its rows can be set and **Apply theme** writes them), as in v7.16.0.

## D. Help Guide (1 minute)

10. About > Help Guide, open the **Root Features** chapter and the glossary **UID** entry. **Pass:** the guide says it was written for version 7.16.1; the Root Features chapter says the tab unlocks under Shizuku as UID 1000 or root (UID 0), and the UID entry describes UID 0 (root), UID 1000 (the system user, not root) and UID 2000 (the shell).

Report any step that fails with the step number, the working mode, the Shizuku UID shown on the badge, the Android version, and a screenshot if it is about how something looks.
