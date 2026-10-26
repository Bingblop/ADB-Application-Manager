#!/usr/bin/env python3
"""Fixtures and expected values of the fontbuilder Java suite (FontBuilderTest). Dev time only: CI runs the suite with the JDK alone.

Needs fontTools (4.67), uharfbuzz (HarfBuzz 15) and freetype-py (FreeType 2.13). Run with python -I, from anywhere:

  make_fixtures.py fonts --inter F --ss3 F --ss3bold F --ss3vf F --notosans F --notoitalic F --twemoji F --noto F
      makes the fixture fonts next to this script from the downloads named in README.md, each subset to TEXT with TEXT_FEATURES (the
      text fonts) or to EMOJI with every feature (the emoji fonts), hinting and names kept; Source Sans 3 renamed Fixture Sans 3
      ('Source' is a Reserved Font Name)
  java ... com.bloatware.bingblop.FontBuilderTest --dump DIR
      (the suite's dump mode) writes the fonts the suite builds, one pair per case, and DIR/cases.txt naming each case's sources
  make_fixtures.py expect DIR
      reads those fonts with fontTools (a warning when fontTools loads or saves one fails the run) and writes expected.txt: per face the
      tables, glyph count, every name record, OS/2 usWeightClass and fsSelection, head macStyle and modified, post format, and CRC-32s of
      the cmap, hmtx and every glyph as fontTools reads them (the suite computes the same from its own reading); then checks each face
      against its sources: HarfBuzz shapes SAMPLE as the source does (a variable source at the case's location, PostScript outlines
      before the conversion) and draws its glyphs with every point within OUTLINE_TOLERANCE of the source's, the emoji sequences shape
      as the emoji font alone does (ids moved by the text font's glyph count), and FreeType draws SAMPLE's glyphs as it draws the
      source's
  java ... com.bloatware.bingblop.FontBuilderTest --e2e OUT NAME REGULAR[@tag=v,...] BOLD[@...]|- EMOJI|-
      builds a whole package from full fonts (FontBuilder, FontPackage, ApkSigner with a throwaway key) into OUT
  make_fixtures.py e2e OUT --aapt2 PATH --apksigner PATH
      checks such a package: apksigner verify, aapt2 dump badging and xmltree, the fonts in it as `expect` checks them (fontTools
      without warnings, HarfBuzz shaping and FreeType drawing against the sources named in OUT/build.txt)
"""
import argparse, binascii, hashlib, io, logging, math, os, re, subprocess, sys, zipfile

from fontTools.ttLib import TTFont
from fontTools import subset
from fontTools.misc.timeTools import timestampFromString
import uharfbuzz as hb
import freetype

HERE = os.path.dirname(os.path.abspath(__file__))
EXPECTED = os.path.join(HERE, "expected.txt")

# The text fonts: printable ASCII, the Latin-1 signs emoji fonts also map, and a few letters with marks
TEXT = "U+0020-007E,U+00A9,U+00AE,U+00E9,U+00FC,U+2122,U+2013,U+2014,U+2018,U+2019,U+201C,U+201D"
# The emoji fonts: a face, a skin tone, a ZWJ family, a flag, keycaps, a text-default heart and smiley, the signs text fonts have
EMOJI = "U+0023,U+0031,U+00A9,U+00AE,U+200D,U+20E3,U+FE0F,U+263A,U+2764,U+1F600,U+1F44D,U+1F3FD,U+1F468,U+1F469,U+1F467,U+1F1EB,U+1F1F7"
# Text fonts keep these features only (all of Inter's would pull in 500 glyphs)
TEXT_FEATURES = ["ccmp", "locl", "liga", "kern", "mark", "mkmk", "rvrn"]
SAMPLE = "Hamburgefontsiv AVATAR To fi ffl 0123456789 (#1) café © ™"
SEQUENCES = ["1F600", "1F44D 1F3FD", "1F468 200D 1F469 200D 1F467", "1F1EB 1F1F7", "23 FE0F 20E3", "31 FE0F 20E3", "2764 FE0F",
             "263A FE0F", "A9 FE0F"]
FIXED_TIME = timestampFromString("Thu Jan  1 00:00:00 2026")
# HarfBuzz draws a variable source in fractions, the static font is whole units, and an implied on-curve point (the middle of two off-curve
# ones) moves by up to half a unit more when they round
OUTLINE_TOLERANCE = 1.0

