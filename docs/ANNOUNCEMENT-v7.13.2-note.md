# 7.13.2 fixes (a note for the 7.13.0 Discussion)

A short reply to post under the v7.13.0 announcement Discussion once v7.13.2 is on the Releases page. GitHub Discussions cannot be written from the tools that made this release, so the owner pastes it.

---

**7.13.2 is out.** https://github.com/Bingblop/ADB-Application-Manager/releases/tag/v7.13.2

Two things you asked for, and a round of fixes from a second review pass:

- **The SD Maid accessibility cleaner starts on its own.** With the consent given and a working mode (ADB, Wireless Debugging, Shizuku or Root), AppCleaner now switches its accessibility service on itself, waits for it to connect, and runs. It does nothing in Read-Only mode or without the consent.
- **This app and Android System WebView are never force-stopped by the cleaner.** They are stock exclusions, and a guard keeps them out even if the list is edited. Force-stopping this app used to end the cleaning halfway.
- **Fixes from the second review pass:** *Choose package files* and Files *Send a file here* deliver again (they clashed with the backup-folder picker); Files Delete, Rename and New folder act on the device you asked about; a multi-file push and the APK pull stop when the device changes; a folder pull with skipped files no longer says Saved; restoring an app you removed with *keep data* installs it; keys typed while the real terminal starts are no longer lost; *Install when finished* no longer deletes the patched APK when the system installer was only opened; a Morphe patch is still shown after you leave the app and come back; the update check's "N of M" only goes forward (#185). The full list is in the release text and in [CHANGELOG.md](https://github.com/Bingblop/ADB-Application-Manager/blob/main/CHANGELOG.md).

**It has not been run on a phone, and the SD Maid change is the one that needs one most.** Whether One UI binds the service the way the code expects, and how long it takes, can only be seen on a device. If you have twenty-five minutes, work through the **[one-page phone script](https://github.com/Bingblop/ADB-Application-Manager/blob/main/docs/PHONE-SCRIPT-v7.13.2.md)**; part A is the SD Maid steps and takes five.

- A step that **passes**: a reply here with the step number and your phone model is enough.
- A step that **fails**: open a [phone bug report](https://github.com/Bingblop/ADB-Application-Manager/issues/new?template=phone-bug-report.yml) with the working mode, the One UI version and the step.

Install the `arm64-v8a` file over 7.13.1 as an update; settings, saved lists and keys are kept, and the signing certificate is the same (`9461870f…`). Obtainium users get it on the next check.
