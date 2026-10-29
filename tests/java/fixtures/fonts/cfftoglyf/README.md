# Fonts and expected outlines for the `cfftoglyf` suite

`tests/java/src/CffToGlyfTest.java` converts each font here with `CffToGlyf.convert` and compares every glyph with the outline fontTools
draws for it through `Cu2QuPen(TTGlyphPen(glyphSet), upem / 1000, reverse_direction=True)`: contour count, on-curve points within 1 unit,
bounds within 1, signed area within 0.5 %, the advance, and the left side bearing equal to the new xMin. CI has only a JDK, so fontTools'
outlines were worked out at dev time by `make_fixtures.py` and written to the `.expect.txt` files.

## Files

| File | Size | Glyphs | Made from | Version | SHA-256 of the source | License |
|---|---|---|---|---|---|---|
| `ss3-subset.otf` | 15,712 | 169 | `https://raw.githubusercontent.com/adobe-fonts/source-sans/release/OTF/SourceSans3-Regular.otf` | 3.052 | `08df266400933d3178d081a45f94a08814c3e55b4b7dd2e0ff69cb1329f13ab6` | OFL 1.1, `OFL.txt` |
| `ss3vf-subset.otf` | 23,364 | 171 | `https://raw.githubusercontent.com/adobe-fonts/source-sans/release/VF/SourceSans3VF-Upright.otf` | 3.052 | `3d0dfd6a3a644ab3d462a737923ffac41fb0ae007ce9ba83c24e6bfa76aa56c7` | OFL 1.1, `OFL.txt` |
| `cjk-subset.otf` | 10,100 | 37 | `https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/OTF/Japanese/NotoSansCJKjp-Regular.otf` | 2.004 | `68a3fc98800b2a27b371f2fb79991daf3633bd89309d4ffaa6946fd587f375b5` | OFL 1.1, `OFL.txt` |
| `synth-cff.otf` | 5,828 | 19 | written by `make_fixtures.py` | | | made for these tests, public domain |
| `synth-cid.otf` | 5,344 | 7 | written by `make_fixtures.py` | | | made for these tests, public domain |
| `synth-cff2.otf` | 2,996 | 8 | written by `make_fixtures.py` | | | made for these tests, public domain |
| `*.expect.txt` | | | fontTools' outlines, written by `make_fixtures.py` | | | |
| `make_fixtures.py` | | | the script that made the fonts and the expected outlines (dev time only) | | | |
| `OFL.txt` | | | the copyright notices of both upstream fonts and the OFL 1.1 text (the same in `https://raw.githubusercontent.com/adobe-fonts/source-sans/release/LICENSE.md` and `https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/LICENSE`) | | | |

The subsets are `fontTools.subset` outputs with the CFF / CFF2 table untouched apart from the subsetting (subroutines kept, hinting kept,
glyph names kept; GSUB / GPOS / GDEF / BASE / DSIG / JSTF / meta dropped). 'Source' is a Reserved Font Name of Source Sans 3, so all three
subsets are renamed (name table and CFF names): CffToGlyf Fixture Sans, CffToGlyf Fixture Sans VF and CffToGlyf Fixture CJK.

What each covers:

* `ss3-subset.otf`: CFF 1, name-keyed: Basic Latin, Latin-1 and a few more letters; local and global subroutines nested 5 deep, hint
  masks, counter masks, a width in every glyph (before hstem, hstemhm, endchar or a subroutine call).
* `ss3vf-subset.otf`: CFF2 variable (wght, avar, HVAR, MVAR): blends, vsindex in charstrings, subroutines nested 8 deep; checked at the
  default (wght 200) and at wght 700.
* `cjk-subset.otf`: CFF 1, CID-keyed: 9 Font DICTs, FDSelect format 3, kana, kanji, hangul, full-width forms; VORG, vhea and vmtx.
* `synth-cff.otf`: seac (endchar with 4 and with 5 numbers), flex, hflex, hflex1, flex1 (both branches), hstemhm and hintmask with an
  implied vstem and 9 stems, cntrmask, every line and curve operator with odd and even argument counts, 1240 local subroutines (bias 1131),
  endchar and the width inside subroutines, 16.16 and 16-bit numbers, dotsection, degenerate, parallel-tangent and looping cubics, and a
  glyph drawn with the arithmetic operators (`N`) beside its literal twin (`N.lit`).
* `synth-cid.otf`: 3 Font DICTs (FDSelect format 0) with 10, 1300 and no local subroutines; one global subroutine reaches different local
  ones in different Font DICTs.
