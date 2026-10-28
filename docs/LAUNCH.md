# Getting the app out there

The plan for distributing and announcing ADB Application Manager Pro, starting with v7.13.0, and the texts to paste. Status as of 2026-10-13. Everything here is a draft the owner posts; nothing in this file has been posted by the people and tools that wrote it.

## Where the app can be, and where it cannot

| Channel | Status | What it takes |
| --- | --- | --- |
| **GitHub Releases** | Live: v7.19.0 (v7.18.3, v7.18.2, v7.18.1, v7.18.0, v7.17.0, v7.16.2, v7.16.1, v7.16.0, v7.15.0, v7.14.1, v7.14.0, v7.13.4, v7.13.3, v7.13.2, v7.13.1 and v7.13.0 before it), three APKs and `SHA256SUMS.txt`, signed with the one release certificate. | Nothing more. Keep the 64-bit file first in the asset list (the in-app updater takes the first `.apk`). |
| **Obtainium** (badge in the README) | Ready with this branch: the badge adds a configuration that follows the Releases page and picks the `-arm64-v8a` file. | Merge this branch. The redirect page offers Obtainium to a visitor who does not have it. |
| **Obtainium directory** (apps.obtainium.imranr.dev) | **Not yet.** Their rules: the source repository must be at least 4 months old and have at least 35 stars. This repository was created on 2026-09-30 (eligible from 2027-01-30) and has 31 stars. | After 2027-01-30, open a pull request on `ImranR98/apps.obtainium.imranr.dev` adding `public/data/apps/complex/com.bloatware.bingblop.json` with the configuration below. |
| **APKMirror** | Open to any free app. The uploader checks the signature against earlier uploads, so the first upload sets the certificate. | Upload the `-universal` APK at https://www.apkmirror.com/apk-upload/ with the release notes. Later versions can be uploaded by anyone and are matched by certificate. |
| **GitHub Discussions** | Draft ready: `docs/ANNOUNCEMENT-v7.13.0.md`. | Paste it as an Announcement. |
| **Reddit, XDA, Mastodon/Bluesky** | Drafts below. | Post from the owner's accounts. Each community has its own self-promotion rule; read the sidebar first. |
| **IzzyOnDroid** | **Out.** Their inclusion policy rejects apps that integrate with LLM services (the Coding Agents and Ask-agent features) and apps whose code was written with AI tools, and asks for a FOSS licence. Submitting would be rejected and would cost the maintainers' time. | Nothing. Do not submit. |
| **F-Droid (main repository)** | **Out for now.** F-Droid builds from source on its own servers; the build uses prebuilt jars in `libs/` and a prebuilt engine in `engine/dist/`, and the repository is GPL-3.0 (see `LICENSE`). The same AI-integration objection applies in their community. | Would need a source-only build and a `fdroiddata` metadata file. Not planned. |
| **Google Play** | **Out.** The app's working modes (ADB over TCP, Shizuku, root, system-app uninstall) and its in-app updater are outside Play's policies. | Nothing. |

### Licence

The repository is **GPL-3.0**: `LICENSE` is the unmodified GNU GPL v3 text. The GPL-3.0 components built into the APK (morphe-patcher, the SD Maid SE port) require the whole app to be distributed under GPL-3.0, so the posts below say "open source, GPL-3.0". `NOTICE` names every third-party component and its licence.

### GitHub settings the owner has to set (not possible from a pull request)

- **Social preview**: Settings → General → Social preview → upload `docs/screenshots/social-preview.png` (1280×640). This is the image Reddit, Discord, Mastodon and X show for any link to the repository.
- **Website**: Settings → General → Website: `https://github.com/Bingblop/ADB-Application-Manager/releases/latest` until there is a landing page.
- **Discussions**: post `docs/ANNOUNCEMENT-v7.13.0.md` as an Announcement and pin it.
- The description and the 19 topics are already set and good.

## Obtainium

The badge in the README links to:

```
https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/<url-encoded configuration>
```

The configuration (the same object Obtainium exports and imports):

```json
{"id":"com.bloatware.bingblop","url":"https://github.com/Bingblop/ADB-Application-Manager","author":"Bingblop","name":"ADB Application Manager Pro","additionalSettings":"{\"apkFilterRegEx\":\"arm64-v8a\"}"}
```

