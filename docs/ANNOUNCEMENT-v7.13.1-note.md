# 7.13.1 fixes (a note for the 7.13.0 Discussion)

A short reply to post under the v7.13.0 announcement Discussion once v7.13.1 is on the Releases page. GitHub Discussions cannot be written from the tools that made this release, so the owner pastes it.

---

**7.13.1 is out: fifty-four fixes, nothing new.** https://github.com/Bingblop/ADB-Application-Manager/releases/tag/v7.13.1

A whole-codebase review found 54 bugs and this release fixes all of them. The ones you would have noticed: the SD Maid Deduplicator could delete the copy marked *Kept*; a Connected Devices batch could land on the other device when one dropped; a refused `su` in Root mode read as success; closing the full-screen terminal could freeze the app; a rename onto a folder's name moved the file into it and overwrote; an extract with *Keep both* could delete an empty folder; the Morphe tab loaded seven times on every switch; a second Update All dropped the apps still waiting; "2.0-beta1" counted as the same version as "2.0". The full list, every fix named, is in the release text and in [CHANGELOG.md](https://github.com/Bingblop/ADB-Application-Manager/blob/main/CHANGELOG.md).

**It has not been run on a phone.** Every fix passes the UI and Java suites on a computer, and that is all. If you can spare twenty minutes on a device, please work through **[section 28 of the device checklist](https://github.com/Bingblop/ADB-Application-Manager/blob/main/docs/DEVICE-TEST-CHECKLIST.md#28-bug-fixes-from-the-code-review-of-2026-10-13-v7131)**: one step per fix, the eleven serious ones first, each saying exactly where to tap and what the screen must say. Any working mode helps (ADB, Wireless Debugging, Shizuku or Root), and a watch or second phone covers the Connected Devices step.

- A step that **passes**: a reply here with the step and your phone model is enough.
- A step that **fails**: open a [phone bug report](https://github.com/Bingblop/ADB-Application-Manager/issues/new?template=phone-bug-report.yml); it asks for the working mode, the One UI version and the checklist step, so the report arrives usable.

Install the `arm64-v8a` file over 7.13.0 as an update; settings, saved lists and keys are kept, and the signing certificate is the same (`9461870f…`). Obtainium users get it on the next check.

One thing found and left open on purpose: the update check's "N of M" count can step back by one for a frame (#185). Cosmetic; the fix is a design change and goes into the next release.