FILES = {   # fixture file: (argument, family to rename to or None, PostScript name prefix)
    "Inter-var.ttf": ("inter", None, None),
    "FixtureSans3-Regular.otf": ("ss3", "Fixture Sans 3", "FixtureSans3"),
    "FixtureSans3-Bold.otf": ("ss3bold", "Fixture Sans 3", "FixtureSans3"),
    "FixtureSans3VF.otf": ("ss3vf", "Fixture Sans 3 VF", "FixtureSans3VF"),
    "NotoSans-Regular.ttf": ("notosans", None, None),
    "NotoSans-Italic.ttf": ("notoitalic", None, None),
    "Twemoji-sub.ttf": ("twemoji", None, None),
    "Noto-COLRv1-sub.ttf": ("noto", None, None),
}
EMOJI_FILES = ("Twemoji-sub.ttf", "Noto-COLRv1-sub.ttf")


def sha(b): return hashlib.sha256(b).hexdigest()


def unicodes(spec):
    out = []
    for part in spec.split(","):
        a, _, b = part.strip()[2:].partition("-")
        out.extend(range(int(a, 16), int(b or a, 16) + 1))
    return out


# ---------------------------------------------------------------------------------------------------------------------------------
# fonts

def rename(font, family, ps):
    for rec in font["name"].names:
        if rec.nameID in (0, 7, 13, 14):
            continue                                                # copyright, trademark and license notices stay as they are
        s = rec.toUnicode()
        for a, b in (("Source Sans 3 VF", family), ("Source Sans 3", family), ("SourceSans3VF", ps), ("SourceSans3", ps)):
            s = s.replace(a, b)
        rec.string = s
        assert "Source" not in s, s
    if "CFF " in font:
        cff = font["CFF "].cff
        cff.fontNames = [n.replace("SourceSans3", ps) for n in cff.fontNames]
        top = cff.topDictIndex[0]
        for attr in ("FullName", "FamilyName"):
            if hasattr(top, attr):
                setattr(top, attr, getattr(top, attr).replace("Source Sans 3", family))


def make_fonts(a):
    for name, (arg, family, ps) in FILES.items():
        src = getattr(a, arg)
        font = TTFont(src)
        o = subset.Options()
        o.layout_features = ["*"] if name in EMOJI_FILES else TEXT_FEATURES
        o.name_IDs = ["*"]
        o.name_languages = ["*"]
        o.notdef_outline = True
        o.glyph_names = False
        o.hinting = True
        o.drop_tables += ["DSIG"]                                   # the suite adds its own to see it go
        s = subset.Subsetter(o)
        s.populate(unicodes=unicodes(EMOJI if name in EMOJI_FILES else TEXT))
        s.subset(font)
        if family:
            rename(font, family, ps)
        font["head"].modified = FIXED_TIME
        font.recalcTimestamp = False
        out = os.path.join(HERE, name)
        font.save(out)
        with open(src, "rb") as f:
            print("%-26s %7d bytes %5d glyphs  from %s (sha256 %s)" % (name, os.path.getsize(out), font["maxp"].numGlyphs, src, sha(f.read())))


# ---------------------------------------------------------------------------------------------------------------------------------
# expect: what fontTools reads in the built fonts

class Warnings(logging.Handler):
    def __init__(self):
        super().__init__(logging.WARNING)
        self.seen = []

    def emit(self, record): self.seen.append(record.getMessage())


def load_clean(path):
    """The font, read completely and saved again by fontTools; the warnings it logged on the way."""
    w = Warnings()
    log = logging.getLogger("fontTools")
    log.addHandler(w)
    try:
        font = TTFont(path, recalcBBoxes=False, recalcTimestamp=False)
        font.ensureDecompiled()
        for tag in font.keys():
            if tag != "GlyphOrder":
                font[tag]
        font.save(io.BytesIO())
        font = TTFont(path)                                         # saving recalculates some fields: read again for the values
        font.ensureDecompiled()
    finally:
        log.removeHandler(w)
    return font, w.seen


def crc(lines): return "%08x" % (binascii.crc32("".join(l + "\n" for l in lines).encode("utf-8")) & 0xFFFFFFFF)


