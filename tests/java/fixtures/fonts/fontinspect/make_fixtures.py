#!/usr/bin/env python3
"""Fixtures and expected values of the fontinspect Java suite (FontInspectTest). Dev time only: CI runs the suite with the JDK alone.

Needs fontTools (4.67). Run with python -I, from anywhere; the downloads stay in their own folders (paths as arguments).

  make_fixtures.py fonts --ss3it F --ss3vf F --notocolr F --twemoji F --notocbdt F
      writes the five fixture fonts next to this script: Source Sans 3 Semibold Italic (CFF) and Source Sans 3 VF Upright (CFF2, wght)
      subset to printable ASCII and renamed (their Reserved Font Name 'Source' taken out of the names they show, OFL 1.1 section 3), and
      four emoji from Noto Color Emoji COLRv1, Twemoji (COLRv0) and Noto Color Emoji CBDT with their colour tables
  make_fixtures.py expect
      writes, for every font under tests/java/fixtures/fonts (every suite's folder), what fontTools reads in the fields FontInspect.Info
      has (see block() for the line format) into expected.txt, keyed by the file's SHA-256
  make_fixtures.py check --classes CLASSES FONT...
      dev time on full fonts (not committed): runs FontInspectTest --dump on each font and compares its lines with the ones written here

The rules FontInspect adds to what fontTools reads, mirrored here: the names are picked as Sfnt.name picks them (Windows Unicode English,
then any Windows Unicode, then Unicode, then Mac English, then Windows symbol; the first of the best kind), the family and style are the
typographic ones (16 / 17) when present; weight, width, fsType and fsSelection come from OS/2 when it has 64 bytes or more, else from
head.macStyle; the characters are the best map of the cmap suite's make_fixtures.py (getBestCmap's order, then (3, 0), then (1, 0));
the colour format is COLR when it has base glyphs and a CPAL, then CBDT, sbix, SVG; the Regular / Bold guess is the named instance whose
(clamped) wght is closest to 400 / 700 (ties: nearest the defaults of the other axes, then the first), unless wght itself, clamped to
its range with the other axes at their defaults, comes closer. The warnings and their order are in README.md.
"""
import argparse, importlib.util, os, subprocess, sys
from decimal import Decimal

from fontTools.ttLib import TTFont
from fontTools import subset

HERE = os.path.dirname(os.path.abspath(__file__))
FONTS = os.path.dirname(HERE)                                   # tests/java/fixtures/fonts
EXPECTED = os.path.join(HERE, "expected.txt")
MAX_TEXT = 1000

sys.dont_write_bytecode = True                                  # no __pycache__ in the fixtures folder
_spec = importlib.util.spec_from_file_location("cmap_fixtures", os.path.join(FONTS, "cmap", "make_fixtures.py"))
cmapfx = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cmapfx)                                # best_map, fixture_fonts, file_sha, rename, save


# ---------------------------------------------------------------------------------------------------------------------------------------
# fonts

def subset_to(src, unicodes, colour):
    opts = subset.Options()
    opts.layout_features = []
    opts.name_IDs = ["*"]
    opts.name_languages = ["*"]
    opts.notdef_outline = True
    opts.hinting = True
    opts.drop_tables += ["GSUB", "GPOS", "GDEF", "BASE", "DSIG", "JSTF", "meta"] + ([] if colour else ["COLR", "CPAL", "CBDT", "CBLC", "sbix", "SVG "])
    f = TTFont(src)
    s = subset.Subsetter(opts)
    s.populate(unicodes=unicodes)
    s.subset(f)
    return f


def rename_cff(font, old, new):
    if "CFF " not in font:
        return
    cff = font["CFF "].cff
    cff.fontNames = [n.replace(old, new) for n in cff.fontNames]
    for top in cff.topDictIndex:
        for key in ("FullName", "FamilyName"):
            v = getattr(top, key, None)
            if v:
                setattr(top, key, v.replace(old, new))


