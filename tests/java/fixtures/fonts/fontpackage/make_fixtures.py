#!/usr/bin/env python3
"""Fixtures and expected values of the fontpackage Java suite (FontPackageTest). Dev time only: CI runs the suite with the JDK alone.

Needs fontTools (4.67) for `fonts` and the font check in `verify`, and the Android SDK tools for `verify` (aapt2, aapt, apksigner and a
platform android.jar). Run with python -I, from anywhere:

  make_fixtures.py fonts --inter F
      makes the two fixture fonts next to this script from Inter's variable font (the download named in README.md): static instances at
      wght 400 and 700 (opsz at its default), cut down to printable ASCII, hinting dropped
  java ... com.bloatware.bingblop.FontPackageTest --dump DIR
      (the suite's dump mode) writes the manifest of every case in the suite (cases.tsv + <id>.axml) and sample packages, unsigned and
      signed with a throwaway key
  make_fixtures.py verify DIR --aapt2 P --aapt P --apksigner P --android-jar P
      checks those files: each manifest is byte for byte the one aapt2 compiles from the same text (aapt2 link), the samples pass
      `apksigner verify` (with and without --min-sdk-version 26), `aapt2 dump badging / xmltree` and `aapt dump badging / xmltree` read
      them, the fonts are stored, 4-byte aligned and load in fontTools, the FlipFont xml parses with the display name; then writes
      expected.txt (font hashes, manifest hashes, aapt2's xmltree and badging of the sample), which the suite compares in CI
"""
import argparse, hashlib, os, re, subprocess, sys, tempfile, zipfile
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
EXPECTED = os.path.join(HERE, "expected.txt")
FONTS = {"Inter-Regular-sub.ttf": 400, "Inter-Bold-sub.ttf": 700}
ANDROID_NS = "http://schemas.android.com/apk/res/android"


def sha(b):
    return hashlib.sha256(b).hexdigest()


def cmd_fonts(a):
    from fontTools.ttLib import TTFont
    from fontTools.varLib.instancer import instantiateVariableFont
    from fontTools import subset
    for name, wght in FONTS.items():
        f = TTFont(a.inter)
        f = instantiateVariableFont(f, {"wght": wght, "opsz": None}, updateFontNames=True)  # every axis pinned: a static font
        opts = subset.Options()
        opts.hinting = False
        opts.layout_features = ["kern", "liga"]
        opts.name_IDs = ["*"]
        opts.notdef_outline = True
        s = subset.Subsetter(opts)
        s.populate(unicodes=range(0x20, 0x7F))
        s.subset(f)
        out = os.path.join(HERE, name)
        f.save(out)
        print(name, os.path.getsize(out), "bytes", sha(open(out, "rb").read()))


def aapt_text(s):
    """A string as aapt2 reads an attribute value: \\ ' " escaped, a leading @ or ? escaped, then XML-escaped."""
    out = ""
    for i, c in enumerate(s):
        if c in "\\'\"" or (c in "@?" and i == 0):
            out += "\\"
        out += c
    return out.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def run(args):
    r = subprocess.run(args, capture_output=True, text=True)
    return r.returncode, "\n".join(l for l in (r.stdout + r.stderr).split("\n") if not l.startswith("Picked up "))


