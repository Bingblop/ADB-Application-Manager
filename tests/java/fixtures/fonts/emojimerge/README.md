# Fonts and expected values for the `emojimerge` suite

`tests/java/src/EmojiMergeTest.java` checks `EmojiMerge`, which merges a colour emoji font (COLR v0 or v1 with CPAL, TrueType outlines)
into a TrueType text font. CI has only a JDK, so what fontTools, HarfBuzz and FreeType say about these fonts was worked out at dev time by
`make_fixtures.py` and written to `expected.txt`.

The suite merges nine pairs (each text font with Noto and Twemoji, plus Inter and Noto Sans with the synthetic COLR v1 font and Lato with
the synthetic COLR v0 one) and checks each merged font table by table against its two sources: glyph counts, the text glyphs byte for
byte, every emoji glyph scaled by s = round(text upem / emoji upem * 65536) / 65536 with its hints dropped and its components moved past
the text glyphs, hmtx, head bounds, cmap and the format 14 variation sequences, GSUB (the text font's lookups, the U+FE0F restore and
text-to-emoji swap lookups, the emoji lookups, all turned on in `rlig` in every script and language system), GDEF, GPOS and kern, COLR
(v0 records; v1 wrappers, paint graphs, clip boxes rounded outwards), CPAL, post v3, OS/2, the dropped tables and the warnings. Then it
shapes each merged font with a small GSUB applier written for the suite (below): every sequence must give the emoji font's glyphs plus
the text font's glyph count, or the text font's glyphs where the text form wins, and every text character and text run the text font's
glyphs. After that: the COLR v1 and v0 details on the synthetic fonts, glyph classes, the options, refusals with their codes, damaged
emoji tables, progress and Cancel, hand-built tables, and a seeded fuzz (truncation, bit flips, random bytes, huge counts) of both inputs.

## Files

| File | Size | Glyphs | Units per em | Made from | Version | SHA-256 of the source | License |
|---|---|---|---|---|---|---|---|
| `Lato-sub.ttf` | 95,308 | 467 | 2000 | `https://raw.githubusercontent.com/google/fonts/main/ofl/lato/Lato-Regular.ttf` | 2.015 | `d636e4683231f931eda222d588e944d082bfd3bdba02f928bee461c0f185b251` | OFL 1.1, `OFL.txt` (renamed "Fixture L": "Lato" is a Reserved Font Name) |
| `Inter-sub.ttf` | 87,284 | 687 | 2048 | `https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf` (instance wght 400, opsz 14) | 4.001;git-66647c0bb | `29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031` | OFL 1.1, `OFL.txt` |
| `NotoSans-kern.ttf` | 28,472 | 388 | 1000 | `https://raw.githubusercontent.com/google/fonts/main/ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf` (instance wght 400, wdth 100) | 2.015 | `bfb7bb691513f12e734dc346c03a03f784912432d7e3fa8e56efcf906fe86b3d` | OFL 1.1, `OFL.txt` |
| `Noto-sub.ttf` | 95,432 | 772 | 1024 | `https://raw.githubusercontent.com/googlefonts/noto-emoji/v2026-09-24-unicode18_0/2D/fonts/Noto-COLRv1.ttf` | 2.057 | `b8e25ea68db82f9e4d0aee921f4420be2be39887bd5c893a2ad98710531f9d0c` | OFL 1.1, `OFL.txt` |
| `Twemoji-sub.ttf` | 56,320 | 624 | 512 | `fonts/twemoji.ttf` in `https://github.com/SableClient/twemoji-font/releases/download/v1.0.4/twemoji-font-1.0.4.zip` (zip `d4e9b55a4e57dcedeab2b024d790a10d59656a21804957aefb9a3cccec865bd2`) | 1.0.4 | `d2d721abc0ab04c0f40673e4b521e8b580a6b31d1018b7f2dcb438fdae4c265e` | code Apache-2.0 (Mozilla Foundation), art CC-BY-4.0 (Twemoji), `Twemoji-LICENSE.md` (the release's `LICENSE.md`) |
| `Synth-COLRv1.ttf` | 2,224 | 13 | 1000 | drawn by `make_fixtures.py` | | | as this repository (no font data from elsewhere) |
| `Synth-COLRv0.ttf` | 1,028 | 7 | 2048 | drawn by `make_fixtures.py` | | | as this repository (no font data from elsewhere) |
| `expected.txt` | | | | written by `make_fixtures.py expect` and `verify` | | | |
| `make_fixtures.py` | | | | the script that made the fonts and `expected.txt` (dev time only) | | | |
| `OFL.txt` | | | | the copyright notices of the OFL fonts above and the OFL 1.1 text | | | |
| `Twemoji-LICENSE.md` | | | | the Twemoji font release's license | | | |

SHA-256 of the fixtures themselves: `expected.txt` keeps them (`font` lines), and the suite fails when one changed.

What they hold:

* **Lato-sub.ttf**: Lato subset to Latin-1, U+2122, U+2194, U+2195, U+2764, U+200D, U+2640, U+263A, U+261D, U+0301, dashes and quotes
  (the characters a text font and an emoji font both may have), with its hinting (fpgm, prep, cvt) and its GSUB (types 1, 4 and 6 format
  3; scripts DFLT, cyrl, grek, latn with a TRK language system), no GDEF.
* **Inter-sub.ttf**: a static instance of Inter, the same characters, no hinting; GDEF 1.2 with classes and mark glyph sets; GSUB types
  1, 3, 4 and 6 (formats 1, 2 and 3), some lookups with IgnoreMarks; scripts DFLT and latn with a CAT language system.
* **NotoSans-kern.ttf**: a static instance of Noto Sans, the same characters, with GSUB, GPOS and GDEF removed and a kern table (format 0,
  the pair kerning HarfBuzz applied from GPOS for 27 letters) added: a text font with no OpenType layout at all.
* **Noto-sub.ttf**: Noto Color Emoji COLRv1 subset to the characters of the suite's sequences (plus the skin tones, U+FE0E, U+FE0F,
  U+200D, U+20E3), with its COLR v1, CPAL, GSUB (types 1, 2, 4, 5 format 3 and 6 format 3 in ccmp), vhea / vmtx and cmap format 14.
