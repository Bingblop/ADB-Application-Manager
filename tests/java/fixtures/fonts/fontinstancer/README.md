# Fonts and expected values for the `fontinstancer` suite

`tests/java/src/FontInstancerTest.java` checks `FontInstancer` (the static instance of a variable font, design section 3.2) against these
files. CI has only a JDK, so everything fontTools, HarfBuzz and FreeType say about the fonts was worked out at dev time by
`make_fixtures.py` and written to the `*.expect.txt` files.

## Files

The five real fonts are subsets made with `fontTools.subset`: outlines, hinting, gvar, HVAR, MVAR, avar, STAT, GDEF and the kern / mark /
mkmk features of GSUB and GPOS are kept for the kept glyphs (and `rvrn` for Roboto Flex).

| File | Size | Glyphs | Made from | Version | SHA-256 of the source | License |
|---|---|---|---|---|---|---|
| `Inter-var.ttf` | 73,744 | 159 | `https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf` | 4.001;git-66647c0bb | `29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031` | OFL 1.1, `OFL.txt` |
| `NotoSans-var.ttf` | 75,944 | 183 | `https://raw.githubusercontent.com/google/fonts/main/ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf` | 2.015 | `bfb7bb691513f12e734dc346c03a03f784912432d7e3fa8e56efcf906fe86b3d` | OFL 1.1, `OFL.txt` |
| `RobotoFlex-var.ttf` | 135,936 | 33 | `https://raw.githubusercontent.com/google/fonts/main/ofl/robotoflex/RobotoFlex%5BGRAD%2CXOPQ%2CXTRA%2CYOPQ%2CYTAS%2CYTDE%2CYTFI%2CYTLC%2CYTUC%2Copsz%2Cslnt%2Cwdth%2Cwght%5D.ttf` | 3.200 | `9b523f7d82593df0107173849ebb8c817471a1df4b4fb2c3cbf40cfd810c8281` | OFL 1.1, `OFL.txt` |
| `FixtureSans3-var.ttf` | 35,572 | 162 | `https://raw.githubusercontent.com/google/fonts/main/ofl/sourcesans3/SourceSans3%5Bwght%5D.ttf` (renamed Fixture Sans 3: 'Source' is a Reserved Font Name) | 3.052 | `042fe2cc0b933e328410d7acbd0aa6a1873dca5aef81875f4bc214b08825c7b9` | OFL 1.1, `OFL.txt` |
| `Roboto-hinted.ttf` | 65,256 | 115 | `https://raw.githubusercontent.com/google/fonts/main/ofl/roboto/Roboto%5Bwdth%2Cwght%5D.ttf` | 3.015 | `d7598e12c5dbef095ff8272cfc55da0250bd07fbdecbac8a530b9b277872a134` | OFL 1.1, `OFL.txt` |
| `synth-gvar.ttf` | 4,220 | 14 | written by `make_fixtures.py` | | | made for these tests, public domain |
| `synth-avar2.ttf` | 1,264 | 3 | written by `make_fixtures.py` | | | made for these tests, public domain |
| `*.expect.txt` | | | written by `make_fixtures.py expect` | | | |
| `make_fixtures.py` | | | the script that made the fonts and the expectations (dev time only) | | | |

