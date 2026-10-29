# Fonts and expected values for the `fontbuilder` suite

`tests/java/src/FontBuilderTest.java` checks `FontBuilder` (design section 3.9), which makes the fonts of one FlipFont package from the
fonts picked on the Custom Fonts page: each face made static at its location (`FontInstancer`), PostScript outlines converted
(`CffToGlyf`), the emoji font merged in (`EmojiMerge`), then renamed to the display name with the Regular or Bold style bits, written,
read back and every glyph decoded. CI has only a JDK, so what fontTools, HarfBuzz and FreeType say about the built fonts was worked out at
dev time by `make_fixtures.py` and written to `expected.txt`.

## Files

| File | Size | Glyphs | Made from | Version | SHA-256 of the source | License |
|---|---|---|---|---|---|---|
| `Inter-var.ttf` | 64,312 | 113 | `https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf` (variable: opsz, wght, avar, HVAR, MVAR) | 4.001;git-66647c0bb | `29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031` | OFL 1.1, `OFL.txt` |
| `FixtureSans3-Regular.otf` | 18,696 | 110 | `https://raw.githubusercontent.com/adobe-fonts/source-sans/release/OTF/SourceSans3-Regular.otf` (CFF) | 3.052 | `08df266400933d3178d081a45f94a08814c3e55b4b7dd2e0ff69cb1329f13ab6` | OFL 1.1, `OFL.txt` (renamed Fixture Sans 3: 'Source' is a Reserved Font Name) |
| `FixtureSans3-Bold.otf` | 19,232 | 110 | `https://raw.githubusercontent.com/adobe-fonts/source-sans/release/OTF/SourceSans3-Bold.otf` (CFF) | 3.052 | `7776ddb9f3eb58683e59f28d558d8896b768c2d0c80799fb3f1c56c54dfd98c9` | OFL 1.1, `OFL.txt` (renamed, as above) |
| `FixtureSans3VF.otf` | 29,360 | 110 | `https://raw.githubusercontent.com/adobe-fonts/source-sans/release/VF/SourceSans3VF-Upright.otf` (CFF2, wght 200-900, avar) | 3.052 | `3d0dfd6a3a644ab3d462a737923ffac41fb0ae007ce9ba83c24e6bfa76aa56c7` | OFL 1.1, `OFL.txt` (renamed Fixture Sans 3 VF) |
| `NotoSans-Regular.ttf` | 22,884 | 114 | `https://raw.githubusercontent.com/notofonts/notofonts.github.io/main/fonts/NotoSans/hinted/ttf/NotoSans-Regular.ttf` (static, ttfautohint) | 2.015 | `478c558ea716033cd60c03438f628dfa75694dcf6b5f6d505a2f05fd2b4f3823` | OFL 1.1, `OFL.txt` |
| `NotoSans-Italic.ttf` | 25,436 | 120 | `https://raw.githubusercontent.com/notofonts/notofonts.github.io/main/fonts/NotoSans/hinted/ttf/NotoSans-Italic.ttf` (static, ttfautohint) | 2.015 | `467e3f89eeca4108bb8710a2b9e0cf2281ac56d5b0609211a83776d0505eecb5` | OFL 1.1, `OFL.txt` |
| `Twemoji-sub.ttf` | 16,720 | 147 | `fonts/twemoji.ttf` in `https://github.com/SableClient/twemoji-font/releases/download/v1.0.4/twemoji-font-1.0.4.zip` (zip `d4e9b55a4e57dcedeab2b024d790a10d59656a21804957aefb9a3cccec865bd2`; COLRv0, hinted) | 1.0.4 | `d2d721abc0ab04c0f40673e4b521e8b580a6b31d1018b7f2dcb438fdae4c265e` | code Apache-2.0 (Mozilla Foundation), art CC-BY-4.0 (Twemoji), `Twemoji-LICENSE.md` (the release's `LICENSE.md`) |
| `Noto-COLRv1-sub.ttf` | 27,420 | 202 | `https://raw.githubusercontent.com/googlefonts/noto-emoji/v2026-09-24-unicode18_0/2D/fonts/Noto-COLRv1.ttf` (COLRv1) | 2.057 | `b8e25ea68db82f9e4d0aee921f4420be2be39887bd5c893a2ad98710531f9d0c` | OFL 1.1, `OFL.txt` |
| `expected.txt` | | | written by `make_fixtures.py expect` from the suite's `--dump` | | | |
| `make_fixtures.py` | | | the script that made the fonts and `expected.txt`, and checks whole packages (dev time only) | | | |
| `OFL.txt` | | | the copyright notices of the OFL fonts above and the OFL 1.1 text | | | |
| `Twemoji-LICENSE.md` | | | the Twemoji font release's license | | | |

The SHA-256 of each fixture is in `expected.txt` (`source` lines); the suite notes a fixture that changed, and its line-by-line checks
then fail until `expect` is run again.

Each font is a `fontTools.subset` of its source with the hinting and every name kept: the text fonts to printable ASCII, the signs both
kinds of font map (U+00A9, U+00AE, U+2122), a few accented letters, dashes and quotes, with the `ccmp locl liga kern mark mkmk rvrn`
features (all of Inter's would pull in 500 glyphs); the emoji fonts to the suite's sequences (a face, a skin tone, a ZWJ family, a flag,
two keycaps, a heart and a smiley with U+FE0F, the copyright sign) with every feature. DSIG is dropped (the suite adds one to the italic
case to see it go) and head.modified is fixed, so `fonts` makes the same files again.

## The cases

| Case | Regular | Bold | Emoji | What it covers |
|---|---|---|---|---|
| `var` | `Inter-var.ttf`, where `FontInstancer.guessRegular` puts it (wght 400, opsz 14) | the same array at wght 700 | | one variable file giving both faces, variation tables and names 16 / 17 / 25 left out |
| `cff` | `FixtureSans3-Regular.otf` | `FixtureSans3-Bold.otf` | `Noto-COLRv1-sub.ttf` | CFF converted, COLRv1 merged into both faces, a display name outside ASCII (Café Sans) |
| `cff2` | `FixtureSans3VF.otf` at wght 300 | the same array at wght 900 | `Twemoji-sub.ttf` | CFF2 instanced and converted (one progress stage), COLRv0 merged |
| `static` | `NotoSans-Regular.ttf` | | `Twemoji-sub.ttf` | a static hinted TrueType font, no Bold |
| `italic` | `NotoSans-Italic.ttf` with a DSIG added | | | the Italic style bits, DSIG removed |
| `same` | `NotoSans-Regular.ttf` | the same file read again (an equal array) | | the "Bold no heavier" warning, one file read |

## expected.txt

`source FILE SHA` for each fixture, then one block per built face, `face CASE Regular|Bold` to `end`, of what fontTools reads in it:

* `tables`: the table tags, sorted; `glyphs`: the glyph count;
* `name P E L ID TEXT`: every name record in file order (TEXT with characters outside printable ASCII, and `\`, as `\uXXXX` or
  `\UXXXXXXXX`);
* `os2 WEIGHT FSSELECTION`, `head MACSTYLE MODIFIED`, `post FORMAT`;
* `cmap COUNT CRC`: the best subtable's mappings, `CP GID` per line; `hmtx CRC`: `ADVANCE LSB` per glyph; `glyf CRC`: one line per glyph,
  `E` (empty), `S ENDS X,Y,ON;... iN` (simple: contour ends, points, instruction length) or `C GID,DX,DY,A,B,C,D;... iN` (composite;
  `pFIRST,SECOND` for a point-matched offset; the transform in F2Dot14). CRC is the CRC-32 of the lines, each ended by `\n`, in UTF-8.

The suite computes the same lines from its own reading of each face and compares them one by one.

## Making them again

Dev time only (fontTools 4.67.0, uharfbuzz 0.57.0 with HarfBuzz 15.0.0, freetype-py with FreeType 2.13.2; run Python with `-I`, keep each
download in its own folder):

```sh
D=/tmp/fontbuilder-dl
mkdir -p "$D/inter" "$D/ss3" "$D/ss3bold" "$D/ss3vf" "$D/notosans" "$D/notoitalic" "$D/twemoji" "$D/noto"
curl -L -o "$D/inter/Inter.ttf" 'https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf'
curl -L -o "$D/ss3/SourceSans3-Regular.otf" 'https://raw.githubusercontent.com/adobe-fonts/source-sans/release/OTF/SourceSans3-Regular.otf'
curl -L -o "$D/ss3bold/SourceSans3-Bold.otf" 'https://raw.githubusercontent.com/adobe-fonts/source-sans/release/OTF/SourceSans3-Bold.otf'
curl -L -o "$D/ss3vf/SourceSans3VF-Upright.otf" 'https://raw.githubusercontent.com/adobe-fonts/source-sans/release/VF/SourceSans3VF-Upright.otf'
curl -L -o "$D/notosans/NotoSans-Regular.ttf" 'https://raw.githubusercontent.com/notofonts/notofonts.github.io/main/fonts/NotoSans/hinted/ttf/NotoSans-Regular.ttf'
curl -L -o "$D/notoitalic/NotoSans-Italic.ttf" 'https://raw.githubusercontent.com/notofonts/notofonts.github.io/main/fonts/NotoSans/hinted/ttf/NotoSans-Italic.ttf'
curl -L -o "$D/noto/Noto-COLRv1.ttf" 'https://raw.githubusercontent.com/googlefonts/noto-emoji/v2026-09-24-unicode18_0/2D/fonts/Noto-COLRv1.ttf'
curl -L -o "$D/twemoji/twemoji-font-1.0.4.zip" 'https://github.com/SableClient/twemoji-font/releases/download/v1.0.4/twemoji-font-1.0.4.zip'
(cd "$D/twemoji" && unzip -o twemoji-font-1.0.4.zip fonts/twemoji.ttf)
sha256sum "$D"/*/*.ttf "$D"/*/*.otf "$D"/twemoji/fonts/*.ttf "$D"/twemoji/*.zip
M=tests/java/fixtures/fonts/fontbuilder/make_fixtures.py
python3 -I $M fonts --inter "$D/inter/Inter.ttf" --ss3 "$D/ss3/SourceSans3-Regular.otf" --ss3bold "$D/ss3bold/SourceSans3-Bold.otf" \
  --ss3vf "$D/ss3vf/SourceSans3VF-Upright.otf" --notosans "$D/notosans/NotoSans-Regular.ttf" --notoitalic "$D/notoitalic/NotoSans-Italic.ttf" \
  --twemoji "$D/twemoji/fonts/twemoji.ttf" --noto "$D/noto/Noto-COLRv1.ttf"
mkdir -p /tmp/fontbuilder-cls && javac -encoding UTF-8 -nowarn -source 8 -target 8 -sourcepath src -cp 'libs/*' -d /tmp/fontbuilder-cls tests/java/src/FontBuilderTest.java
java -cp /tmp/fontbuilder-cls -Dfixtures=tests/java/fixtures com.bloatware.bingblop.FontBuilderTest --dump /tmp/fontbuilder-dump
python3 -I $M expect /tmp/fontbuilder-dump
```

Run `--dump` and `expect` again whenever a fixture or what the engine writes changes. Besides writing `expected.txt`, `expect` checks
every built face against its sources, and fails on a difference:

* fontTools reads every table and saves the font again without a warning;
* HarfBuzz shapes SAMPLE (`Hamburgefontsiv AVATAR To fi ffl 0123456789 (#1) café © ™`) with the same glyphs and positions as
  the source (a variable source at the face's location, where positions may differ by one unit: the static font rounds once);
* every segment end of HarfBuzz's drawing of SAMPLE's glyphs in the source (at the location) lies within 1 unit of one in the built face;
* the emoji sequences shape to the emoji font's own glyphs plus the text font's glyph count (a variation selector or ZWJ that HarfBuzz
  draws as the font's zero-width space glyph is left out on both sides: the emoji font's space alone, the text font's once merged);
* FreeType draws SAMPLE unhinted at 48 ppem with under 3 % of the pixels more than 16/255 apart from the source (under 10 % for a CFF2
  source between its masters: there FreeType's own drawing of the source is about a unit off HarfBuzz's, which the outline check
  above measures instead).

Results at the time of writing (all passed):

| Face | Outline (units) | FreeType pixels |
|---|---|---|
| var Regular / Bold | 0.00 / 0.85 | 0.27 % / 0.20 % |
| cff Regular / Bold | 0.00 / 0.00 | 2.55 % / 1.36 % (cubic to quadratic) |
| cff2 Regular (wght 300) / Bold (wght 900) | 0.60 / 0.00 | 7.60 % / 1.95 % |
| static, italic, same Regular and Bold | 0.00 | 0.00 % |

For the cff2 Regular at wght 300, HarfBuzz's own drawing of the source was the reference: the built face's points are within 0.6 units of
it, FreeType's drawing of the source within 1.4, and fontTools' `instantiateVariableFont` CFF2 instance up to 5 units off it.

`FontBuilderTest --fuzz SEED COUNT` (SEED in hex) runs only the fuzz: seeds 1 and badc0de, 15,000 builds each, took 9 s each and every
one ended in a font or a FontException with no crash inside (the suite's own 600 take under a second).

## Whole packages from full fonts (dev time)

Not part of CI. `FontBuilderTest --e2e OUT NAME REGULAR[@tag=v,...] BOLD[@tag=v,...]|- EMOJI|-` builds the fonts from full font files,
writes the package (`FontPackage.writeUnsigned`), signs it (`ApkSigner.prepare` and `signV2`) with a throwaway key from the JDK's keytool,
and records the sources in `OUT/build.txt`; `make_fixtures.py e2e OUT --aapt2 PATH --apksigner PATH` then runs `apksigner verify
--min-sdk-version 26`, `aapt2 dump badging` and `aapt2 dump xmltree`, unpacks the fonts and checks each as `expect` does. Run with
build-tools from Android 15 (aapt2 2.19-11948202, apksigner 0.9) and OpenJDK 21 on a Linux container (not a phone; timings are one cold
JVM run each with `-Xmx512m`):

| Package | Sources | Fonts | Package | Build (read, instance, convert, emoji, write) | Package + sign |
|---|---|---|---|---|---|
| (a) Inter Package | Inter 4.001 variable at wght 400 and 700 (opsz 14), no emoji | 413,756 + 414,896 bytes | 831,216 bytes | 171 ms (13, 91, -, -, 59) | 4 + 40 ms |
| (b) Lato Emoji | Lato 2.015 Regular, no Bold, Twemoji 1.0.4 (COLRv0) | 2,065,928 bytes | 2,068,322 bytes | 263 ms (25, -, -, 169, 58) | 4 + 43 ms |
| (c) Source Noto | Source Sans 3 3.052 OTF Regular and Bold (CFF), Noto Color Emoji 2.057 (COLRv1) | 5,515,208 + 5,511,936 bytes | 11,029,709 bytes | 774 ms (30, -, 78, 503, 150) | 10 + 59 ms |
| (d) Roboto Flex Slanted | Roboto Flex 3.200 at its named instances Light Italic and ExtraBold Italic (all 13 axes given), OpenMoji 17.0.0 (COLRv1) | 2,650,588 + 2,651,424 bytes | 5,304,634 bytes | 457 ms (22, 77, -, 243, 102) | 6 + 46 ms |

All four: apksigner verified the v2 signature; aapt2 read the package name (`com.monotype.android.font.<Name>_<6 hex>`), versionCode 1,
minSdkVersion 26 and targetSdkVersion 34; fontTools read every table with no warning; the names were the display name with Regular, Bold,
Italic or Bold Italic (d's slnt -10 instances are Italic and Bold Italic, fsSelection 0x0081 / 0x00a1, macStyle 2 / 3); HarfBuzz shaped
SAMPLE as the source with every point within 0.85 units of the source's, and the emoji sequences as the emoji font; FreeType's pixels were
within 0.27 % (2.55 % for the converted CFF). Not run: on a device.