ASCII = list(range(0x20, 0x7F))
EMOJI = [0x1F600, 0x1F44D, 0x2764, 0x1F3FD]                      # grinning face, thumbs up, red heart, a skin tone


def cmd_fonts(a):
    for src, out in ((a.ss3it, "FixtureSans-SemiboldIt.otf"), (a.ss3vf, "FixtureSansVF.otf")):
        f = subset_to(src, ASCII, False)
        cmapfx.rename(f, "Source", "Fixture")                     # every shown name but 0 / 7 / 13 / 14 (notices and license)
        rename_cff(f, "Source", "Fixture")
        cmapfx.save(f, os.path.join(HERE, out))
    cmapfx.save(subset_to(a.notocolr, EMOJI, True), os.path.join(HERE, "NotoColorEmoji-COLRv1.ttf"))
    cmapfx.save(subset_to(a.twemoji, EMOJI, True), os.path.join(HERE, "Twemoji-COLRv0.ttf"))
    cmapfx.save(subset_to(a.notocbdt, EMOJI[:2], True), os.path.join(HERE, "NotoColorEmoji-CBDT.ttf"))


# ---------------------------------------------------------------------------------------------------------------------------------------
# what FontInspect should say

def jtrim(s):
    """Java's String.trim(): every character up to U+0020 off both ends."""
    i, j = 0, len(s)
    while i < j and ord(s[i]) <= 0x20:
        i += 1
    while j > i and ord(s[j - 1]) <= 0x20:
        j -= 1
    return s[i:j]


def name_string(font, name_id):
    if "name" not in font:
        return ""
    best, best_score = None, -1
    for rec in font["name"].names:                              # file order: the first of the best kind wins
        if rec.nameID != name_id:
            continue
        pid, eid, lang = rec.platformID, rec.platEncID, rec.langID
        if pid == 3 and eid in (1, 10) and lang == 0x409:
            score = 5
        elif pid == 3 and eid in (1, 10):
            score = 4
        elif pid == 0:
            score = 3
        elif pid == 1 and eid == 0 and lang == 0:
            score = 2
        elif pid == 3 and eid == 0:
            score = 1
        else:
            continue
        if score > best_score:
            best, best_score = rec, score
    if best is None:
        return ""
    return jtrim(best.toUnicode(errors="replace").replace("\0", ""))


def utf16_len(s):
    return len(s.encode("utf-16-be", "surrogatepass")) // 2


def clip(s):
    s = jtrim(s)
    if utf16_len(s) <= MAX_TEXT:
        return s
    cut = s.encode("utf-16-be", "surrogatepass")[:2 * (MAX_TEXT - 1)].decode("utf-16-be", "surrogatepass")
    return jtrim(cut) + "\u2026"


def num(v):
    """A double as its exact decimal expansion, the way the suite writes it (16.16 values end within 16 places)."""
    if v == int(v) and abs(v) < 1e15:
        return str(int(v))
    s = format(Decimal(v), "f")
    return s.rstrip("0").rstrip(".") if "." in s else s


def esc(s, spaces=False):
    s = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    return s.replace(" ", "\\s") if spaces else s


def clampv(a, v):
    return max(a["min"], min(a["max"], v))


def distance(a, v):
    v = clampv(a, v)
    if v < a["def"]:
        return 0.0 if a["def"] == a["min"] else (a["def"] - v) / (a["def"] - a["min"])
    return 0.0 if a["max"] == a["def"] else (v - a["def"]) / (a["max"] - a["def"])


def guess(axes, instances, target):
    w = next((k for k, a in enumerate(axes) if a["tag"] == "wght"), -1)
    at = [a["def"] for a in axes]
    if w >= 0:
        at[w] = clampv(axes[w], float(target))
        best, best_w, best_rest = None, float("inf"), float("inf")
        for coords in instances:
            dw = abs(clampv(axes[w], coords[w]) - target)
            rest = 0.0
            for k in range(len(axes)):
                if k != w:
                    rest += distance(axes[k], coords[k])
            if dw < best_w or (dw == best_w and rest < best_rest):
                best, best_w, best_rest = coords, dw, rest
        if best is not None and best_w <= abs(at[w] - target):
            at = [clampv(axes[k], best[k]) for k in range(len(axes))]
    return at