The CFF2 fixture is `../cfftoglyf/ss3vf-subset.otf` (Source Sans 3 VF, CFF2), used where it is (its source, version and license are in
that folder's `README.md` and `OFL.txt`); its expectations are `cff2-ss3vf.expect.txt` here, and the suite checks its SHA-256 first.

What each font is for:

* `Inter-var.ttf`: `opsz` + `wght` with avar, HVAR and MVAR; composites whose component offsets vary (`i`, accented letters); kerning
  (PairPos 1 and 2) and mark attachment with variation devices; 9 named instances.
* `NotoSans-var.ttf`: `wdth` + `wght` (usWidthClass), Latin, Greek and Cyrillic.
* `RobotoFlex-var.ttf`: 13 axes (`slnt`, `opsz`, the parametric ones) with avar, 20 named instances, and GSUB FeatureVariations (`rvrn`
  swaps the currency glyphs of `$ ¢ ₦ ₱ ₴ ₵` from wght 600, at narrow widths and at small optical sizes: Inter has none); its glyphs
  already carry the overlap flags. Cut down to 33 glyphs to stay under 150 KB.
* `FixtureSans3-var.ttf`: a weight-only font with 8 named instances and a default that is not Regular (ExtraLight).
* `Roboto-hinted.ttf`: a hinted variable font (fpgm, prep, cvt, instructions in 111 glyphs; no cvar), for `keepHinting` and for the
  hinting that is dropped by default; 18 named instances.
* `synth-gvar.ttf`: every gvar, cvar, MVAR, HVAR and avar 1 case the real fonts do not show: the "all points" encoding, private and shared
  point numbers, 8-, 16-bit and zero delta runs, inferred deltas between, outside and at the same coordinate as listed points, contours with
  one and with no listed point, intermediate regions (one negative), a region on three axes, shared tuples, a composite with scaled and
  2x2 components, a point-matched component, a nested composite, phantom points that move the advance only and the bearing only, glyph
  instructions, a cvar with sparse, all-entries and intermediate tuples, MVAR with 24 tags (a vertical and a gasp one that are left alone),
  an HVAR with an advance map whose rows do not follow the phantom points (and move `.notdef`, `A.rvrn` and `B.rvrn`, which have no gvar
  data), `rvrn` with 3 condition sets, an axis whose default is its maximum (`slnt`) and one whose default is its minimum (`XHGT`).
* `synth-avar2.ttf`: avar version 2 (its own variation store and index map: the width follows the weight and the weight the width).

## The expect files

One line per item, a location opened by `loc` and closed by `end`:

| Line | What |
|---|---|
| `MF` | the names of the metrics on the `M` lines |
| `font` | file, SHA-256, glyph count |
| `loc` | the location's spec (`default`, `min`, `max`, `#k` named instance k, `rand:N` a seeded point, `tag=value,...`) and its user values |
| `norm`, `ftnorm` | the normalized coordinates (F2Dot14) HarfBuzz uses, and fontTools' own |
| `g` | per glyph of a glyf font: id, advance, bearing, bounds, CRC-32 of its points (or components) |
| `P` | every point of up to 3 sample glyphs |
| `c`, `e` | per glyph of the CFF2 font: id, advance, contours, bounds, area and on-curve points of the variable font drawn at the location and converted to quadratics (`e`: an empty glyph) |
| `M` | the OS/2, hhea, post, head and maxp values (`-`: none) |
| `T` | fontTools' table tags |
| `V` | the cvt values |
| `H` | synth-gvar only: HarfBuzz's advances with gvar removed (HVAR gives them) |
| `KG`, `K` | the kerning glyphs, and each kerned pair's two adjustments |
| `AG`, `AM`, `A` | base and mark glyphs, and each attached pair's offset |
| `S` | a character and the glyph `rvrn` gives it |

Everything on a `g` / `P` / `M` / `T` / `V` / `K` / `A` / `S` line comes from fontTools' `instantiateVariableFont` (4.67, full instance,
default options) run at HarfBuzz's normalized coordinates, but for the advances of a glyf font with HVAR (and the average width and hhea
values that follow from them): those are HarfBuzz's at the same coordinates. Before writing anything, `expect` checks its kerning, mark
and `rvrn` evaluators against HarfBuzz shaping (10,522 runs).

## What the suite compares

At every location (58: 8 Inter, 6 Noto Sans, 9 Roboto Flex, 4 Fixture Sans 3, 6 Roboto, 15 synth-gvar, 7 synth-avar2, 3 CFF2):

* the location from the named instance or the tag map, and the normalized coordinates, equal to HarfBuzz's;
* glyf fonts: every glyph's points or components exact (CRC-32), advances exact (HarfBuzz's when the font has HVAR), the sample glyphs
  point for point; bearings and bounds within 1 (composites, see below);
* the CFF2 font: every glyph as CffToGlyfTest compares outlines (contours, on-curve points within 1, bounds within 1, area within 0.5 %),
  advances exact, bearing = xMin;