* `synth-cff2.otf`: 2 axes, 3 regions (one with an intermediate peak on both axes), 2 ItemVariationData sets, 2 Font DICTs (FDSelect
  format 4) whose Private DICTs set vsindex and blend their BlueValues, blends of 1 to 12 values (flex), blends in subroutines, a vsindex
  operator, hint and counter masks, an empty charstring; checked at 5 locations.

The rest (subroutine biases at their boundaries, FontMatrix, every limit, damaged charstrings and tables, progress and Cancel, the fuzz)
uses fonts the suite builds itself.

## The .expect.txt files

```
# a comment
loc <label> <coords> [<user>]        a section: coords are the F2Dot14 values per fvar axis ("-" for the default / a CFF 1 font),
                                     user the user-space location they come from (tag=value,...)
s gid advance contours xMin yMin xMax yMax area crc on-curve
                                     a simple glyph: area is the signed area of the quadratic outline (TrueType direction: negative for
                                     an outer contour); crc is CRC-32 of "x,y,o;" for every point (o 1 on-curve, 0 off), then "|" and the
                                     end points joined by ","; on-curve is each contour's on-curve points "x,y" joined by " ", contours
                                     joined by " / ", repeated points (consecutive, or the last equal to the first) left out
c gid advance g,dx,dy g,dx,dy        a composite glyph (seac): its components
e gid advance                        an empty glyph
```

The location is fontTools' `normalizeLocation` (avar included) rounded to F2Dot14, as a font engine reads it; the suite also checks that
`VarStore.normalize` gives the same coordinates (within 1). A glyph whose crc matches is the same point for point; the others differ only
where fontTools writes a closing point that repeats the start (blended coordinates that return to the start only after rounding).

Two places where fontTools cannot give the outline itself:

* fontTools does not run the arithmetic operators, so glyph `N` of `synth-cff.otf` gets the line of its twin `N.lit`, which draws the same
  outline with plain numbers.
* fontTools blends a CFF2 glyph with vsindex 0 unless its charstring sets one: it reads a Private DICT's vsindex only to count the regions.
  `synth-cff2.otf`'s lines are therefore drawn from a twin font whose Font DICT 1 glyphs start with `1 vsindex` (and whose Private DICT
  does not set it), which is what the font means. FreeType, which follows the Private DICT's vsindex, renders that twin like the converted
  font at all 5 locations.

## Making them again

Dev time only: Python 3 with fontTools 4.67 (run with `-I`; keep each download in its own folder):

```sh
D=/tmp/cfftoglyf-dl
mkdir -p "$D/ss3" "$D/ss3vf" "$D/cjk"
curl -L -o "$D/ss3/SourceSans3-Regular.otf" 'https://raw.githubusercontent.com/adobe-fonts/source-sans/release/OTF/SourceSans3-Regular.otf'
curl -L -o "$D/ss3vf/SourceSans3VF-Upright.otf" 'https://raw.githubusercontent.com/adobe-fonts/source-sans/release/VF/SourceSans3VF-Upright.otf'
curl -L -o "$D/cjk/NotoSansCJKjp-Regular.otf" 'https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/OTF/Japanese/NotoSansCJKjp-Regular.otf'
sha256sum "$D"/*/*.otf          # compare with the table above
python3 -I tests/java/fixtures/fonts/cfftoglyf/make_fixtures.py "$D/ss3/SourceSans3-Regular.otf" "$D/ss3vf/SourceSans3VF-Upright.otf" \
    "$D/cjk/NotoSansCJKjp-Regular.otf"
```

It writes the fonts and `.expect.txt` files into this folder (or the folder given as a fourth argument) and prints which charstring
operators each font's glyphs run. The output is the same byte for byte on every run (head dates are fixed).

## Checked at dev time, not in CI

* The whole source fonts converted against fontTools the same way: Source Sans 3 Regular (2,478 glyphs), Source Sans 3 VF at the default,
  wght 700 and wght 900, Noto Sans CJK JP Regular (65,535 glyphs, 18 Font DICTs): no glyph outside the tolerances; Source Sans 3 and the CJK
  font point for point.
* FreeType 2.13.2 (freetype-py) loads and renders every glyph of every converted font without an error, and each glyph's coverage at 48
  ppem is within a few percent of FreeType's rendering of the source (the differences are the 1/1000 em curve tolerance and
  anti-aliasing). FreeType does not open `synth-cff2.otf` itself (it has no FDSelect format 4), so that check used the same font with an
  FDSelect format 3.