* **Twemoji-sub.ttf**: Twemoji (the Sable COLRv0 build, units per em 512, hinted) subset the same way, with COLR v0, CPAL, GSUB (type 4
  ligatures in ccmp, DFLT only) and cmap format 14.
* **Synth-COLRv1.ttf**: every one of the 32 COLR v1 paint formats; PaintColrGlyph (e3 draws e1 moved right); a glyph that a paint draws
  and the cmap maps too ("both", U+1F7E2); a composite drawn only by paints; a variable clip box; glyphs whose lsb is not their xMin;
  GDEF 1.2 with mark attachment classes and a mark glyph set, and three lookups using IgnoreMarks, UseMarkFilteringSet and
  MarkAttachmentType; a mark at U+0302 (which the text fixtures do not have); a GPOS the merge leaves out. Glyph order: .notdef, space,
  sq, circ, tri, comp, both, mk, e1 (U+1F7E0), e2 (U+1F7E1), e3 (U+1F7E3), e4 (U+1F7E4), lig.
* **Synth-COLRv0.ttf**: COLR v0 at units per em 2048 with hinted layer glyphs (fpgm, prep), a composite layer, a layer glyph the cmap
  maps too (U+1F7E7) and advances that round differently when scaled; no GSUB.

Fonts are written with a fixed head.modified, so `fonts` makes the same files again.

## expected.txt

* `font NAME SHA GLYPHS UPEM`: each fixture.
* `seq NAME CPS : IDS`: the glyph ids HarfBuzz gives for a sequence with that font alone (no script set, language "und", default
  ignorables removed). The emoji fonts get the suite's sequences, the synthetic fonts and the text fonts the synthetic sequences too, and
  `Synth-COLRv1.ttf/noGDEF` is that font without its GDEF: a merged font whose text font has no GDEF has none, so the emoji lookups run
  with the classes HarfBuzz takes from Unicode instead, and the merge warns about it.