def describe(tags, coords):
    out = []
    for t, v in zip(tags, coords):
        out.append("%s %s" % (t.strip(), str(int(v)) if v == int(v) and abs(v) < 1e9 else repr(v)))
    return ", ".join(out)


def location(tags, coords):
    seen = {}
    for t, v in zip(tags, coords):                              # a tag twice keeps the later value in the first place, as a Java map does
        seen[t] = v
    return " ".join("%s=%s" % (esc(t, True), num(v)) for t, v in seen.items())


def colour(font, warnings):
    if "COLR" in font.reader:
        try:
            colr = font["COLR"]
            if colr.version >= 1:
                t = colr.table
                v1 = t.BaseGlyphList is not None and t.BaseGlyphList.BaseGlyphCount > 0
                base = t.BaseGlyphRecordCount or 0
            else:
                v1, base = False, len(colr.ColorLayers)
            if v1 or base > 0:
                if "CPAL" not in font.reader:
                    warnings.append("nopalette")
                else:
                    return "COLRv1" if v1 else "COLRv0"
        except Exception:
            warnings.append("colordamaged")
    for tag in ("CBDT", "sbix"):
        if tag in font.reader:
            warnings.append("bitmap:" + tag)
            return tag
    if "SVG " in font.reader:
        warnings.append("svg")
        return "SVG"
    return "none"


def block(path):
    """The lines FontInspectTest writes for an Info (or the error it expects), in the same order."""
    f = TTFont(path)
    tags = set(f.reader.keys())
    if not tags & {"glyf", "CFF ", "CFF2"}:
        bitmap = next((t for t in ("CBDT", "sbix") if t in tags), None)
        return ["error bitmap_emoji" if bitmap else "error no_outlines"]
    out = []
    family = name_string(f, 16) or name_string(f, 1)
    sub = name_string(f, 17) or name_string(f, 2)
    full = name_string(f, 4) or jtrim(family + " " + sub)
    for key, value in (("family", family), ("subfamily", sub), ("fullName", full), ("postScriptName", name_string(f, 6)),
                       ("version", clip(name_string(f, 5))), ("copyright", clip(name_string(f, 0))), ("designer", clip(name_string(f, 9))),
                       ("license", clip(name_string(f, 13))), ("licenseUrl", clip(name_string(f, 14)))):
        out.append("%s %s" % (key, esc(value)))
    mac = f["head"].macStyle
    if "OS/2" in tags and len(f.reader["OS/2"]) >= 64:
        os2 = f["OS/2"]
        weight, width, fstype, fssel = os2.usWeightClass, os2.usWidthClass, os2.fsType, os2.fsSelection
    else:
        weight, width, fstype, fssel = 700 if mac & 1 else 400, 5, 0, 1 if mac & 2 else 0
    italic = bool(fssel & 1 or mac & 2)
    outlines = "TrueType" if "glyf" in tags else "CFF2" if "CFF2" in tags else "CFF"
    pid, eid, fmt, m = cmapfx.best_map(f)
    latin = len(m) > 0 and all(c in m for c in list(range(0x30, 0x3A)) + list(range(0x41, 0x5B)) + list(range(0x61, 0x7B)))
    symbol = (pid, eid) == (3, 0)
    warnings = []
    col = colour(f, warnings)
    variable = "fvar" in tags
    avar2 = "avar" in tags and len(f.reader["avar"]) >= 2 and f.reader["avar"][:2] == b"\x00\x02"
    out += ["weight %d" % weight, "width %d" % width, "fsType %d" % fstype, "italic %s" % str(italic).lower(), "outlines " + outlines,
            "glyphs %d" % f["maxp"].numGlyphs, "upem %d" % f["head"].unitsPerEm, "codepoints %d" % len(m), "latin %s" % str(latin).lower(),
            "symbol %s" % str(symbol).lower(), "color " + col, "variable %s" % str(variable).lower(), "avar2 %s" % str(avar2 and variable).lower()]
    if variable:
        fvar = f["fvar"]
        axes = []
        for a in fvar.axes:
            ax = {"tag": a.axisTag, "min": a.minValue, "def": a.defaultValue, "max": a.maxValue}
            ax["min"] = min(ax["min"], ax["def"])                 # as VarStore.Fvar.read (and HarfBuzz) mend a damaged axis
            ax["max"] = max(ax["max"], ax["def"])
            axes.append(ax)
            name = name_string(f, a.axisNameID) or a.axisTag.strip()
            out.append("axis %s %s %s %s %s %s" % (esc(a.axisTag, True), num(ax["min"]), num(ax["def"]), num(ax["max"]),
                                                   str(bool(a.flags & 1)).lower(), esc(name)))
        tagl = [a["tag"] for a in axes]
        insts = []
        for inst in fvar.instances[:1024]:
            coords = [inst.coordinates.get(t, 0.0) for t in tagl]
            insts.append(coords)
            name = name_string(f, inst.subfamilyNameID) or describe(tagl, coords)
            out.append("instance %s %s" % (location(tagl, coords), esc(name)))
        out.append("guessRegular " + location(tagl, guess(axes, insts, 400)))
        out.append("guessBold " + (location(tagl, guess(axes, insts, 700)) if "wght" in tagl else "none"))
    else:
        out += ["guessRegular none", "guessBold none"]
    if symbol:
        warnings.append("symbol")
    elif not m:
        warnings.append("nocharacters")
    elif not latin and col == "none" and "COLR" not in tags:
        warnings.append("nolatin")
    if fstype & 2:
        warnings.append("restricted")
    if avar2 and variable:
        warnings.append("avar2")
    out += ["warning " + w for w in warnings]
    return out


