# Fonts and expected values for the `fontpackage` suite

`tests/java/src/FontPackageTest.java` checks `FontPackage` (the Samsung FlipFont package the Custom Fonts tab builds: display name rules,
suffix and package name, versionCode bumps, the FlipFont xml, the binary AndroidManifest.xml and the zip) and signs the packages with
`ApkSigner.prepare` + `ApkSigner.signV2` and a throwaway key. CI has only a JDK (and, in `tests.yml`, build-tools 35.0.0 on `PATH`), so
what aapt2 says about the manifests was worked out at dev time by `make_fixtures.py` and written to `expected.txt`. When `apksigner` and
`aapt2` are found (`APKSIGNER` / `AAPT2`, `PATH`, or `ANDROID_HOME` build-tools), the suite also runs them on the packages it signs;
otherwise those checks print `SKIP`.

## Files

| File | Size | Made from | Version | SHA-256 of the source | License |
|---|---|---|---|---|---|
| `Inter-Regular-sub.ttf` | 18,308 | `https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf`: static instance at wght 400 (opsz at its default 14), printable ASCII (101 glyphs), hinting dropped | 4.001;git-66647c0bb | `29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031` | OFL 1.1 (no Reserved Font Name), `OFL.txt` |
| `Inter-Bold-sub.ttf` | 18,412 | the same font at wght 700 | 4.001;git-66647c0bb | the same | OFL 1.1, `OFL.txt` |
| `OFL.txt` | 4,377 | `https://raw.githubusercontent.com/google/fonts/main/ofl/inter/OFL.txt` | | `5b9321a4298cfeb6b34354164a1c3afc3db114569984c502b9b35d988fd58c57` | |
| `expected.txt` | | written by `make_fixtures.py verify` | | | |
| `make_fixtures.py` | | the script that made the fonts and `expected.txt` (dev time only) | | | |

The package does not read the fonts (it only checks their first bytes); real fonts make the sample packages real ones.

## expected.txt

* `aapt2 VERSION`: the aapt2 that printed the two blocks below; the suite compares a live aapt2's exact text only when its version is this
  one (another version prints `SKIP` for those two checks, the other aapt2 checks still run);
* `font NAME SHA-256`: the fixture fonts;
* `manifest ID SHA-256`: for each manifest case of the suite (`CASES` in the test, plus `sample`), the SHA-256 of the AndroidManifest.xml
  `FontPackage.manifest` makes. `verify` records a hash only when those bytes are identical to what `aapt2 link` compiles from the same
  text manifest (labels and version names escaped for aapt2), so CI checks byte equality with aapt2 without the SDK. The cases cover XML
  specials, labels equal to attribute or element names, `@` / `?` at the start, RTL, CJK, emoji, a 64-character label, control and bidi
  characters (cleaned first), the largest versionCode, and a pair that sorts differently by code point and by UTF-16 unit;
* `xmltree-begin` ... `xmltree-end`: `aapt2 dump xmltree --file AndroidManifest.xml` of the signed sample (display name
  `Inter & "Friends" <b>Ü</b> 'x' 漢字 😀`, plus a bidi override and a line break that are cleaned out), which the suite's own binary XML
  reader must render identically;
* `badging-begin` ... `badging-end`: `aapt2 dump badging` of the signed sample.

`verify` also checks, without recording anything: `apksigner verify --verbose --min-sdk-version 26` and `apksigner verify --verbose` (which
reads minSdkVersion from the manifest with apksigner's own parser) on a Regular + Bold and a Regular-only package; `aapt2 dump badging /
xmltree` and `aapt dump badging / xmltree` (aapt 1) read them; no classes.dex and no resources.arsc; the fonts are stored, 4-byte aligned and
load in fontTools; the FlipFont xml parses with ElementTree and its display name is the cleaned name. `zipalign -c -v 4` also passed on the
unsigned and signed samples (run by hand, not in the script).

aapt 1's badging prints `application: label=''` for these packages (it looks the label up through a resource table, and there is none);
aapt2's badging and both xmltree dumps show the literal label.

## Making them again

Dev time only (fontTools 4.67; Android build-tools 35.0.0 and platform 34; run Python with `-I`, keep the download in its own folder):

```sh
D=/tmp/fontpackage-dl
mkdir -p "$D/inter"
curl -L -o "$D/inter/Inter.ttf" 'https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf'
sha256sum "$D/inter/Inter.ttf"
python3 -I tests/java/fixtures/fonts/fontpackage/make_fixtures.py fonts --inter "$D/inter/Inter.ttf"
CP=$(ls libs/*.jar | tr '\n' ':')
mkdir -p /tmp/fontpackage-cls && javac -encoding UTF-8 -nowarn -source 8 -target 8 -sourcepath src:tests/java/stubs -cp "$CP" -d /tmp/fontpackage-cls tests/java/src/FontPackageTest.java
java -cp "/tmp/fontpackage-cls:$CP" com.bloatware.bingblop.FontPackageTest --dump /tmp/fontpackage-dump
BT="$ANDROID_HOME/build-tools/35.0.0"
python3 -I tests/java/fixtures/fonts/fontpackage/make_fixtures.py verify /tmp/fontpackage-dump --aapt2 "$BT/aapt2" --aapt "$BT/aapt" \
  --apksigner "$BT/apksigner" --android-jar "$ANDROID_HOME/platforms/android-34/android.jar"
```

Run `--dump` and `verify` again whenever `FontPackage.manifest` changes what it writes or a case is added: the suite fails on a manifest
hash that is missing or no longer matches until `verify` has compared the new bytes with aapt2's. `FontPackageTest --fuzz SEED COUNT` runs
only the fuzz, with another seed or for longer.

Not run: on a device (no phone installed or applied one of these packages; Samsung's FlipFont reader was not checked).