For the directory pull request (after 2027-01-30), the file `public/data/apps/complex/com.bloatware.bingblop.json`:

```json
{
  "configs": [
    {"id":"com.bloatware.bingblop","url":"https://github.com/Bingblop/ADB-Application-Manager","author":"Bingblop","name":"ADB Application Manager Pro","additionalSettings":"{\"apkFilterRegEx\":\"arm64-v8a\"}"}
  ],
  "description": "Debloat, freeze, uninstall, update, install, back up and inspect every app on the phone itself, through ADB, Wireless Debugging, Shizuku or root. Hidden settings editor, Material You overlays, a real terminal, Private DNS, SD Maid tools, Morphe patching.",
  "category": "Tools"
}
```

## Landing page, bug reports, GIFs

- **Landing page**: `docs/index.html` is a GitHub Pages site (one file, no build; `docs/.nojekyll` beside it). Switch it on under **Settings → Pages → Build and deployment → Source: Deploy from a branch → Branch `main`, folder `/docs`**. It is then served at `https://bingblop.github.io/ADB-Application-Manager/`; put that address in the repository's Website field. The three download buttons read the latest release from the GitHub API when the page loads and fall back to the Releases page.
- **Phone bug reports**: `.github/ISSUE_TEMPLATE/phone-bug-report.yml` is a form that asks for the working mode, the app version, the phone with its Android and One UI version, and the checklist section and step before anything else. `config.yml` beside it points questions to Discussions and lists the checklist.
- **GIFs for the posts**: `docs/screenshots/v7.13.0/bottom-bar.gif` (tapping through the tabs, holding Installer for the shortcut menu, the More sheet) and `docs/screenshots/v7.13.0/dns-speed-test.gif` (Test speed filling the tiles, then Sort by speed). Both are recorded from the UI test harness with mock data; `tools/record-gifs.js` re-records them (run from the repository root with `LIB=tests/ui/lib FRAMES=<dir> node tools/record-gifs.js`, then assemble the `<name>.list` files with ImageMagick's `convert`). Reddit shows a GIF inline when it is the post's image; for a text post, link the raw file on `main`.

## Post drafts

Honesty line used in every post: the app is tested on a computer by its UI and Java suites on every change; on a phone, testing is thin, the checklist is in the repository, and reports are wanted. Do not claim more.

### Reddit (long form: r/droidappshowcase, r/oneui, r/samsung, r/degoogle; r/androidapps and r/Android only in the thread or with the flair their rules name)

**Title:** ADB Application Manager Pro 7.13.0: debloat, freeze, update and inspect every app from the phone itself (ADB, Wireless Debugging, Shizuku or root; no computer, no ads, no account)

**Body:**

I have been building an Android app that does what you would normally do from a computer with ADB, but on the phone: list every package, freeze or uninstall system apps (with the 5,000-entry UAD-NG debloat list and an Undo), install `.apk` / `.apks` / `.apkm` / `.xapk`, update apps from GitHub, F-Droid, IzzyOnDroid, APKMirror and others, edit Android's hidden Global/Secure/System settings with plain-English descriptions, switch Material You overlays, and run a real terminal (a pty with vim and htop, your own Termux, and optional AI coding agents that only run commands with your OK).

It works in four modes: ADB over TCP (`adb tcpip 5555` once), Wireless Debugging (Android 11+, pairing from inside the app), Shizuku, or root. Most of the app also works read-only with none of them.

**New in 7.13.0**

- An **Extra Settings** tab: Private DNS with fifteen services and a built-in speed test (real DNS-over-TLS lookups, sorted by speed), animation speed, adaptive-brightness bias, device telemetry, power and reboot tiles, Doze and the power governor, zram and memory.
- A **bottom navigation bar** with four pinned tabs, a More sheet and long-press shortcuts.
- A **Console** tab: ADB Shell with a history of cards, and Live Logcat; the Terminal is its own tab.
- The **Application Updater** check is rebuilt: every source at once, cancel, retry, a host that is down is named once.
- Install buttons on every ShizuStore row, a DUMP permission row with "Copy grant commands", an SD Maid fix for One UI's App info page.

**What it is not**

- No ads, no account, no analytics, no server of mine. The ADB key is generated on the phone.
- Not on Google Play (the working modes are outside its rules) and not on IzzyOnDroid or F-Droid (their policies exclude apps that integrate with AI services, which the optional coding agents do).
- Not heavily tested on phones. Every change runs a UI suite and Java suites on a computer; on a device, I have run a small part of the 7.13.0 checklist myself. The checklist is in the repository, and bug reports with the exact step are the most useful thing you can send.

**Get it:** the Releases page on GitHub (the `-arm64-v8a` file for any phone since 2016, `-universal` if unsure), or the Obtainium badge in the README. Signing certificate SHA-256 `9461870f…bd26d17` is listed in the README so you can check a copy.

https://github.com/Bingblop/ADB-Application-Manager

### XDA Forums (Android Apps and Games; one thread, updated per release)

**Title:** [APP][8.0+] ADB Application Manager Pro: ADB/Shizuku/root app manager, debloater, hidden settings, terminal, on the phone itself

**Post:** the Reddit body above, followed by:

**Requirements:** Android 8.0 or newer. One of: ADB over TCP, Wireless Debugging (Android 11+), Shizuku, or root. Without any of them the app opens in Read-Only mode and still shows everything.

**Downloads:** https://github.com/Bingblop/ADB-Application-Manager/releases/latest (three APKs: arm64-v8a, armeabi-v7a, universal; `SHA256SUMS.txt` next to them).

**Signing certificate:** SHA-256 `94:61:87:0F:7C:D2:40:64:06:A1:19:F4:D3:75:33:C2:3B:B7:D1:ED:85:05:06:BA:D7:EA:3E:6B:4B:D2:6D:17`.

**Changelog:** https://github.com/Bingblop/ADB-Application-Manager/blob/main/CHANGELOG.md

**Source:** https://github.com/Bingblop/ADB-Application-Manager

**Credits:** UAD-NG (debloat data), Exodus Privacy (tracker list), Shizuku, Morphe (the patching engine), SD Maid SE by darken (the cleaning tools), xterm.js. The full list is in NOTICE.

### Short posts (Mastodon, Bluesky, X; under 300 characters)

> ADB Application Manager Pro 7.13.0 is out: debloat, freeze, update and inspect every app from the phone itself, over ADB, Wireless Debugging, Shizuku or root. New: Extra Settings tab (Private DNS with a speed test, animations, power), bottom nav, Console tab. No ads, no account. https://github.com/Bingblop/ADB-Application-Manager/releases/latest

> New in ADB Application Manager Pro 7.13.0: a Private DNS card with fifteen services and a real DNS-over-TLS speed test, so you can pick the fastest resolver for your network without a VPN app. Android 9+, no root needed with ADB or Shizuku. https://github.com/Bingblop/ADB-Application-Manager

### Answers to the questions that will come

- **Is it open source?** Yes, GPL-3.0 (the bundled Morphe and SD Maid components require it). The text is in `LICENSE`.
- **Why not F-Droid or IzzyOnDroid?** Their policies exclude apps that integrate with AI services and apps written with AI tools. Both apply here (optional coding agents; much of the code was written with AI assistance, reviewed and tested by its UI and Java suites). The app does not phone home and has no trackers, but that is not the objection.
- **Is it safe to give an app ADB or root?** The app runs the commands it shows; every destructive action asks first and reads the phone back afterwards instead of trusting the command's exit status. The source is readable, the release workflow refuses to publish an APK that is not signed with the published certificate, and the certificate is in the README. Nobody should take that on trust; read the code or sandbox it.
- **Was it written by AI?** Largely, with a human owner directing, testing on a phone and deciding what ships. Say so when asked; it is in the commit history anyway.
- **Does it work on non-Samsung phones?** The ADB, Shizuku and root paths are plain Android. One UI is where it is used most and where the SD Maid accessibility fix was reasoned; reports from other brands are wanted.

## After the first posts

- Watch Issues and the Discussion for the first week and answer within a day; a first impression of an unanswered bug report is worse than no post.
- Each report that names a checklist step is a line in `docs/PHONE-CHECK-RESULTS-v7.13.0.md` (create it when the first one comes).
- When the repository passes 35 stars and 2027-01-30: the Obtainium directory pull request above.
