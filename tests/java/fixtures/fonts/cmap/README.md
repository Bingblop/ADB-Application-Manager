# Fonts and expected values for the `cmap` suite

`tests/java/src/CmapTest.java` checks `Cmap` (the best subtable of a font's character map, its format 14 variation sequences, and the
table writer) and `EmojiData` (which characters an emoji font draws in a merged font). CI has only a JDK, so everything fontTools and
HarfBuzz say was worked out at dev time by `make_fixtures.py` and written to `expected.txt`.

## Files

The fonts keep their character map: Lato, Inter and Twemoji keep the original `cmap` table byte for byte (and their glyph count, which
decides which glyph ids are valid); Noto Color Emoji is subset to every character and variation sequence its map has (fontTools writes the
new `cmap`), so its 42,330 glyphs become 1,509. Outlines are emptied, advances set to 500, and every table outside head, hhea, maxp, hmtx,
cmap, name, OS/2, post, glyf, loca, fvar, avar and STAT is dropped.

| File | Size | Glyphs | Subtables (platform, encoding, format) | Made from | Version | SHA-256 of the source | License |
|---|---|---|---|---|---|---|---|
| `Lato-cmap.ttf` | 23,252 | 3023 | (0,3) 4, (1,0) 6, (3,1) 4 | `https://raw.githubusercontent.com/google/fonts/main/ofl/lato/Lato-Regular.ttf` | 2.015 | `d636e4683231f931eda222d588e944d082bfd3bdba02f928bee461c0f185b251` | OFL 1.1, `OFL.txt` (renamed: "Lato" is a Reserved Font Name) |
| `Inter-cmap.ttf` | 41,260 | 2933 | (0,3) 4, (0,4) 12, (3,1) 4, (3,10) 12 | `https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf` | 4.001;git-66647c0bb | `29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031` | OFL 1.1, `OFL.txt` |
| `Twemoji-cmap.ttf` | 68,380 | 16107 | (0,3) 4, (0,4) 12, (0,5) 14, (1,0) 0, (3,1) 4, (3,10) 12 | `fonts/twemoji.ttf` in `https://github.com/SableClient/twemoji-font/releases/download/v1.0.4/twemoji-font-1.0.4.zip` (zip `d4e9b55a4e57dcedeab2b024d790a10d59656a21804957aefb9a3cccec865bd2`) | 1.0.4 | `d2d721abc0ab04c0f40673e4b521e8b580a6b31d1018b7f2dcb438fdae4c265e` | code Apache-2.0 (Mozilla Foundation), art CC-BY-4.0 (Twemoji), `Twemoji-LICENSE.md` (the release's `LICENSE.md`) |
| `NotoColorEmoji-cmap.ttf` | 11,896 | 1509 | (0,3) 4, (0,4) 12, (0,5) 14 with 371 sequences, (3,1) 4, (3,10) 12 | `https://raw.githubusercontent.com/googlefonts/noto-emoji/v2026-09-24-unicode18_0/2D/fonts/Noto-COLRv1.ttf` | 2.057 | `b8e25ea68db82f9e4d0aee921f4420be2be39887bd5c893a2ad98710531f9d0c` | OFL 1.1, `OFL.txt` |
| `Symbol-cmap.ttf` | 4,928 | 96 | (1,0) 0, (3,0) 4 at U+F020..F07E | Lato's printable ASCII glyphs (the Lato file above) behind a Windows symbol map, as symbol fonts have them | | | OFL 1.1, `OFL.txt` (renamed) |
| `expected.txt` | | | | written by `make_fixtures.py expect`, `emojidata` and `verify` | | | |
| `make_fixtures.py` | | | | the script that made the fonts and `expected.txt` (dev time only) | | | |
| `OFL.txt` | | | | the copyright notices of the OFL fonts above and the OFL 1.1 text | | | |
| `Twemoji-LICENSE.md` | | | | the Twemoji font release's license | | | |

No real OFL font with a Windows symbol (3, 0) map was found (Font Awesome 4.7, Typicons and Elusive Icons all use (3, 1)), so
`Symbol-cmap.ttf` is made from Lato.

`EmojiData`'s Emoji_Presentation table was written by `make_fixtures.py emojidata` from Unicode 18.0.0's
`https://www.unicode.org/Public/18.0.0/ucd/emoji/emoji-data.txt` (dated 2026-01-30, SHA-256
`80d00f8e616a0ef27fd6b8de3b758c06383b5d917e2977709578e68baf733bf1`, Unicode License v3), and its table of the characters with an emoji
variation sequence (c, U+FE0F) from `https://www.unicode.org/Public/18.0.0/ucd/emoji/emoji-variation-sequences.txt` (dated 2026-01-09,
SHA-256 `ff1707564aa1f1b2fcf4ec92d609d4cb26940bc0d4dcf07f5328ad6879e84da3`); `expected.txt` keeps the version, count (1,228 and 371 code
points) and the hash of each list, and the suite compares the tables with them.

## expected.txt

* `emoji VERSION COUNT SHA`: the Emoji_Presentation list above.
* `variation VERSION COUNT SHA`: the characters with an emoji variation sequence above.
* `font REL FILESHA PID EID FORMAT COUNT MAPSHA UVSCOUNT UVSSHA`, for every `.ttf` / `.otf` under `tests/java/fixtures/fonts` (this folder
  and every other suite's, keyed by the file's SHA-256): the subtable fontTools reads (`getBestCmap`'s order (3,10), (0,6), (0,4), (3,1),
  (0,3), (0,2), (0,1), (0,0), then the Windows symbol (3,0), then Mac (1,0) with Mac Roman codes made Unicode as in Apple's ROMAN.TXT;
  glyph 0 and ids past the glyph count left out), the number of characters and the SHA-256 of `"%X %d\n"` per character, and the same for
  the format 14 sequences (`"%X %X D\n"` for a default one, `"%X %X %d\n"` with its glyph otherwise). A font Sfnt refuses for having no
  outlines (a bitmap emoji font) is read table by table, as its map is still read.
* `built CASE SHA`: the SHA-256 of every table the suite builds (from each fixture font's map and from five synthetic maps: 40,000
  scattered BMP characters, all 65,535 BMP characters, characters in every plane with sequences, an empty map and one character).
  `verify` records a hash only when every check on that table passed: records sorted, format 4's search fields, segments and closing
  segment as the specification asks; fontTools reads back the map and sequences it was built from ((0,3) and (3,1) the same table, format
  12 the whole map, format 4 a prefix of the BMP characters in code point order: all of them when they fit in 64 KB); HarfBuzz finds every
  glyph and every variation glyph through it.

The suite fails on a font that changed (its SHA-256 is no longer recorded) only in this folder; a new or changed font in another suite's
folder gets a `NOTE` and is checked only for reading and building back, until `expect` (and `--dump` + `verify`) are run again.

## Making them again

Dev time only (fontTools 4.67 and uharfbuzz 0.57; run Python with `-I`, keep the downloads in their own folders):

```sh
D=/tmp/cmap-dl
mkdir -p "$D/lato" "$D/inter" "$D/noto" "$D/twemoji" "$D/ucd"
curl -L -o "$D/lato/Lato-Regular.ttf" 'https://raw.githubusercontent.com/google/fonts/main/ofl/lato/Lato-Regular.ttf'
curl -L -o "$D/inter/Inter.ttf" 'https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf'
curl -L -o "$D/noto/Noto-COLRv1.ttf" 'https://raw.githubusercontent.com/googlefonts/noto-emoji/v2026-09-24-unicode18_0/2D/fonts/Noto-COLRv1.ttf'
curl -L -o "$D/twemoji/twemoji-font-1.0.4.zip" 'https://github.com/SableClient/twemoji-font/releases/download/v1.0.4/twemoji-font-1.0.4.zip'
(cd "$D/twemoji" && unzip -o twemoji-font-1.0.4.zip fonts/twemoji.ttf)
curl -L -o "$D/ucd/emoji-data.txt" 'https://www.unicode.org/Public/18.0.0/ucd/emoji/emoji-data.txt'
curl -L -o "$D/ucd/emoji-variation-sequences.txt" 'https://www.unicode.org/Public/18.0.0/ucd/emoji/emoji-variation-sequences.txt'
sha256sum "$D"/*/*.ttf "$D"/*/fonts/*.ttf "$D"/ucd/*.txt
python3 -I tests/java/fixtures/fonts/cmap/make_fixtures.py fonts --lato "$D/lato/Lato-Regular.ttf" --inter "$D/inter/Inter.ttf" \
  --twemoji "$D/twemoji/fonts/twemoji.ttf" --notoemoji "$D/noto/Noto-COLRv1.ttf"
python3 -I tests/java/fixtures/fonts/cmap/make_fixtures.py emojidata "$D/ucd/emoji-data.txt" "$D/ucd/emoji-variation-sequences.txt"   # prints EmojiData's tables
python3 -I tests/java/fixtures/fonts/cmap/make_fixtures.py expect
mkdir -p /tmp/cmap-cls && javac -encoding UTF-8 -nowarn -source 8 -target 8 -sourcepath src -d /tmp/cmap-cls tests/java/src/CmapTest.java
java -cp /tmp/cmap-cls -Dfixtures=tests/java/fixtures com.bloatware.bingblop.CmapTest --dump /tmp/cmap-dump
python3 -I tests/java/fixtures/fonts/cmap/make_fixtures.py verify /tmp/cmap-dump
```

Run `expect`, `--dump` and `verify` again whenever a fixture font anywhere under `tests/java/fixtures/fonts` changes or `Cmap.build`
changes what it writes. `CmapTest --fuzz SEED COUNT` runs only the fuzz, with another seed or for longer (30,000 cases ran in 30 s).

## Checks made on full fonts while Cmap was written

Not part of CI (the full fonts are not committed). `make_fixtures.py check --classes CLASSES FONT...` runs the suite's `--read` mode
(Cmap's best subtable, map and sequences) and compares it with fontTools; it agreed on all 23 fonts tried: Inter 4.001, Lato 2.015, Noto
Sans 2.015, Roboto Flex 3.200, Source Sans 3 VF (TrueType), Source Sans 3 Semibold Italic (CFF) and VF Upright (CFF2) 3.052, Noto Color
Emoji COLRv1 2.057 and 2.051, Noto Color Emoji CBDT 2.051, Blob2 (COLRv1), Fluent Emoji Color and Flat (COLRv1), OpenMoji 17
(COLRv0 and v1), Twemoji Mozilla (Firefox 158) and the Sable Twemoji build, Apple Color Emoji 26.2.1 and a 115 MB Linux build (CBDT,
read table by table), Font Awesome 4.7, Typicons and Elusive Icons. `CmapTest --dump DIR FONT...` with `verify DIR --no-record` checked the
tables built from 16 of them the same way as the recorded ones (all passed; for Inter the new table is 25,480 bytes where the original is
25,866).

Not run: on a device. The suite runs on the JDK only.
