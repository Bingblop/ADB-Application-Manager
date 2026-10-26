#!/usr/bin/env bash
# Makes the emoji samples the Custom Fonts tab shows on the rows of its Emoji fonts card (assets/emoji/<id>.ttf): for each of the four
# vector (COLR) emoji fonts the app offers, a copy cut down to the sixteen emoji of the sample, from the very file the app downloads
# (the same address, size and SHA-256 as EmojiCatalog), so the row shows the art the download brings. The iOS emoji are not here: they
# are Apple's art, which the app never ships; the tab reads them from the download's own file when asked (EmojiSpecimen).
#
#   tools/make-emoji-specimens.sh [repository root]      (needs curl, unzip, sha256sum and python3 with fontTools: pip install fonttools)
#
# Run it again when an emoji font in EmojiCatalog changes, and commit what it writes. The licences of the cut-down copies are the fonts'
# own (assets/emoji/NOTICE.txt): OFL-1.1 for Noto Color Emoji and Blobmoji, CC-BY-4.0 for Twemoji's art, CC-BY-SA-4.0 for OpenMoji.
set -euo pipefail

root="${1:-.}"
out="$root/assets/emoji"
[ -d "$root/src/com/bloatware/bingblop" ] || { echo "not the repository root: $root" >&2; exit 2; }
command -v pyftsubset >/dev/null 2>&1 || { echo "pyftsubset is missing (pip install fonttools)" >&2; exit 2; }

# The sample: the same sixteen emoji as EmojiSpecimen.SAMPLE and FNT_EMO_SAMPLE in assets/index.html (FontLibraryTest checks all three).
SAMPLE='😀😂🥰😎🥳😭👍👋❤️🔥🎉🐶🦄🍕🚀🌈'

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$out"

# id, address, SHA-256 of the download, the font inside a zip (or "-")
fetch() {
  local id="$1" url="$2" sha="$3" entry="$4" f="$work/$1.download"
  curl -fsSL --retry 3 -o "$f" "$url"
  echo "$sha  $f" | sha256sum -c --quiet - || { echo "FAIL $id: the download's SHA-256 is not the pinned one" >&2; exit 1; }
  if [ "$entry" != "-" ]; then
    unzip -p "$f" "$entry" > "$work/$id.ttf"
  else
    mv "$f" "$work/$id.ttf"
  fi
}

fetch noto "https://raw.githubusercontent.com/googlefonts/noto-emoji/v2026-09-24-unicode18_0/2D/fonts/Noto-COLRv1.ttf" \
  b8e25ea68db82f9e4d0aee921f4420be2be39887bd5c893a2ad98710531f9d0c -
fetch twemoji "https://github.com/SableClient/twemoji-font/releases/download/v1.0.4/twemoji-font-1.0.4.zip" \
  d4e9b55a4e57dcedeab2b024d790a10d59656a21804957aefb9a3cccec865bd2 fonts/twemoji.ttf
fetch blobmoji "https://raw.githubusercontent.com/DavidBerdik/blobmoji2/blobmoji-17r1/fonts/Noto-COLRv1.ttf" \
  be12a85b4ac069ef07541e861f96e99710ad4f5264600aff2dd396a640dbfb7f -
# OpenMoji's COLRv0 build: the same art, and a WebView older than Chrome 98 (no COLRv1) still draws it
fetch openmoji "https://raw.githubusercontent.com/hfg-gmuend/openmoji/17.0.0/font/OpenMoji-color-glyf_colr_0/OpenMoji-color-glyf_colr_0.ttf" \
  8376fee074649ece235faba0d157d851f3d93632f0540ad1165feba1aba9ba37 -
echo "d2d721abc0ab04c0f40673e4b521e8b580a6b31d1018b7f2dcb438fdae4c265e  $work/twemoji.ttf" | sha256sum -c --quiet - \
  || { echo "FAIL twemoji: the font in the zip is not the pinned one" >&2; exit 1; }

for id in noto twemoji blobmoji openmoji; do
  # every name record is kept (the copyright and the licence travel with the copy); the hinting and the layout rules of the sample stay
  pyftsubset "$work/$id.ttf" --text="$SAMPLE" --unicodes=U+FE0F --layout-features='*' --name-IDs='*' --name-languages='*' \
    --name-legacy --notdef-outline --output-file="$out/$id.ttf"
  echo "$id: $(wc -c < "$out/$id.ttf") bytes"
done