def glyph_line(font, name):
    """A glyph as the suite writes it: E (empty), S ends points instructions, or C components instructions."""
    g = font["glyf"][name]
    if g.numberOfContours == 0:
        return "E"
    prog = len(g.program.getBytecode()) if hasattr(g, "program") and g.program else 0
    if g.isComposite():
        parts = []
        for c in g.components:
            t = getattr(c, "transform", None)
            m = [16384, 0, 0, 16384] if t is None else [round(t[0][0] * 16384), round(t[0][1] * 16384), round(t[1][0] * 16384), round(t[1][1] * 16384)]
            gid = font.getGlyphID(c.glyphName)
            at = "%d,%d" % (c.x, c.y) if hasattr(c, "x") else "p%d,%d" % (c.firstPt, c.secondPt)
            parts.append("%d,%s,%d,%d,%d,%d" % (gid, at, *m))
        return "C %s i%d" % (";".join(parts), prog)
    coords, ends, flags = g.getCoordinates(font["glyf"])
    pts = ";".join("%d,%d,%d" % (x, y, f & 1) for (x, y), f in zip(coords, flags))
    return "S %s %s i%d" % (",".join(str(e) for e in ends), pts, prog)


def escape(s): return "".join(c if 0x20 <= ord(c) < 0x7F and c != "\\" else "\\u%04x" % ord(c) if ord(c) < 0x10000 else "\\U%08x" % ord(c) for c in s)


def face_lines(font):
    order = font.getGlyphOrder()
    out = ["tables " + " ".join(sorted(t for t in font.keys() if t != "GlyphOrder")), "glyphs %d" % len(order)]
    for r in font["name"].names:
        out.append("name %d %d %d %d %s" % (r.platformID, r.platEncID, r.langID, r.nameID, escape(r.toUnicode())))
    os2 = font["OS/2"]
    out.append("os2 %d 0x%04x" % (os2.usWeightClass, os2.fsSelection))
    out.append("head %d %d" % (font["head"].macStyle, font["head"].modified))
    out.append("post %s" % ("%g" % font["post"].formatType))
    best = font.getBestCmap()
    out.append("cmap %d %s" % (len(best), crc(["%d %d" % (cp, font.getGlyphID(g)) for cp, g in sorted(best.items())])))
    hm = font["hmtx"].metrics
    out.append("hmtx %s" % crc(["%d %d" % hm[g] for g in order]))
    out.append("glyf %s" % crc([glyph_line(font, g) for g in order]))
    return out