* the metrics equal (head bounds and hhea's minLeftSideBearing / minRightSideBearing / xMaxExtent within 1), the cvt equal, the tables
  fontTools keeps (less the ones the app drops), kerning and mark attachment equal, the `rvrn` glyphs equal.

The rest is built in the suite: hand-made gvar tables for each encoding and each refusal, hand-made MVAR tables (clamping, the hhea
copy, OS/2 version 1), the options (hinting dropped and kept, overlap flags), the location helpers, errors, progress and Cancel, avar
segment maps, and a seeded fuzz (truncation, bit flips, random bytes, huge counts, word copies in 12 tables; 3,000 instances in about
1 s). `FontInstancerTest --fuzz SEED COUNT` runs only the fuzz.

## Where the app differs from fontTools on purpose

* Normalization: HarfBuzz's, which is the spec's (16.16 through avar, then F2Dot14). fontTools 4.67 maps the value before rounding it and
  is 1 unit (1/16384) apart at 11 of the 58 locations; there its own instance has up to 46 glyphs 1 unit apart from the app's (`verify`
  shows them). The expectations are made at HarfBuzz's coordinates.
* Composite bounds and bearings come from the rounded outline (fontTools takes them from unrounded component offsets): 1 apart for two
  composites at three synth-gvar locations.
* A glyf font with HVAR takes its advances from HVAR, gvar or not, as HarfBuzz and FreeType do; fontTools takes them from gvar's phantom
  points (and ignores HVAR when there is no gvar). The real fixtures' HVAR gives their phantom points' advances to the unit, so only
  synth-gvar shows it; real fonts that move an advance in HVAR only are listed under "Results at dev time". The `H` lines (synth-gvar
  without gvar) check the font without gvar.
* STAT, DSIG, hdmx, LTSH, VDMX, vhea and vmtx are dropped; a CFF2 font becomes a glyf font (fontTools keeps CFF2, with the default's
  bearings).
* MVAR's vertical tags are left alone when there is no vhea (fontTools fails on them).

## Making them again

Dev time only (fontTools 4.67, uharfbuzz 0.57, freetype-py 2.13, numpy; run Python with `-I`, keep each download in its own folder):

```sh
D=/tmp/fontinstancer-dl
mkdir -p "$D/inter" "$D/notosans" "$D/robotoflex" "$D/ss3" "$D/roboto"
curl -L -o "$D/inter/Inter.ttf" 'https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf'
curl -L -o "$D/notosans/NotoSans.ttf" 'https://raw.githubusercontent.com/google/fonts/main/ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf'
curl -L -o "$D/robotoflex/RobotoFlex.ttf" 'https://raw.githubusercontent.com/google/fonts/main/ofl/robotoflex/RobotoFlex%5BGRAD%2CXOPQ%2CXTRA%2CYOPQ%2CYTAS%2CYTDE%2CYTFI%2CYTLC%2CYTUC%2Copsz%2Cslnt%2Cwdth%2Cwght%5D.ttf'
curl -L -o "$D/ss3/SourceSans3.ttf" 'https://raw.githubusercontent.com/google/fonts/main/ofl/sourcesans3/SourceSans3%5Bwght%5D.ttf'
curl -L -o "$D/roboto/Roboto.ttf" 'https://raw.githubusercontent.com/google/fonts/main/ofl/roboto/Roboto%5Bwdth%2Cwght%5D.ttf'
sha256sum "$D"/*/*.ttf
F=tests/java/fixtures/fonts/fontinstancer
python3 -I $F/make_fixtures.py fonts --inter "$D/inter/Inter.ttf" --notosans "$D/notosans/NotoSans.ttf" \
  --robotoflex "$D/robotoflex/RobotoFlex.ttf" --ss3 "$D/ss3/SourceSans3.ttf" --roboto "$D/roboto/Roboto.ttf"
python3 -I $F/make_fixtures.py expect
mkdir -p /tmp/fontinstancer-cls && javac -encoding UTF-8 -nowarn -source 8 -target 8 -sourcepath src -d /tmp/fontinstancer-cls tests/java/src/FontInstancerTest.java
java -cp /tmp/fontinstancer-cls com.bloatware.bingblop.FontInstancerTest --dump /tmp/fontinstancer-dump
python3 -I $F/make_fixtures.py verify /tmp/fontinstancer-dump
```

