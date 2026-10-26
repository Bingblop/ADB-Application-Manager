#!/usr/bin/env python3
# Dev-time only (NOT run in CI, which has only a JDK). Rebuilds the fonts.xml / font_fallback.xml
# fixtures the `samsungfonts` suite parses, and records in expected.txt the und-Zsye emoji font
# files each one yields. It downloads the real AOSP font configs, keeps a couple of ordinary
# families plus every <family lang="und-Zsye"> (including the ignore="true" legacy one, so the
# suite can prove it is skipped), and collapses the whitespace so each file stays a few KB.
#
#   python3 make_fixtures.py            # downloads from android.googlesource.com and rebuilds
#   python3 make_fixtures.py A B C      # uses local fonts.xml(15) font_fallback.xml(15) fonts.xml(12)
#
# Sources (Apache-2.0, see NOTICE), fetched 2026-10-26:
#   fonts15.xml    frameworks/base refs/heads/android15-release  data/fonts/fonts.xml
#   fallback15.xml frameworks/base refs/heads/android15-release  data/fonts/font_fallback.xml
#   fonts12.xml    frameworks/base refs/heads/android12-release  data/fonts/fonts.xml
import sys, re, os, base64, urllib.request

BASE = "https://android.googlesource.com/platform/frameworks/base/+/refs/heads/"
SRC = {
    "fonts15.xml": BASE + "android15-release/data/fonts/fonts.xml?format=TEXT",
    "fallback15.xml": BASE + "android15-release/data/fonts/font_fallback.xml?format=TEXT",
    "fonts12.xml": BASE + "android12-release/data/fonts/fonts.xml?format=TEXT",
}

def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return base64.b64decode(r.read()).decode("utf-8")

def families(xml):
    out = [m.group(0) for m in re.finditer(r'<family\b[^>]*>.*?</family>', xml, re.S)]
    out += [m.group(0) for m in re.finditer(r'<family\b[^>]*/>', xml)]
    return out

def trim(xml, keep_other=2):
    fams = families(xml)
    zsye = [f for f in fams if re.search(r'lang=["\'][^"\']*und-Zsye', f)]
    other = [f for f in fams if f not in zsye][:keep_other]
    body = '<?xml version="1.0" encoding="utf-8"?>\n<familyset>\n'
    for f in other + zsye:
        body += '    ' + re.sub(r'\s+', ' ', f).strip() + '\n'
    return body + '</familyset>\n'

def emoji_files(xml):
    out = []
    for f in families(xml):
        if not re.search(r'lang=["\'][^"\']*und-Zsye', f):
            continue
        if re.search(r'ignore=["\']true["\']', f):
            continue
        for mm in re.finditer(r'<font[^>]*>([^<]+)</font>', f):
            name = mm.group(1).strip()
            if re.match(r'[A-Za-z0-9][A-Za-z0-9._-]*\.(ttf|otf|ttc)$', name) and name not in out:
                out.append(name)
    return out

def main():
    here = os.path.dirname(os.path.abspath(__file__))
    args = sys.argv[1:]
    exp = []
    names = ["fonts15.xml", "fallback15.xml", "fonts12.xml"]
    for i, name in enumerate(names):
        xml = open(args[i], encoding="utf-8").read() if len(args) == 3 else fetch(SRC[name])
        trimmed = trim(xml)
        open(os.path.join(here, name), "w", encoding="utf-8").write(trimmed)
        exp.append(name + ": " + " ".join(emoji_files(trimmed)))
    open(os.path.join(here, "expected.txt"), "w", encoding="utf-8").write("\n".join(exp) + "\n")
    print("\n".join(exp))

if __name__ == "__main__":
    main()
