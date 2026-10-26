# Fonts and expected values for the `otlayout` suite

`tests/java/src/OtLayoutTest.java` checks `OtLayout` (GSUB / GPOS / GDEF: the model, the Extension writer, the glyph and lookup index
shifts, the emoji merge, GPOS variation deltas, FeatureVariations and GDEF ligature carets at an instance) against these files. CI has only
a JDK, so everything fontTools and HarfBuzz say about the fonts was worked out at dev time by `make_fixtures.py` and written to
`expected.txt`.

## Files

The fonts are subsets of real fonts: every GSUB, GPOS and GDEF structure is kept for the kept glyphs (`fontTools.subset` with all features
and scripts), the outlines are emptied, and gvar / HVAR / MVAR / COLR / CPAL / hinting are dropped, because the suite reads only GSUB, GPOS,
GDEF, fvar, avar, maxp and cmap.

| File | Size | Glyphs | Made from | Version | SHA-256 of the source | License |
|---|---|---|---|---|---|---|
| `Inter-sub.ttf` | 100,108 | 1073 | `https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf` | 4.001;git-66647c0bb | `29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031` | OFL 1.1, `OFL.txt` |
| `NotoSans-sub.ttf` | 95,968 | 698 | `https://raw.githubusercontent.com/google/fonts/main/ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf` | 2.015 | `bfb7bb691513f12e734dc346c03a03f784912432d7e3fa8e56efcf906fe86b3d` | OFL 1.1, `OFL.txt` |
| `RobotoFlex-sub.ttf` | 43,020 | 148 | `https://raw.githubusercontent.com/google/fonts/main/ofl/robotoflex/RobotoFlex%5BGRAD%2CXOPQ%2CXTRA%2CYOPQ%2CYTAS%2CYTDE%2CYTFI%2CYTLC%2CYTUC%2Copsz%2Cslnt%2Cwdth%2Cwght%5D.ttf` | 3.200 | `9b523f7d82593df0107173849ebb8c817471a1df4b4fb2c3cbf40cfd810c8281` | OFL 1.1, `OFL.txt` |
| `NotoColorEmoji-sub.ttf` | 16,668 | 809 | `https://raw.githubusercontent.com/googlefonts/noto-emoji/v2026-09-24-unicode18_0/2D/fonts/Noto-COLRv1.ttf` (the Noto Color Emoji entry of the design's emoji list) | 2.057 | `b8e25ea68db82f9e4d0aee921f4420be2be39887bd5c893a2ad98710531f9d0c` | OFL 1.1, `OFL.txt` |
| `Twemoji-sub.ttf` | 20,216 | 1053 | `fonts/twemoji.ttf` in `https://github.com/SableClient/twemoji-font/releases/download/v1.0.4/twemoji-font-1.0.4.zip` (zip `d4e9b55a4e57dcedeab2b024d790a10d59656a21804957aefb9a3cccec865bd2`) | 1.0.4 | `d2d721abc0ab04c0f40673e4b521e8b580a6b31d1018b7f2dcb438fdae4c265e` | code Apache-2.0 (Mozilla Foundation), art CC-BY-4.0 (Twemoji), `Twemoji-LICENSE.md` (the release's `LICENSE.md`) |
| `expected.txt` | | | written by `make_fixtures.py expect` and `verify` | | | |
| `make_fixtures.py` | | | the script that made the fonts and `expected.txt` (dev time only) | | | |

Why these: Inter has kerning and mark positioning that vary with `wght` and `opsz` (PairPos 1 and 2, some behind Extension lookups,
MarkBase / MarkMark with Anchor format 3), Noto Sans adds Devanagari, every contextual GSUB format (5.1-5.3, 6.1-6.3), MarkLig, chained
GPOS 8.3 and variable ligature carets (CaretValue format 3), Roboto Flex has GSUB FeatureVariations (`rvrn` swaps in other currency glyphs
from `wght` 600, at narrow widths and at small optical sizes), and the two emoji fonts are what the emoji merge combines with a text font
(ZWJ, flag, keycap and tag sequences through ligatures and contextual lookups). Formats none of them has (GSUB 1.2 next to 1.1 sharing a coverage, GSUB 8, GPOS 1.2 with
VariationIndex devices, GPOS 3, GPOS 7, FeatureParams) are built by hand in the suite.

## expected.txt

One item per line: the glyph count and the shift deltas tried on each font; each table's model as fontTools reads it (lookups with their
real type, flag, subtable count and mark filtering set; features with their lookups and FeatureParams bytes; scripts and language
systems); at each instance location, the normalized coordinates and, from fontTools' `instantiateVariableFont`, the kerning of every letter
pair, the mark attachment of every letter + combining mark, the features after FeatureVariations and the ligature carets; the merges to
make; and the SHA-256 of every table the suite makes. `expect` checks its own kerning and mark evaluators against HarfBuzz shaping before
it writes anything. `verify` records a hash only when every check on the tables passed:

* written tables (every lookup through an Extension lookup) shape in HarfBuzz like the originals (text with 11 feature settings and 4
  scripts; the emoji fonts' sequences and every ligature they define);
* shifted tables are, in fontTools' view, the originals with every glyph id + delta (lookup index + delta);
* instanced GSUB / GPOS / GDEF shape like fontTools' instance within 1 unit (letter pairs, base + one or two marks, random text, 11 feature
  settings), where the uninstanced tables differ in hundreds to thousands of the same runs; the instanced ligature carets have the
  formats fontTools writes (format 1, never a format 3 without a Device table, which OTS refuses);
* merged GSUB tables shape emoji sequences like the emoji font with its glyph ids + the text font's glyph count, in 4 scripts, and text
  like the text font.

## Making them again

Dev time only (fontTools 4.67 and uharfbuzz 0.57; run Python with `-I`, keep the downloads in their own folders):

```sh
D=/tmp/otlayout-dl
mkdir -p "$D/inter" "$D/notosans" "$D/robotoflex" "$D/noto" "$D/twemoji"
curl -L -o "$D/inter/Inter.ttf" 'https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf'
curl -L -o "$D/notosans/NotoSans.ttf" 'https://raw.githubusercontent.com/google/fonts/main/ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf'
curl -L -o "$D/robotoflex/RobotoFlex.ttf" 'https://raw.githubusercontent.com/google/fonts/main/ofl/robotoflex/RobotoFlex%5BGRAD%2CXOPQ%2CXTRA%2CYOPQ%2CYTAS%2CYTDE%2CYTFI%2CYTLC%2CYTUC%2Copsz%2Cslnt%2Cwdth%2Cwght%5D.ttf'
curl -L -o "$D/noto/Noto-COLRv1.ttf" 'https://raw.githubusercontent.com/googlefonts/noto-emoji/v2026-09-24-unicode18_0/2D/fonts/Noto-COLRv1.ttf'
curl -L -o "$D/twemoji/twemoji-font-1.0.4.zip" 'https://github.com/SableClient/twemoji-font/releases/download/v1.0.4/twemoji-font-1.0.4.zip'
(cd "$D/twemoji" && unzip -o twemoji-font-1.0.4.zip fonts/twemoji.ttf)
sha256sum "$D"/*/*.ttf
python3 -I tests/java/fixtures/fonts/otlayout/make_fixtures.py fonts --inter "$D/inter/Inter.ttf" --notosans "$D/notosans/NotoSans.ttf" \
  --robotoflex "$D/robotoflex/RobotoFlex.ttf" --notoemoji "$D/noto/Noto-COLRv1.ttf" --twemoji "$D/twemoji/fonts/twemoji.ttf"
python3 -I tests/java/fixtures/fonts/otlayout/make_fixtures.py expect
mkdir -p /tmp/otlayout-cls && javac -encoding UTF-8 -nowarn -source 8 -target 8 -sourcepath src -d /tmp/otlayout-cls tests/java/src/OtLayoutTest.java
java -cp /tmp/otlayout-cls -Dfixtures=tests/java/fixtures com.bloatware.bingblop.OtLayoutTest --dump /tmp/otlayout-dump
python3 -I tests/java/fixtures/fonts/otlayout/make_fixtures.py verify /tmp/otlayout-dump
```

Run `--dump` and `verify` again whenever `OtLayout` changes what it writes: the suite fails on a hash that no longer matches until the new
tables have passed `verify`. `OtLayoutTest --fuzz SEED COUNT` runs only the fuzz, with another seed or for longer.

## Checks made on the full fonts while OtLayout was written

Not part of CI (the full fonts are not committed); the same comparisons as `verify`, run on the source fonts above and a few more:

* parse + write of GSUB / GPOS, injected into the font: identical HarfBuzz shaping in 21,320 runs each for Inter and Noto Sans, 2,976
  emoji sequences for Noto Color Emoji and 4,427 for Twemoji (without the layout tables, 77 of 150 runs differ: the check is not vacuous);
* `shiftGlyphs` against fontTools' view, every glyph reference + delta: Inter +4000 (396,178 GSUB / 26,266 GPOS references), Noto Sans
  +3000, Source Sans 3 +5000, Noto Color Emoji +20000, Twemoji +4515, Roboto Flex +60000; `shiftLookupIndexes` for Noto Sans +90 and Noto
  Color Emoji +257;
* the instancer (GPOS deltas, FeatureVariations, GDEF) against `instantiateVariableFont`: Inter at wght 700, 900 and 900 + opsz 32, Noto
  Sans at wght 700 and 900 + wdth 62.5, Source Sans 3 at wght 900, Roboto Flex at wght 700, 900, 300 + wdth 25 and 1000 + opsz 144: no
  shaping difference at all (about 13,500 text runs, 13,456 letter pairs and the base + mark runs at each location); Noto Sans' 7 variable
  carets equal fontTools' at wght 700;
* the merge, Inter + Noto Color Emoji, Noto Sans + Twemoji, Noto Sans + Noto Color Emoji, and a font without GSUB + each emoji font:
  emoji sequences shape like the emoji font (2,570 and 4,024 sequences), text like the text font. In Devanagari runs of a text font that
  has a `dev2` script, HarfBuzz's Indic shaper splits ZWJ and keycap sequences the default shaper keeps together; Latin, Cyrillic, Greek
  and Arabic runs are identical.

Not run: on a device.