With the sources above, `fonts` gives these files byte for byte and `expect` the same expect files. Run `--dump` and `verify` again
whenever `FontInstancer` changes what it writes.

## Results at dev time

Not part of CI.

* The suite: 511 checks pass. 4,749 glyphs at the 58 locations: no glyph with other points than fontTools' and no other advance than
  the expected one; the normalized coordinates are HarfBuzz's at all 58.
* `verify` on the suite's instances: against fontTools at HarfBuzz's coordinates, 0 glyphs with other points, 0 other advances, the
  metrics equal but head.xMin and hhea.minLeftSideBearing 1 apart at one synth-gvar location (a composite's bounds); HarfBuzz shaping
  (every character, the kerning pairs, bases with marks, the `rvrn` characters: 44,908 glyphs) gives the same glyphs as the variable font
  at the location with advances equal and offsets at most 1 apart; FreeType draws simple glyphs at most 0.516 units and composites at
  most 1.391 units from the variable font (FreeType keeps the fractions of deltas and component offsets that a static font rounds), and the
  48-pixel renderings of the real glyf fonts are at most 14 of 255 apart with no pixel more than 16 apart (synth-gvar: 19, one pixel at two
  locations); the CFF2 renderings are at most 59 apart (about 2 % of pixels more than 16 apart: the quadratic outlines are within 1 unit of
  the cubic ones); hinted at 16 pixels, Roboto's points are within 1/32 pixel of the variable font's but for one glyph (S at instance 15,
  1 pixel), where fontTools' instance does the same.
  synth-gvar is shaped with its HVAR there, and FreeType reads its advances with it but draws it without: its glyph H moves gvar's left
  phantom point, which HarfBuzz, fontTools and the app move the outline by and FreeType ignores when the font has HVAR.
* The full source fonts (not committed: Inter 2,933 glyphs, Noto Sans 4,515, Roboto Flex 948, Source Sans 3 2,478, Roboto 1,326, and the CFF2
  fonts Source Sans 3 VF 2,478 and Source Serif 4 Variable 1,464), each at its maximum, its minimum and a seeded point: the glyf fonts have
  no glyph with other points and no other advance than fontTools' (one glyph of Source Sans 3 has bounds 1 apart); HarfBuzz gives the same
  glyphs, the same advances and offsets at most 1 apart; FreeType draws simple glyphs within 0.516 units and composites within 1.484; the
  CFF2 fonts match the variable font drawn at the location in every glyph (CffToGlyfTest's criteria) with fontTools' advances. Instancing
  takes 46 to 133 ms per font on the dev machine.
* Advances of 53 glyf variable fonts with HVAR from Google Fonts (369 instances: default, the Regular and Bold the app guesses, minimum,
  maximum, two seeded points; 1,014,842 glyphs) against HarfBuzz and FreeType on the variable font at the location: every advance is
  HarfBuzz's, FreeType's within 1, and HarfBuzz draws every glyph within 2 units of where it draws the variable font's. Taking the advances
  from the phantom points instead, as fontTools does, left 108 glyphs at another width in seven of them: Fraunces' gcircumflex and gmacron
  (1225 for HarfBuzz's 1029 at opsz 9, wght 100), Literata's ldot, tcaron and three more (376 for 513 at opsz 72, wght 900), Oswald's dong
  sign, Playfair Display's underscore and the .notdef of Open Sans and Open Sans Italic, whose gvar data does not move the phantom points
  while HVAR moves the advance, and glyphs of Lexend, Open Sans and Open Sans Italic 1 unit apart (the phantom points round otherwise).
* Shaping runs of many stacked marks in one go (all the combining marks of a block in a row) puts the last mark up to 7 units from where
  HarfBuzz puts it on the variable font, each mark being within 1 of the one it sits on: every anchor is rounded on its own in a static
  font. `verify` shapes the characters one by one.

Not run: on a device.
