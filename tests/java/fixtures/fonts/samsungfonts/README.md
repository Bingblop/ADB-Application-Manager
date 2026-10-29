# Fonts config fixtures for the `samsungfonts` suite

`tests/java/src/SamsungFontsTest.java` checks `SamsungFonts`, which includes a small tolerant parser that finds the
`und-Zsye` (emoji) families in Android's font configuration so the system-emoji swap knows which `/system/fonts` files to
bind over. These fixtures are compact, faithful subsets of the real AOSP font configs, so the parser is exercised against
real data; CI (JDK only, no network, no Python) reads the committed files and `expected.txt`.

No font binaries are committed here (the suite needs none): only the XML configs and the emoji file names each yields.

## Files

| File | Source (fetched 2026-10-26) | License |
|---|---|---|
| `fonts15.xml` | `frameworks/base` `android15-release` `data/fonts/fonts.xml` (trimmed) | Apache-2.0, `NOTICE` |
| `fallback15.xml` | `frameworks/base` `android15-release` `data/fonts/font_fallback.xml` (trimmed) | Apache-2.0, `NOTICE` |
| `fonts12.xml` | `frameworks/base` `android12-release` `data/fonts/fonts.xml` (trimmed) | Apache-2.0, `NOTICE` |
| `expected.txt` | the `und-Zsye` font files each config yields (written by `make_fixtures.py`) | |
| `make_fixtures.py` | the dev-time script that downloaded and trimmed the configs | |
| `NOTICE` | the Android Open Source Project copyright notice and the Apache 2.0 license text | Apache-2.0 |

Each config was fetched from `https://android.googlesource.com/platform/frameworks/base/+/refs/heads/<branch>/data/fonts/<file>?format=TEXT`
(base64), kept to a couple of ordinary families plus every `<family lang="und-Zsye">` - including the `ignore="true"` legacy
family, so the suite can prove it is skipped - and had its whitespace collapsed, leaving each file a few KB.

## expected.txt

One line per config: `<file>: <emoji font file names, space-separated>`. The emoji files are the `<font>` entries of every
`und-Zsye` family that is not `ignore="true"`. The suite parses each config with `SamsungFonts.emojiFamilyFiles` and compares.
