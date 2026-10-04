# Translating "ADB Application Manager Pro" into {LANG}

You are translating the interface text of an Android app for power users: it freezes, disables, removes (debloats), installs and inspects apps through ADB, Wireless Debugging, Shizuku or Root, edits Android's hidden settings, manages files and shows device logs. The people who read it are comfortable with technical words, but the text must still read like a polished app in {LANG}, not like a machine translation.

## What you get and what you hand back

* `{SRC}`: a JSON array of strings to translate. Each entry: `{ "id": 17, "en": "Freeze {0} apps?", "ctx": "where it appears" }`.
* Write `{OUT}`: one JSON object, `{ "17": "<your translation>", ... }`, with **every id** of the file, and nothing else (valid JSON, UTF-8, no comments).
* Then run `node {CHECK} {SRC} {OUT} {CODE}`. It lists every problem (a missing id, a lost `{0}`, a changed tag, an empty text ...). Fix them all and run it again until it prints `OK`. Do not hand back a file that does not pass.

## Rules

1. **Placeholders.** `{0}`, `{1}` ... stand for text or numbers the app inserts (an app name, a count, a path). Keep every one of them, exactly as written, once each. You may move them to where {LANG} wants them ("{1} de {0}").
2. **Tags.** A sentence like `Tap <1>Install</1> to continue` has numbered tags for bold text or a link. Keep every tag with its number, translate the words between them, and you may reorder the tagged parts if {LANG} needs it. `<2/>` stands for an icon: keep it where it belongs. `<br>` is a line break: keep it.
3. **Keep as they are** (do not translate, do not transliterate): ADB, APK, APKS, APKM, XAPK, OBB, Shizuku, Root, Rish, Magisk, SDK, UID, PID, SHA-256, VirusTotal, UAD-NG, F-Droid, GitHub, Codeberg, GitLab, Obtainium, IzzyOnDroid, Orion, ShizuStore, Aurora Store, Play Store, Galaxy Store, Droid-ify, Neo Store, Accrescent, Aptoide, APKPure, Uptodown, itch.io, Samsung, Pixel, One UI, Material You, Monet, RRO, Wi-Fi, Bluetooth, package names (`com.example.app`), setting names (`adb_enabled`), shell commands, file paths and extensions, URLs, hex colors, keyboard keys, and units (KB, MB, GB, ms, s, px). Names of Android's settings tables (Global, Secure, System) stay in English as well: they are the names of real database tables.
4. **Android's own words.** Where Android itself has a word for it, use the word the Android system / Samsung One UI uses in {LANG}: Install, Uninstall, Update, Enable, Disable, Force stop, Clear data, Clear cache, Permissions, Allow, Deny, Notifications, Storage, Battery, Network, Settings, Developer options, USB debugging, Wireless debugging, Pairing code, Backup, Restore, Share, Copy, Paste, Delete, Rename, Cancel, OK, Save, Search, Sort, Filter, Refresh, Download, Open, Close, Back, Next, Done, Select all, Clear, Reset, Undo.
5. **App words.** *Freeze* = turn an app off without uninstalling it (it can be turned on again): use the word apps like Island, Brevent or Greenify use in {LANG} for that, and use it everywhere. *Debloat* = remove pre-installed apps nobody needs. *Quick list* = a saved group of apps one tap acts on. *Working mode* = how the app reaches the system (ADB, Wireless Debugging, Shizuku, Root). *Overlay* / *RRO* = a system theme layer. Be consistent: one word for one thing, across the whole file.
6. **Length.** The screen is 360 px wide and buttons are small. Keep each text about as short as the English; shorten where {LANG} idiom allows. Button and menu labels are commands (imperative or the infinitive, whichever {LANG} apps use).
7. **Tab names.** The entries whose `ctx` says `tab label` are names on the tab bar, on one or two lines. If a name is longer than 11 characters, break it into two lines with a single `\n` at a natural place, each line at most 12 characters (as the English does: `Application\nManager`). Do not use `\n` anywhere else.
8. **Plurals.** `{0} app` and `{0} apps` are two separate entries (one is shown when the count is exactly 1, the other for any other count). If {LANG} has more plural forms than that (Russian, Arabic ...), prefer a wording that is correct for every count, for example `Apps: {0}` instead of `{0} apps`.
9. **Punctuation and typography.** Follow {LANG}'s own conventions (quotation marks, spacing before `:` or `?`, full-width marks in Chinese and Japanese, the Arabic comma). Keep `…` where the English has it. Do not add or remove sentences, emoji, or line breaks. No trailing or leading spaces.
10. **Colors.** Entries with `ctx` `color name` are names of colors: translate them to the usual color word, or leave the English when {LANG} has no common word (transliterate proper-noun-like ones such as "Periwinkle" only if {LANG} apps normally do).
11. **When in doubt**, keep the English word rather than invent a translation that Android users would not recognize. If a string is already fine in {LANG} as it is (`OK`, `Root`, a number format), return it unchanged.

## Quality bar

Read each entry together with its `ctx`: the same English word can need different translations in different places ("Open" as a button, "Open" as a state). A reviewer who reads {LANG} natively will check a sample of your work for wrong meaning, stiff wording, inconsistent terms, and anything that would not fit on a small button.