def cmd_expect(a):
    lines = ["# Written by make_fixtures.py expect: what fontTools reads in every fixture font, for FontInspectTest. See README.md."]
    for rel in cmapfx.fixture_fonts():
        path = os.path.join(FONTS, rel)
        lines.append("font %s %s" % (rel, cmapfx.file_sha(path)))
        lines += block(path)
        lines.append("end")
        print(rel, lines[-2] if len(lines) > 2 else "")
    with open(EXPECTED, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


def cmd_check(a):
    bad = 0
    for path in a.fonts:
        out = subprocess.run(["java", "-cp", a.classes, "com.bloatware.bingblop.FontInspectTest", "--dump", path],
                             capture_output=True, text=True, encoding="utf-8")
        got = [l for l in out.stdout.splitlines() if l and not l.startswith(("font ", "end"))]
        try:
            want = block(path)
        except Exception as e:                                  # fontTools cannot read it either
            want = ["fontTools: %s" % e]
        ok = got == want
        bad += not ok
        print("ok  " if ok else "FAIL", os.path.basename(path), "%d lines" % len(want))
        if not ok:
            for w, g in zip(want + [""] * len(got), got + [""] * len(want)):
                if w != g:
                    print("    want", w[:200])
                    print("    got ", g[:200])
            if out.stderr:
                print("   ", out.stderr[-300:])
    sys.exit(1 if bad else 0)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("fonts")
    for k in ("ss3it", "ss3vf", "notocolr", "twemoji", "notocbdt"):
        p.add_argument("--" + k, required=True)
    sub.add_parser("expect")
    p = sub.add_parser("check")
    p.add_argument("--classes", required=True)
    p.add_argument("fonts", nargs="+")
    a = ap.parse_args()
    {"fonts": cmd_fonts, "expect": cmd_expect, "check": cmd_check}[a.cmd](a)


if __name__ == "__main__":
    main()