* `run NAME SCRIPT CPS : IDS`: a text run shaped with a text font alone, for the script HarfBuzz guesses (Latn) and for Common (Zyyy).
* `verified NAME SHA`: a merged font (as the suite makes it, written by `--dump`) that passed every check of `verify`. The suite notes,
  but does not fail, a merged font whose hash is not listed, and still runs its own checks on it.

## The suite's GSUB applier

HarfBuzz's default shaper for one font, written for this suite and checked against `expected.txt` for every font alone before it is
used on the merged fonts: the cmap with variation sequences (a base and its marks; a found sequence takes the selector in), glyph classes
from GDEF or, without them, from Unicode (a non-spacing mark that is not default-ignorable is a mark), the default features in HarfBuzz's
two GSUB stages (rvrn, then the others in lookup order; the first feature of a tag in the language system counts; script latn, DFLT, dflt
for Latn and zyyy, DFLT, dflt, latn for Common), lookup types 1 to 6 with their flags and mark filtering sets, HarfBuzz's skipping
(default-ignorables passed over unless substituted, except ZWNJ in the input and hidden ones), ligatures that put the glyphs they passed
over after themselves, MultipleSubst outputs that keep the input's character, nested lookups with HarfBuzz's position bookkeeping, and the
removal of the default-ignorables no lookup produced. Not emulated: type 8 (reverse chaining), ligature component ids, required features,
the `rand` feature and script-specific shapers; a font that needs one stops the applier with an error, so the suite fails instead of
guessing.

## Making them again

Dev time only (fontTools 4.67, uharfbuzz 0.57 with HarfBuzz 15.0.0, freetype-py with FreeType 2.13.2; run Python with `-I`, keep each
download in its own folder):

```sh
D=/tmp/emojimerge-dl
mkdir -p "$D/lato" "$D/inter" "$D/notosans" "$D/noto" "$D/twemoji"
curl -L -o "$D/lato/Lato-Regular.ttf" 'https://raw.githubusercontent.com/google/fonts/main/ofl/lato/Lato-Regular.ttf'
curl -L -o "$D/inter/Inter.ttf" 'https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf'
curl -L -o "$D/notosans/NotoSans.ttf" 'https://raw.githubusercontent.com/google/fonts/main/ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf'
curl -L -o "$D/noto/Noto-COLRv1.ttf" 'https://raw.githubusercontent.com/googlefonts/noto-emoji/v2026-09-24-unicode18_0/2D/fonts/Noto-COLRv1.ttf'
curl -L -o "$D/twemoji/twemoji-font-1.0.4.zip" 'https://github.com/SableClient/twemoji-font/releases/download/v1.0.4/twemoji-font-1.0.4.zip'
(cd "$D/twemoji" && unzip -o twemoji-font-1.0.4.zip fonts/twemoji.ttf)
sha256sum "$D"/*/*.ttf "$D"/twemoji/fonts/*.ttf "$D"/twemoji/*.zip
M=tests/java/fixtures/fonts/emojimerge/make_fixtures.py
python3 -I $M fonts --lato "$D/lato/Lato-Regular.ttf" --inter "$D/inter/Inter.ttf" --notosans "$D/notosans/NotoSans.ttf" \
  --noto "$D/noto/Noto-COLRv1.ttf" --twemoji "$D/twemoji/fonts/twemoji.ttf"
python3 -I $M expect
mkdir -p /tmp/emojimerge-cls && javac -encoding UTF-8 -nowarn -source 8 -target 8 -sourcepath src -d /tmp/emojimerge-cls tests/java/src/EmojiMergeTest.java
java -cp /tmp/emojimerge-cls -Dfixtures=tests/java/fixtures com.bloatware.bingblop.EmojiMergeTest --dump /tmp/emojimerge-dump
python3 -I $M verify /tmp/emojimerge-dump
```