def read_cases(dump):
    """cases.txt of a --dump: case NAME, then key=value fields (regular / bold / emoji sources, locations, the files written)."""
    cases = []
    with open(os.path.join(dump, "cases.txt"), encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if parts[0] != "case":
                continue
            c = {"name": parts[1]}
            for p in parts[2:]:
                k, _, v = p.partition("=")
                c[k] = v
            cases.append(c)
    return cases


def location(spec):
    return {} if not spec or spec == "-" else {k: float(v) for k, v in (kv.split(":") for kv in spec.split(","))}


def expect(a):
    cases = read_cases(a.dir)
    lines = ["# fontbuilder expectations: written by make_fixtures.py expect from FontBuilderTest --dump; see README.md"]
    for name in sorted(FILES):
        with open(os.path.join(HERE, name), "rb") as f:
            lines.append("source %s %s" % (name, sha(f.read())))
    problems = 0
    for c in cases:
        for face in ("Regular", "Bold"):
            out = c.get(face.lower() + "Out", "-")
            if out == "-":
                continue
            p = os.path.join(a.dir, out)
            font, warnings = load_clean(p)
            for w in warnings:
                print("FAIL %s %s: fontTools: %s" % (c["name"], face, w))
                problems += 1
            lines.append("face %s %s" % (c["name"], face))
            lines.extend(face_lines(font))
            lines.append("end")
            src = os.path.join(HERE, c[face.lower()])
            emoji = None if c.get("emoji", "-") == "-" else os.path.join(HERE, c["emoji"])
            problems += verify_face(p, src, location(c.get(face.lower() + "Loc")), emoji, "%s %s" % (c["name"], face))
    with open(EXPECTED, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("wrote", EXPECTED, "-", problems, "problems")
    return 1 if problems else 0


# ---------------------------------------------------------------------------------------------------------------------------------
# HarfBuzz and FreeType against the sources

def hb_font(path, loc=None):
    with open(path, "rb") as f:
        face = hb.Face(f.read())
    font = hb.Font(face)
    if loc:
        font.set_variations(loc)
    return font


def shape(font, text):
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(font, buf)
    return [(i.codepoint, p.x_advance, p.x_offset, p.y_offset) for i, p in zip(buf.glyph_infos, buf.glyph_positions)]


def num_glyphs(path):
    with open(path, "rb") as f:
        return hb.Face(f.read()).glyph_count


class Ends:
    """A pen for HarfBuzz's drawing: the point each segment ends on (implied on-curve points included)."""
    def __init__(self): self.ends = []
    def moveTo(self, p): self.ends.append(p)
    def lineTo(self, p): self.ends.append(p)
    def curveTo(self, *a): self.ends.append(a[-1])
    def qCurveTo(self, *a): self.ends.append(a[-1])
    def closePath(self): pass
    def endPath(self): pass


def outline_diff(built, src, loc):
    """The farthest any segment end of HarfBuzz's drawing of the source (at loc) is from the nearest one of the built font, in font
    units, over SAMPLE's characters: the instance, the cubic-to-quadratic conversion and the merge must keep every point where it was."""
    a, b = hb_font(built), hb_font(src, loc)
    worst, where = 0.0, None
    for ch in sorted(set(SAMPLE) - {" "}):
        ga, gb = a.get_nominal_glyph(ord(ch)), b.get_nominal_glyph(ord(ch))
        if not ga or not gb:
            return float("inf"), ch
        pa, pb = Ends(), Ends()
        a.draw_glyph_with_pen(ga, pa)
        b.draw_glyph_with_pen(gb, pb)
        if not pb.ends:
            continue
        if not pa.ends:
            return float("inf"), ch
        d = max(min(math.hypot(p[0] - q[0], p[1] - q[1]) for q in pa.ends) for p in pb.ends)
        if d > worst:
            worst, where = d, ch
    return worst, where


def verify_face(built, src, loc, emoji, label, quiet=False):
    """HarfBuzz and FreeType on the built face against its sources; prints and counts what differs."""
    bad = 0
    mine, ref = shape(hb_font(built), SAMPLE), shape(hb_font(src, loc), SAMPLE)
    ids_ok = [g[0] for g in mine] == [g[0] for g in ref]
    # A static instance rounds each advance and offset once; HarfBuzz on the variable font rounds its own sum: 1 unit apart at most
    tol = 1 if loc else 0
    pos_ok = ids_ok and all(abs(m[k] - r[k]) <= tol for m, r in zip(mine, ref) for k in (1, 2, 3))
    if not (ids_ok and pos_ok):
        print("FAIL %s: HarfBuzz shapes the sample unlike the source:\n  %s\n  %s" % (label, mine, ref))
        bad += 1
    if emoji:
        n = num_glyphs(src)
        efont, mfont = hb_font(emoji), hb_font(built)
        # HarfBuzz draws a variation selector or ZWJ left over as the font's space glyph with no advance: the emoji font's space alone, the
        # text font's once merged (or nothing, when a text-to-emoji ligature took the selector in): those are left out on both sides
        espace, mspace = efont.get_nominal_glyph(0x20), mfont.get_nominal_glyph(0x20)
        for seq in SEQUENCES:
            text = "".join(chr(int(h, 16)) for h in seq.split())
            got = [g[0] for g in shape(mfont, text) if not (g[0] == mspace and g[1] == 0)]
            want = [g[0] + n for g in shape(efont, text) if not (g[0] == espace and g[1] == 0)]
            if got != want:
                print("FAIL %s: %s shapes to %s, the emoji font's glyphs are %s" % (label, seq, got, want))
                bad += 1
    far, ch = outline_diff(built, src, loc)
    if far > OUTLINE_TOLERANCE:
        print("FAIL %s: a point of %r is %.2f units from where HarfBuzz draws it in the source" % (label, ch, far))
        bad += 1
    diff = raster_diff(built, src, loc)
    # FreeType's own drawing of a CFF2 font between masters is about a unit off HarfBuzz's (and fontTools' instance up to 5): there the
    # outline check above is the measure, and the raster one only a coarse one
    cff2 = loc and "CFF2" in TTFont(src)
    if diff > (0.10 if cff2 else 0.03):
        print("FAIL %s: FreeType draws the sample %.1f%% unlike the source" % (label, 100 * diff))
        bad += 1
    if not quiet:
        print("%s %s: HarfBuzz %s, points within %.2f units of the source's, FreeType %.2f%% of pixels differ by more than 16/255%s" % (
              "ok  " if not bad else "BAD ", label, "same glyphs and positions" if ids_ok and pos_ok else "differs", far, 100 * diff,
              ", emoji sequences as the emoji font" if emoji else ""))
    return bad


def raster(path, loc, text, ppem=48):
    face = freetype.Face(path)
    if loc:
        axes = [a.tag for a in face.get_variation_info().axes]
        face.set_var_design_coords([loc.get(t, a.default) for t, a in zip(axes, face.get_variation_info().axes)])
    face.set_pixel_sizes(0, ppem)
    out = {}
    for ch in text:
        if ch == " " or face.get_char_index(ord(ch)) == 0:
            continue
        face.load_char(ch, freetype.FT_LOAD_RENDER | freetype.FT_LOAD_NO_HINTING)
        b = face.glyph.bitmap
        out[ch] = (face.glyph.bitmap_left, face.glyph.bitmap_top, b.width, b.rows, bytes(b.buffer))
    return out


def raster_diff(built, src, loc):
    """The share of pixels more than 16/255 apart over the sample's characters, both drawn unhinted at 48 ppem."""
    a, b = raster(built, None, SAMPLE), raster(src, loc, SAMPLE)
    total = differ = 0
    for ch in set(a) | set(b):
        if ch not in a or ch not in b:
            differ += 1000
            total += 1000
            continue
        pa, pb = canvas(a[ch]), canvas(b[ch])
        keys = set(pa) | set(pb)
        total += len(keys)
        differ += sum(1 for k in keys if abs(pa.get(k, 0) - pb.get(k, 0)) > 16)
    return differ / max(total, 1)


def canvas(glyph):
    left, top, w, h, buf = glyph
    return {(left + x, top - y): buf[y * w + x] for y in range(h) for x in range(w) if buf[y * w + x]}


# ---------------------------------------------------------------------------------------------------------------------------------
# e2e: a whole package from full fonts

def e2e(a):
    bad = 0
    build = {}
    with open(os.path.join(a.dir, "build.txt"), encoding="utf-8") as f:
        for line in f:
            k, _, v = line.rstrip("\n").partition("=")
            build[k] = v
    apk = os.path.join(a.dir, "font.apk")
    v = subprocess.run([a.apksigner, "verify", "--verbose", "--min-sdk-version", "26", apk], capture_output=True, text=True)
    print("apksigner verify:", "ok" if v.returncode == 0 else "FAILED", " ".join(l for l in v.stdout.splitlines() if "v2" in l and "true" in l))
    bad += v.returncode != 0
    d = subprocess.run([a.aapt2, "dump", "badging", apk], capture_output=True, text=True)
    print("aapt2 dump badging:", "ok" if d.returncode == 0 else "FAILED", d.stdout.strip().splitlines()[:3], d.stderr.strip()[:200])
    bad += d.returncode != 0
    x = subprocess.run([a.aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk], capture_output=True, text=True)
    bad += x.returncode != 0
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        print("entries:", [(i.filename, i.compress_type, i.file_size) for i in z.infolist()])
        for face in ("Regular", "Bold"):
            src = build.get(face.lower(), "-")
            if src == "-":
                continue
            entry = [n for n in names if n.startswith("assets/fonts/") and n.endswith("-Bold.ttf") == (face == "Bold")][0]
            out = os.path.join(a.dir, "unpacked-" + face + ".ttf")
            with open(out, "wb") as f:
                f.write(z.read(entry))
            font, warnings = load_clean(out)
            for w in warnings:
                print("FAIL %s: fontTools: %s" % (face, w))
                bad += 1
            n = font["name"]
            print("%s: %s / %s / %s, weight %d, fsSelection 0x%04x, macStyle %d, %d glyphs, %d bytes" % (face, n.getDebugName(1),
                  n.getDebugName(2), n.getDebugName(6), font["OS/2"].usWeightClass, font["OS/2"].fsSelection, font["head"].macStyle,
                  font["maxp"].numGlyphs, os.path.getsize(out)))
            emoji = None if build.get("emoji", "-") == "-" else build["emoji"]
            bad += verify_face(out, src, location(build.get(face.lower() + "Loc")), emoji, face)
    print("e2e:", "PASS" if not bad else "%d problems" % bad)
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fonts")
    for arg in sorted(set(v[0] for v in FILES.values())):
        f.add_argument("--" + arg, required=True)
    e = sub.add_parser("expect")
    e.add_argument("dir")
    x = sub.add_parser("e2e")
    x.add_argument("dir")
    x.add_argument("--aapt2", required=True)
    x.add_argument("--apksigner", required=True)
    a = ap.parse_args()
    if a.cmd == "fonts":
        make_fonts(a)
        return 0
    if a.cmd == "expect":
        return expect(a)
    return e2e(a)


if __name__ == "__main__":
    sys.exit(main())
