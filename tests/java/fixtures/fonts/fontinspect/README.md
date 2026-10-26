# Fonts and expected values for the `fontinspect` suite

`tests/java/src/FontInspectTest.java` checks `FontInspect` (what a font the user picked is: the names shown in font menus, weight, width,
style, embedding bits, outline kind, glyph count, units per em, characters, colour format, variation axes, named instances, the Regular
/ Bold guesses and the warnings) against what fontTools reads in every font under `tests/java/fixtures/fonts` (this folder and every
other suite's). CI has only a JDK, so fontTools' values were worked out at dev time by `make_fixtures.py` and written to `expected.txt`.

## Files

Subsets made with `fontTools.subset` (layout tables, BASE, DSIG, JSTF and meta dropped; hinting, CFF subroutines, COLR / CPAL and CBDT /
CBLC kept for the kept glyphs).

| File | Size | Glyphs | What it is | Made from | Version | SHA-256 of the source | License |
|---|---|---|---|---|---|---|---|
| `FixtureSans-SemiboldIt.otf` | 11,368 | 96 | CFF, static, weight 600 italic, typographic names (16 / 17) that differ from 1 / 2; printable ASCII | `https://raw.githubusercontent.com/adobe-fonts/source-sans/release/OTF/SourceSans3-SemiboldIt.otf` | 3.052 | `81204ed282543df2d93db214c98b5ec1c9ce735c4c82e4323d5ae5ee69c6b8b7` | OFL 1.1, `OFL.txt` (renamed: 'Source' is a Reserved Font Name) |
| `FixtureSansVF.otf` | 16,920 | 96 | CFF2 variable: wght 200..900 with the default at 200, 7 named instances, avar, HVAR, MVAR, STAT; printable ASCII | `https://raw.githubusercontent.com/adobe-fonts/source-sans/release/VF/SourceSans3VF-Upright.otf` | 3.052 | `3d0dfd6a3a644ab3d462a737923ffac41fb0ae007ce9ba83c24e6bfa76aa56c7` | OFL 1.1, `OFL.txt` (renamed) |
| `NotoColorEmoji-COLRv1.ttf` | 5,588 | 24 | COLRv1 + CPAL, 4 characters (U+1F600, 1F44D, 2764, 1F3FD) | `https://raw.githubusercontent.com/googlefonts/noto-emoji/v2026-09-24-unicode18_0/2D/fonts/Noto-COLRv1.ttf` | 2.057 | `b8e25ea68db82f9e4d0aee921f4420be2be39887bd5c893a2ad98710531f9d0c` | OFL 1.1, `OFL.txt` |
| `Twemoji-COLRv0.ttf` | 5,908 | 13 | COLRv0 + CPAL, the same 4 characters | `fonts/twemoji.ttf` in `https://github.com/SableClient/twemoji-font/releases/download/v1.0.4/twemoji-font-1.0.4.zip` (zip `d4e9b55a4e57dcedeab2b024d790a10d59656a21804957aefb9a3cccec865bd2`) | 1.0.4 | `d2d721abc0ab04c0f40673e4b521e8b580a6b31d1018b7f2dcb438fdae4c265e` | code Apache-2.0 (Mozilla Foundation), art CC-BY-4.0 (Twemoji), `Twemoji-LICENSE.md` (the release's `LICENSE.md`) |
| `NotoColorEmoji-CBDT.ttf` | 7,356 | 3 | CBDT / CBLC bitmaps and no outlines: refused with code `bitmap_emoji`; 2 characters (U+1F600, 1F44D) | `https://raw.githubusercontent.com/googlefonts/noto-emoji/v2.051/fonts/NotoColorEmoji.ttf` (the bitmap font is not in the Unicode 18 tag) | 2.051 | `72a635cb3d2f3524c51620cdde406b217204e8a6a06c6a096ff8ed4b5fd6e27b` | OFL 1.1, `OFL.txt` |
| `expected.txt` | | | | written by `make_fixtures.py expect` | | | |
| `make_fixtures.py` | | | | the script that made the fonts and `expected.txt` (dev time only) | | | |
| `OFL.txt` | | | | the copyright notices of the OFL fonts above and the OFL 1.1 text | | | |
| `Twemoji-LICENSE.md` | | | | the Twemoji font release's license | | | |

The two Source Sans 3 subsets are renamed "Source" -> "Fixture" in every name record but the copyright, trademark, license and license
address (0, 7, 13, 14), and in the CFF font name, full name and family name. Together with the other suites' fonts (TrueType variable
fonts with one, two and thirteen axes, a CFF2 font without named instances, CID-keyed CFF, a Windows symbol font, emoji fonts without
their colour tables) they give the suite every outline kind, every colour format the emoji merge takes, and the bitmap one it refuses.

## expected.txt

For every `.ttf` / `.otf` under `tests/java/fixtures/fonts`: `font REL FILESHA`, then one line per field in the order the suite writes them
(`family`, `subfamily`, `fullName`, `postScriptName`, `version`, `copyright`, `designer`, `license`, `licenseUrl`, `weight`, `width`,
`fsType`, `italic`, `outlines`, `glyphs`, `upem`, `codepoints`, `latin`, `symbol`, `color`, `variable`, `avar2`, then `axis TAG MIN DEF
MAX HIDDEN NAME` per axis, `instance TAG=VALUE... NAME` per named instance, `guessRegular` / `guessBold` as `TAG=VALUE...` or `none`, and
`warning CODE` per warning), then `end`; or `error CODE` for a font FontInspect refuses. Texts escape `\`, line breaks and tabs, and tags
also spaces (`\s`); numbers are exact decimals (fvar's 16.16 values end within 16 places). The suite looks a font up by its SHA-256 and
compares line by line, so a failure names the first field that differs.

What fontTools reads, and the rules FontInspect adds on top of it (the script mirrors them):

* names: the record Sfnt.name picks (Windows Unicode English, then any Windows Unicode, then Unicode, then Mac English, then Windows
  symbol; the first of the best kind), NULs removed and trimmed; family and style are the typographic ones (16 / 17) when present, else 1
  / 2; the full name is 4, else family + style; texts are cut at 1000 characters with an ellipsis;
* weight, width, fsType and italic (fsSelection bit 0 or head.macStyle bit 1) from OS/2 when it has 64 bytes or more, else weight 700 /
  400 and italic from head.macStyle;
* characters: the best map of the cmap suite (`../cmap/make_fixtures.py`); `latin` when A-Z, a-z and 0-9 are all there; `symbol` when it
  is a Windows symbol (3, 0) map;
* colour: COLRv1 when COLR version 1 has base glyph paint records, COLRv0 when it has version 0 base glyph records, either only with a CPAL
  (else the no-palette warning; a COLR cut short of its header or base glyph list gives the damaged-colour warning, and the script
  gives it when fontTools cannot read the table); then CBDT, sbix, SVG (with their warnings); a font with CBDT or sbix and no outlines is
  refused (`bitmap_emoji`);
* axes as fvar has them (a minimum above the default or a maximum below it mended to the default, as VarStore does), named after their
  name id or their tag; instances named after their style name id, or after their location ("wght 700, opsz 32") when that name is
  missing;
* the guesses: the named instance whose wght, clamped to the axis, is closest to 400 (Regular) or 700 (Bold), ties going to the one
  nearest the defaults of the other axes and then to the first, unless wght itself clamped to its range (the other axes at their
  defaults) is closer; with no wght axis, Regular is every axis at its default and Bold is none;
* warnings, in this order: the colour ones; then symbol map, or no characters, or no basic Latin (only for a font without COLR or other
  colour glyphs); restricted-license embedding (fsType bit 0x0002); avar version 2.

A font that changed in this folder or in `../cmap` fails the suite until `expect` is run again; a new or changed font in another suite's
folder gets a `NOTE` and is checked only for being read or refused with a FontException.

## Making them again

Dev time only (fontTools 4.67; run Python with `-I`, keep the downloads in their own folders):

```sh
D=/tmp/fontinspect-dl
mkdir -p "$D/ss3it" "$D/ss3vf" "$D/noto" "$D/notocbdt" "$D/twemoji"
curl -L -o "$D/ss3it/SourceSans3-SemiboldIt.otf" 'https://raw.githubusercontent.com/adobe-fonts/source-sans/release/OTF/SourceSans3-SemiboldIt.otf'
curl -L -o "$D/ss3vf/SourceSans3VF-Upright.otf" 'https://raw.githubusercontent.com/adobe-fonts/source-sans/release/VF/SourceSans3VF-Upright.otf'
curl -L -o "$D/noto/Noto-COLRv1.ttf" 'https://raw.githubusercontent.com/googlefonts/noto-emoji/v2026-09-24-unicode18_0/2D/fonts/Noto-COLRv1.ttf'
curl -L -o "$D/notocbdt/NotoColorEmoji.ttf" 'https://raw.githubusercontent.com/googlefonts/noto-emoji/v2.051/fonts/NotoColorEmoji.ttf'
curl -L -o "$D/twemoji/twemoji-font-1.0.4.zip" 'https://github.com/SableClient/twemoji-font/releases/download/v1.0.4/twemoji-font-1.0.4.zip'
(cd "$D/twemoji" && unzip -o twemoji-font-1.0.4.zip fonts/twemoji.ttf)
sha256sum "$D"/*/*.otf "$D"/*/*.ttf "$D"/twemoji/fonts/*.ttf
python3 -I tests/java/fixtures/fonts/fontinspect/make_fixtures.py fonts --ss3it "$D/ss3it/SourceSans3-SemiboldIt.otf" \
  --ss3vf "$D/ss3vf/SourceSans3VF-Upright.otf" --notocolr "$D/noto/Noto-COLRv1.ttf" --twemoji "$D/twemoji/fonts/twemoji.ttf" \
  --notocbdt "$D/notocbdt/NotoColorEmoji.ttf"
python3 -I tests/java/fixtures/fonts/fontinspect/make_fixtures.py expect
```

New fonts change the cmap suite's `expected.txt` too: run its `expect`, `--dump` and `verify` (see `../cmap/README.md`). Run this
folder's `expect` whenever a fixture font anywhere under `tests/java/fixtures/fonts` changes. `FontInspectTest --fuzz SEED COUNT` runs
only the fuzz, with another seed or for longer.

## Checks made on full fonts while FontInspect was written

Not part of CI (the full fonts are not committed). `make_fixtures.py check --classes CLASSES FONT...` runs `FontInspectTest --dump` on
each font and compares every line with the script's; all 20 fonts tried agreed: Inter 4.001, Lato 2.015, Noto Sans 2.015, Roboto Flex
3.200 (13 axes, 20 instances), Source Sans 3 VF (TrueType), Source Sans 3 Semibold Italic (CFF) and VF Upright (CFF2) 3.052, Noto Color
Emoji COLRv1 2.057 and 2.051, Noto Color Emoji CBDT 2.051 and Apple Color Emoji 26.2.1 (both refused: `bitmap_emoji`), Blob2, Fluent
Emoji Flat, OpenMoji 17 (COLRv0 and v1), Twemoji Mozilla (Firefox 158), the Sable Twemoji build, Font Awesome 4.7, Typicons and Elusive
Icons.

Not run: on a device. The suite runs on the JDK only.