Run `expect`, `--dump` and `verify` again whenever a fixture or what `EmojiMerge` writes changes. `verify` checks each merged font against
its two sources: fontTools reads every table and glyph; HarfBuzz shapes every sequence as the emoji font alone (glyph ids plus the text
font's glyph count, each glyph advancing by the emoji font's own advance times s), every emoji character alone, with U+FE0F and with
U+FE0E as the text font or the emoji font alone (whichever form wins), every text character and text run as the text font alone (also with
liga / kern off, smcp, frac, tnum + case, and in Latin, Vietnamese, Cyrillic and Common), and the sequences in 12 scripts (right to left in
Arabic and Hebrew); FreeType walks the COLR v0 layers and renders them, or the COLR v1 paint graph (the merge's scale and inverse-scale
wrappers taken out) and clip boxes; HarfBuzz rasterizes the colour glyphs of both fonts at 128 px and compares the pixels. All nine
passed with no difference in any of these. `EmojiMergeTest --fuzz SEED COUNT` runs only the fuzz, with another seed or for longer
(seed 777, 20,000 cases: 12,543 merged, 7,457 refused with a FontException, nothing else, in 19 s; the suite's own 2,500 take about 3 s).

`make_fixtures.py check TEXT EMOJI MERGED --emoji-test FILE` runs the same checks on full fonts merged at dev time, with every sequence of
Unicode's emoji-test.txt.

## Checks made on full fonts while EmojiMerge was written

Not part of CI (the full fonts are not committed). Lato 2.015, and static wght 400 instances of Inter 4.001 and Noto Sans 2.015 (the
sources above), were each merged with the five catalog emoji fonts that can be merged: Noto Color Emoji 2.057 (COLRv1, the source above),
Twemoji 17 (Sable 1.0.4, COLRv0), OpenMoji 17.0.0 COLRv1 (`https://raw.githubusercontent.com/hfg-gmuend/openmoji/17.0.0/font/OpenMoji-color-glyf_colr_1/OpenMoji-color-glyf_colr_1.ttf`,
`b6c86e3d95f557dae9a7d59a993b5a6f2274c401d6d975747d43612c6fab236d`) and COLRv0 (the same path with `glyf_colr_0`,
`8376fee074649ece235faba0d157d851f3d93632f0540ad1165feba1aba9ba37`), and Blobmoji2 17r1 (COLRv1,
`https://raw.githubusercontent.com/DavidBerdik/blobmoji2/blobmoji-17r1/fonts/Noto-COLRv1.ttf`,
`be12a85b4ac069ef07541e861f96e99710ad4f5264600aff2dd396a640dbfb7f`). An internal dev-time driver merged each pair with
`EmojiMerge.merge`, then the checks of `check` ran with Unicode 18.0's emoji-test.txt
(`https://www.unicode.org/Public/18.0.0/emoji/emoji-test.txt`, dated 2026-04-30, `8f3735cda1f92a779d78af67cf86066bb1f07143dc22f2ac29394d9bc57ab21a`).

* All 15 merged fonts: HarfBuzz shaped all 5,244 emoji-test sequences (component, fully-, minimally- and unqualified) as the emoji font
  alone or, for the 6 to 13 unqualified ones whose text form wins, as the text font alone, with no difference; every emoji character alone,
  with U+FE0F and with U+FE0E (1,436 to 1,873 per emoji font), every text character (2,163 to 3,093 per text font) and 270 text runs
  matched; emoji advances were the emoji font's times s in every case.
* Sequences in 12 scripts: 1,908 per merged font, no difference. With `rlig` the merged font shapes them as the emoji font alone shapes
  them with no script; the emoji font alone (its lookups in `ccmp`) gives other glyphs in Khmer for about 100 of them.
* FreeType: every COLR v1 paint graph and clip box sampled (46 or 47 per font) and every COLR v0 layer list and rendering sampled matched.
  HarfBuzz raster at 128 px: no glyph off, except two OpenMoji COLRv0 glyphs merged into Noto Sans (s = 0.9765625, where rounding the
  scaled outlines moves edges by up to one unit: 25 and 45 pixels with a channel difference of 36 and 40).
* Merging took 56 to 398 ms per pair on a desktop JVM (including writing the font; the slowest is Lato + Noto, 42,330 emoji glyphs, 5.7
  MB). Lato + Noto and Noto Sans + Blobmoji2 merge in a 40 MB heap and not in 32 MB.

Not run: on a device. The suite and the checks above ran on the JDK and on a desktop with the tools named here only.
