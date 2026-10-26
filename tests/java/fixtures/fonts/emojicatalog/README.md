# Emoji download catalogue fixtures for the `emojicatalog` suite

`tests/java/src/EmojiCatalogTest.java` checks `EmojiCatalog`: the five emoji-font entries the Custom Fonts tab offers, and
the streaming verifier and Twemoji zip-entry extraction that download and unpack them. The verifier and the extraction are
tested with bytes the suite makes itself (no network, no committed binaries). The catalogue's *values* - each file's exact
size and SHA-256 - were measured against the real downloads at development time; `manifest.txt` is that record, and the suite
compares the `EmojiCatalog` class against it so an accidental edit is caught with only a JDK.

The emoji fonts themselves are 2-44 MB and are **not committed**: the app downloads them at run time and verifies them
against exactly these values (failing closed on any mismatch). Only the recomputed manifest and the generator live here.

## Files

| File | What |
|---|---|
| `manifest.txt` | the measured `id size sha256 [zipSize zipSha256 zipEntry]` for each download, written by `make_fixtures.py` |
| `make_fixtures.py` | the dev-time script that downloaded each file and recomputed its size and hash (and the zip's, for Twemoji) |

## Sources, versions and licenses (fetched 2026-10-26, each recomputed and matching the catalogue)

| id | Name / version | URL | License |
|---|---|---|---|
| noto | Noto Color Emoji, Unicode 18 | `raw.githubusercontent.com/googlefonts/noto-emoji/v2026-09-24-unicode18_0/2D/fonts/Noto-COLRv1.ttf` | OFL-1.1 |
| twemoji | Twemoji 17 (zip; entry `fonts/twemoji.ttf`) | `github.com/SableClient/twemoji-font/releases/download/v1.0.4/twemoji-font-1.0.4.zip` | code Apache-2.0 (Mozilla Foundation), art CC-BY-4.0 (Twitter / jdecked) |
| blobmoji | Blobmoji (Emoji 17) | `raw.githubusercontent.com/DavidBerdik/blobmoji2/blobmoji-17r1/fonts/Noto-COLRv1.ttf` | OFL-1.1 |
| openmoji | OpenMoji 17 (COLRv1) | `raw.githubusercontent.com/hfg-gmuend/openmoji/17.0.0/font/OpenMoji-color-glyf_colr_1/OpenMoji-color-glyf_colr_1.ttf` | CC-BY-SA-4.0 |
| openmoji-colrv0 | OpenMoji 17 (COLRv0 fallback for Android 12) | `raw.githubusercontent.com/hfg-gmuend/openmoji/17.0.0/font/OpenMoji-color-glyf_colr_0/OpenMoji-color-glyf_colr_0.ttf` | CC-BY-SA-4.0 |
| apple | iOS emoji (iOS 26.2) | `github.com/samuelngs/apple-emoji-ttf/releases/download/v26.2.1/AppleColorEmoji.ttf` | proprietary (Apple Inc.); third-party build by samuelngs, not licensed by Apple for redistribution - the app downloads it only on the user's tap, for their own phone, and applies it as a root system-emoji swap, never merged |

License texts are at the licence URLs in the catalogue (OFL at `https://openfontlicense.org/`, the Creative Commons deeds at
`https://creativecommons.org/licenses/by/4.0/` and `.../by-sa/4.0/`); no font files are redistributed from this repository.
