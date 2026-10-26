#!/usr/bin/env python3
# Dev-time only (NOT run in CI). Downloads each EmojiCatalog entry once and recomputes its exact
# size and SHA-256 (and, for Twemoji, the zip's size/hash and the extracted fonts/twemoji.ttf),
# then writes manifest.txt. The EmojiCatalog class carries these same values; the `emojicatalog`
# suite compares the class against manifest.txt so an accidental edit is caught without a network.
#
# The emoji fonts themselves are large (2-44 MB) and are NOT committed - the app downloads them at
# run time, verifying against exactly these values. Only the recomputed manifest is committed here.
#
#   python3 make_fixtures.py          # downloads every entry and rewrites manifest.txt
#
# Sources and licenses (fetched 2026-10-26):
#   noto            raw.githubusercontent.com/googlefonts/noto-emoji/v2026-09-24-unicode18_0/2D/fonts/Noto-COLRv1.ttf   OFL-1.1
#   twemoji (zip)   github.com/SableClient/twemoji-font/releases/download/v1.0.4/twemoji-font-1.0.4.zip                  code Apache-2.0, art CC-BY-4.0
#   blobmoji        raw.githubusercontent.com/DavidBerdik/blobmoji2/blobmoji-17r1/fonts/Noto-COLRv1.ttf                  OFL-1.1
#   openmoji        raw.githubusercontent.com/hfg-gmuend/openmoji/17.0.0/font/OpenMoji-color-glyf_colr_1/OpenMoji-color-glyf_colr_1.ttf   CC-BY-SA-4.0
#   openmoji-colrv0 raw.githubusercontent.com/hfg-gmuend/openmoji/17.0.0/font/OpenMoji-color-glyf_colr_0/OpenMoji-color-glyf_colr_0.ttf   CC-BY-SA-4.0
#   apple           github.com/samuelngs/apple-emoji-ttf/releases/download/v26.2.1/AppleColorEmoji.ttf                   proprietary (Apple); third-party build by samuelngs
import hashlib, io, os, urllib.request, zipfile

URLS = {
    "noto": "https://raw.githubusercontent.com/googlefonts/noto-emoji/v2026-09-24-unicode18_0/2D/fonts/Noto-COLRv1.ttf",
    "twemoji": "https://github.com/SableClient/twemoji-font/releases/download/v1.0.4/twemoji-font-1.0.4.zip",
    "blobmoji": "https://raw.githubusercontent.com/DavidBerdik/blobmoji2/blobmoji-17r1/fonts/Noto-COLRv1.ttf",
    "openmoji": "https://raw.githubusercontent.com/hfg-gmuend/openmoji/17.0.0/font/OpenMoji-color-glyf_colr_1/OpenMoji-color-glyf_colr_1.ttf",
    "openmoji-colrv0": "https://raw.githubusercontent.com/hfg-gmuend/openmoji/17.0.0/font/OpenMoji-color-glyf_colr_0/OpenMoji-color-glyf_colr_0.ttf",
    "apple": "https://github.com/samuelngs/apple-emoji-ttf/releases/download/v26.2.1/AppleColorEmoji.ttf",
}
TWEMOJI_ENTRY = "fonts/twemoji.ttf"

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "make_fixtures"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return r.read()

def line(name, data, extra=""):
    return "%s %d %s%s" % (name, len(data), hashlib.sha256(data).hexdigest(), extra)

def main():
    here = os.path.dirname(os.path.abspath(__file__))
    out = ["# Measured size and SHA-256 of each EmojiCatalog download (and, for Twemoji, the zip it comes in).",
           "# Recomputed from the live files by make_fixtures.py. CI has no network, so the",
           "# EmojiCatalog class carries these same values and the suite compares them to this record.",
           "# Format: id size sha256 [zipSize zipSha256 zipEntry]"]
    for name, url in URLS.items():
        data = get(url)
        if name == "twemoji":
            ttf = zipfile.ZipFile(io.BytesIO(data)).read(TWEMOJI_ENTRY)
            out.append(line("twemoji", ttf, " %d %s %s" % (len(data), hashlib.sha256(data).hexdigest(), TWEMOJI_ENTRY)))
        else:
            out.append(line(name, data))
    open(os.path.join(here, "manifest.txt"), "w").write("\n".join(out) + "\n")
    print("\n".join(out))

if __name__ == "__main__":
    main()