def cmd_verify(a):
    d = a.dir
    fails = []

    def check(name, ok, detail=""):
        print(("PASS " if ok else "FAIL ") + name + ("" if ok or not detail else " -- " + detail))
        if not ok:
            fails.append(name)

    # 1. every manifest the suite makes is what aapt2 compiles from the same text
    hashes = []
    tmp = tempfile.mkdtemp(prefix="fp-aapt2-")
    for line in open(os.path.join(d, "cases.tsv"), encoding="utf-8"):
        cid, pkg, vc, vn, label = line.rstrip("\n").split("\t")
        vn, label = bytes.fromhex(vn).decode("utf-8"), bytes.fromhex(label).decode("utf-8")
        mine = open(os.path.join(d, cid + ".axml"), "rb").read()
        text = ('<?xml version="1.0" encoding="utf-8"?>\n'
                '<manifest xmlns:android="%s" package="%s" android:versionCode="%s" android:versionName="%s">\n'
                '    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34" />\n'
                '    <application android:label="%s" android:hasCode="false" android:allowBackup="false" />\n'
                '</manifest>\n') % (ANDROID_NS, pkg, vc, aapt_text(vn), aapt_text(label))
        src = os.path.join(tmp, cid + ".xml")
        open(src, "w", encoding="utf-8").write(text)
        apk = os.path.join(tmp, cid + ".apk")
        code, out = run([a.aapt2, "link", "--manifest", src, "-I", a.android_jar, "-o", apk, "--no-compile-sdk-metadata"])
        if code:
            check("aapt2 compiles case " + cid, False, out)
            continue
        ref = zipfile.ZipFile(apk).read("AndroidManifest.xml")
        check("manifest " + cid + " is byte for byte aapt2's", mine == ref, "%d vs %d bytes" % (len(mine), len(ref)))
        if mine == ref:
            hashes.append((cid, sha(mine)))

    # 2. the sample packages
    xmltree = badging = None
    for name in ("sample.apk", "sample-regular-only.apk"):
        apk = os.path.join(d, name)
        code, out = run([a.apksigner, "verify", "--verbose", "--min-sdk-version", "26", apk])
        check(name + ": apksigner verify --min-sdk-version 26", code == 0 and out.startswith("Verifies")
              and "Verified using v2 scheme (APK Signature Scheme v2): true" in out, out)
        code, out = run([a.apksigner, "verify", "--verbose", apk])          # minSdkVersion read from the manifest by apksigner's own parser
        check(name + ": apksigner verify (minSdkVersion from the manifest)", code == 0 and out.startswith("Verifies"), out)
        code, bad = run([a.aapt2, "dump", "badging", apk])
        check(name + ": aapt2 dump badging", code == 0 and "minSdkVersion:'26'" in bad and "targetSdkVersion:'34'" in bad, bad)
        code, tree = run([a.aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk])
        check(name + ": aapt2 dump xmltree", code == 0 and "hasCode(0x0101000c)=false" in tree, tree)
        code, out = run([a.aapt, "dump", "badging", apk])
        check(name + ": aapt (v1) dump badging", code == 0 and "sdkVersion:'26'" in out and "targetSdkVersion:'34'" in out, out)
        code, out = run([a.aapt, "dump", "xmltree", apk, "AndroidManifest.xml"])
        check(name + ": aapt (v1) dump xmltree", code == 0 and "android:hasCode(0x0101000c)=(type 0x12)0x0" in out, out)
        z = zipfile.ZipFile(apk)
        names = z.namelist()
        check(name + ": no code, no resources", not any(n in names for n in ("classes.dex", "resources.arsc")))
        raw = open(apk, "rb").read()
        for info in z.infolist():
            if info.filename.startswith("assets/fonts/"):
                ln, lx = int.from_bytes(raw[info.header_offset + 26:info.header_offset + 28], "little"), int.from_bytes(raw[info.header_offset + 28:info.header_offset + 30], "little")
                start = info.header_offset + 30 + ln + lx
                check(name + ": " + info.filename + " stored and 4-byte aligned", info.compress_type == 0 and start % 4 == 0)
                from fontTools.ttLib import TTFont
                import io
                f = TTFont(io.BytesIO(z.read(info.filename)))
                check(name + ": " + info.filename + " loads in fontTools", f["maxp"].numGlyphs > 90)
            elif info.filename.startswith("assets/xml/"):
                root = ET.fromstring(z.read(info.filename))
                check(name + ": " + info.filename + " parses", root.tag == "font" and root.find("sans") is not None)
        if name == "sample.apk":
            xmltree, badging = tree, bad
            mine = open(os.path.join(d, "sample.xmltree.txt"), encoding="utf-8").read()
            check("the suite's rendering of the sample manifest is aapt2's xmltree", mine.strip("\n") == tree.strip("\n"))
            want = open(os.path.join(d, "sample.name.txt"), encoding="utf-8").read()
            root = ET.fromstring(z.read([n for n in names if n.startswith("assets/xml/")][0]))
            check("sample: displayname reads back as the cleaned name", root.get("displayname") == want)

    if fails:
        print("%d checks failed: expected.txt not written" % len(fails))
        return 1
    with open(EXPECTED, "w", encoding="utf-8") as o:
        o.write("# fontpackage suite: written by make_fixtures.py verify; the blocks are what this aapt2 printed\n")
        o.write("aapt2 %s\n" % run([a.aapt2, "version"])[1].strip())
        for name in sorted(FONTS):
            o.write("font %s %s\n" % (name, sha(open(os.path.join(HERE, name), "rb").read())))
        for cid, h in hashes:
            o.write("manifest %s %s\n" % (cid, h))
        o.write("xmltree-begin\n" + xmltree.strip("\n") + "\nxmltree-end\n")
        o.write("badging-begin\n" + badging.strip("\n") + "\nbadging-end\n")
    print("wrote", EXPECTED)
    return 0


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fonts")
    f.add_argument("--inter", required=True)
    v = sub.add_parser("verify")
    v.add_argument("dir")
    v.add_argument("--aapt2", required=True)
    v.add_argument("--aapt", required=True)
    v.add_argument("--apksigner", required=True)
    v.add_argument("--android-jar", required=True)
    a = p.parse_args()
    if a.cmd == "fonts":
        cmd_fonts(a)
        return 0
    return cmd_verify(a)


if __name__ == "__main__":
    sys.exit(main())
