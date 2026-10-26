#!/usr/bin/env python3
"""Fixtures and expected values of the cmap Java suite (CmapTest), and EmojiData's table. Dev time only: CI runs the suite with the JDK alone.

Needs fontTools (4.67) and uharfbuzz. Run with python -I, from anywhere; the downloads stay in their own folders (paths as arguments).

  make_fixtures.py fonts --lato F --inter F --twemoji F --notoemoji F
      writes the five fixture fonts next to this script: Lato, Inter and Twemoji keep their original cmap table byte for byte (and their
      glyph count), Noto Color Emoji is subset to the glyphs its cmap reaches (all its characters and variation sequences, cmap rebuilt by
      fontTools), Symbol-cmap.ttf is a few Lato glyphs behind a Windows symbol (3, 0) map and a Mac (1, 0) one; outlines are emptied and the
      layout, colour and variation-delta tables dropped. Lato's Reserved Font Name is taken out of the names it shows (OFL 1.1 section 3).
  make_fixtures.py emojidata FILE VARIATIONS
      reads Unicode's emoji-data.txt and emoji-variation-sequences.txt, prints EmojiData's Emoji_Presentation table and its table of the
      characters with an emoji variation sequence (U+FE0F) (Java), and records each list's hash in expected.txt
  make_fixtures.py expect
      writes, for every font under tests/java/fixtures/fonts (every suite's folder), what fontTools reads: the best map (getBestCmap's order,
      then (3, 0), then (1, 0) with Mac Roman codes made Unicode; glyph 0 and ids past the glyph count left out) and the format 14 sequences
  java ... com.bloatware.bingblop.CmapTest --dump DIR
      (the suite's dump mode) writes every cmap table the suite builds, with the map and sequences it was built from
  make_fixtures.py verify DIR
      checks each of those tables: the layout rules of formats 4, 12 and 14, fontTools reads back the map and sequences it was built from
      (format 4 a prefix of the BMP that fits, in code point order), HarfBuzz finds every glyph and variation glyph through it; then
      records the table's SHA-256 in expected.txt, which the suite compares in CI
  make_fixtures.py check --classes CLASSES FONT...
      dev time on full fonts (not committed): runs the suite's --read mode on each font and compares with fontTools; with
      CmapTest --dump DIR FONT... and verify DIR --no-record, the tables built from those fonts are checked the same way
"""
import argparse, hashlib, os, re, struct, subprocess, sys

from fontTools.ttLib import TTFont, newTable
from fontTools.ttLib.tables.DefaultTable import DefaultTable
from fontTools.ttLib.tables._g_l_y_f import Glyph
from fontTools.ttLib.tables._c_m_a_p import CmapSubtable
from fontTools import subset

HERE = os.path.dirname(os.path.abspath(__file__))
FONTS = os.path.dirname(HERE)                                   # tests/java/fixtures/fonts
REPO = os.path.abspath(os.path.join(FONTS, "..", "..", "..", ".."))
EXPECTED = os.path.join(HERE, "expected.txt")
KEEP = {"head", "hhea", "maxp", "hmtx", "cmap", "name", "OS/2", "post", "glyf", "loca", "fvar", "avar", "STAT"}
PREFS = [(3, 10), (0, 6), (0, 4), (3, 1), (0, 3), (0, 2), (0, 1), (0, 0), (3, 0), (1, 0)]
READABLE = {0, 4, 6, 10, 12, 13}


def mac_roman(code):
    if code < 0x80:
        return code
    if code == 0xDB:
        return 0x20AC                                               # Apple's ROMAN.TXT since Mac OS 8.5; Python's codec still says U+00A4
    return ord(bytes([code]).decode("mac_roman"))


# ---------------------------------------------------------------------------------------------------------------------------------------
# fonts

def strip(font, keep=KEEP):
    for tag in list(font.keys()):
        if tag not in keep and tag != "GlyphOrder":
            del font[tag]
    glyf = font["glyf"]
    for name in font.getGlyphOrder():
        glyf[name] = Glyph()
    font["hmtx"].metrics = {name: (500, 0) for name in font.getGlyphOrder()}
    font["post"].formatType = 3.0
    font["post"].extraNames = []
    font["post"].mapping = {}


def raw_cmap(font, src_path):
    raw = TTFont(src_path, lazy=True).reader["cmap"]
    t = DefaultTable("cmap")
    t.data = raw
    font["cmap"] = t


def rename(font, old, new):
    for rec in font["name"].names:
        if rec.nameID in (0, 7, 13, 14):
            continue                                                # copyright, trademark and license notices stay as they are
        s = rec.toUnicode()
        if old in s:
            rec.string = s.replace(old, new)


def save(font, out):
    font.recalcTimestamp = False                                    # the same inputs give the same file
    font.save(out)
    print(out, os.path.getsize(out))


def keep_original_cmap(src, out, rfn=None):
    f = TTFont(src)
    strip(f)
    raw_cmap(f, src)
    if rfn:
        rename(f, *rfn)
    save(f, out)


def subset_font(src, out, unicodes, keep_tables=None, colour=False):
    opts = subset.Options()
    opts.layout_features = []
    opts.name_IDs = ["*"]
    opts.name_languages = ["*"]
    opts.notdef_outline = True
    opts.drop_tables += ["GSUB", "GPOS", "GDEF", "DSIG", "COLR", "CPAL"] if not colour else ["GSUB", "GPOS", "GDEF", "DSIG"]
    f = TTFont(src)
    s = subset.Subsetter(opts)
    s.populate(unicodes=unicodes)
    s.subset(f)
    return f


def cmd_fonts(a):
    keep_original_cmap(a.lato, os.path.join(HERE, "Lato-cmap.ttf"), ("Lato", "Fixture L"))
    keep_original_cmap(a.inter, os.path.join(HERE, "Inter-cmap.ttf"))
    keep_original_cmap(a.twemoji, os.path.join(HERE, "Twemoji-cmap.ttf"))
    # Noto Color Emoji: every character and sequence, the glyphs renumbered (its 42,330 glyphs would make loca and hmtx 170 KB)
    src = TTFont(a.notoemoji, lazy=True)
    selectors = [s for t in src["cmap"].tables if t.format == 14 for s in t.uvsDict]       # the subsetter keeps a selector only when asked for
    f = subset_font(a.notoemoji, None, unicodes=list(src.getBestCmap().keys()) + selectors)
    strip(f)
    save(f, os.path.join(HERE, "NotoColorEmoji-cmap.ttf"))
    # a symbol font: Lato's printable ASCII behind (3, 0) at U+F020..F07E and (1, 0) format 0 at 0x20..0x7E, as Windows symbol fonts are
    f = subset_font(a.lato, None, unicodes=list(range(0x20, 0x7F)))
    best = f.getBestCmap()
    strip(f)
    rename(f, "Lato", "Fixture Symbol")
    cmap = newTable("cmap")
    cmap.tableVersion = 0
    sym = CmapSubtable.newSubtable(4)
    sym.platformID, sym.platEncID, sym.language = 3, 0, 0
    sym.cmap = {0xF000 + c: g for c, g in best.items()}
    mac = CmapSubtable.newSubtable(0)
    mac.platformID, mac.platEncID, mac.language = 1, 0, 0
    mac.cmap = dict(best)
    cmap.tables = [mac, sym]
    f["cmap"] = cmap
    save(f, os.path.join(HERE, "Symbol-cmap.ttf"))


# ---------------------------------------------------------------------------------------------------------------------------------------
# expected.txt

def read_expected():
    lines = []
    if os.path.exists(EXPECTED):
        with open(EXPECTED, encoding="utf-8") as fh:
            lines = [l.rstrip("\n") for l in fh]
    return lines


def write_expected(lines):
    head = ["# Written by make_fixtures.py (expect, emojidata, verify): what fontTools and HarfBuzz say, for CmapTest. See README.md."]
    body = [l for l in lines if l and not l.startswith("#")]
    order = {"emoji": 0, "variation": 1, "font": 2, "built": 3}
    body.sort(key=lambda l: (order.get(l.split(" ")[0], 9), l))
    with open(EXPECTED, "w", encoding="utf-8") as fh:
        fh.write("\n".join(head + body) + "\n")


def replace_lines(prefix_test, new):
    lines = [l for l in read_expected() if not prefix_test(l)]
    write_expected(lines + new)


def map_text(m):
    return "".join("%X %d\n" % (c, g) for c, g in sorted(m.items()))


def uvs_text(u):
    out = []
    for sel in sorted(u):
        for cp in sorted(u[sel]):
            g = u[sel][cp]
            out.append("%X %X D\n" % (sel, cp) if g is None else "%X %X %d\n" % (sel, cp, g))
    return "".join(out)


def sha(s):
    return hashlib.sha256(s.encode("ascii") if isinstance(s, str) else s).hexdigest()


def best_map(font):
    """(platform, encoding, format, {cp: gid}) as getBestCmap reads it, with the rules the Java side adds (see the module doc)."""
    n = font["maxp"].numGlyphs
    for pid, eid in PREFS:
        for t in font["cmap"].tables:
            if t.platformID != pid or t.platEncID != eid or t.format not in READABLE:
                continue
            m = {}
            for cp, name in t.cmap.items():
                gid = font.getGlyphID(name)
                if gid <= 0 or gid >= n:
                    continue
                if pid == 1:
                    if cp > 0xFF:
                        continue
                    cp = mac_roman(cp)
                m[cp] = gid
            return pid, eid, t.format, m
    return -1, -1, -1, {}


def uvs_of(font):
    n = font["maxp"].numGlyphs
    tabs = [t for t in font["cmap"].tables if t.format == 14]
    tabs.sort(key=lambda t: 0 if (t.platformID, t.platEncID) == (0, 5) else 1)
    if not tabs:
        return {}
    u = {}
    for sel, lst in tabs[0].uvsDict.items():
        d = {}
        for cp, name in lst:
            if name is None:
                d[cp] = None
            elif cp not in d or d[cp] is not None:
                gid = font.getGlyphID(name)
                if 0 < gid < n:
                    d[cp] = gid
        if d:
            u[sel] = d
    for sel in list(u):                                             # a pair listed both ways is "default"
        for cp, name in [(c, nm) for c, nm in tabs[0].uvsDict[sel] if nm is None]:
            u[sel][cp] = None
    return u


def fixture_fonts():
    out = []
    for root, dirs, files in os.walk(FONTS):
        dirs.sort()
        for fn in sorted(files):
            if fn.lower().endswith((".ttf", ".otf")):
                out.append(os.path.relpath(os.path.join(root, fn), FONTS).replace(os.sep, "/"))
    return out


def file_sha(path):
    with open(path, "rb") as fh:
        return hashlib.sha256(fh.read()).hexdigest()


def cmd_expect(a):
    new = []
    for rel in fixture_fonts():
        path = os.path.join(FONTS, rel)
        f = TTFont(path)
        pid, eid, fmt, m = best_map(f)
        u = uvs_of(f)
        count_u = sum(len(v) for v in u.values())
        new.append("font %s %s %d %d %d %d %s %d %s" % (rel, file_sha(path), pid, eid, fmt, len(m), sha(map_text(m)), count_u, sha(uvs_text(u))))
        print(new[-1])
    replace_lines(lambda l: l.startswith("font "), new)


def unicode_header(text):
    """The "# Version:" and "# Date:" of a Unicode data file."""
    m = re.search(r"^# Version: (\S+)", text, re.M)
    return m.group(1) if m else "?", re.search(r"^# Date: (.+)$", text, re.M).group(1)


def java_ranges(name, s, comment):
    """A Java int[] of first and last of each run of consecutive code points in s (sorted), with a comment line in front."""
    ranges = []
    for c in s:
        if ranges and ranges[-1][1] == c - 1:
            ranges[-1][1] = c
        else:
            ranges.append([c, c])
    print("    // %s: %d code points in %d ranges" % (comment, len(s), len(ranges)))
    print("    private static final int[] %s = {" % name)
    for i in range(0, len(ranges), 6):
        print("            " + " ".join("0x%04X, 0x%04X," % (r[0], r[1]) for r in ranges[i:i + 6]))
    print("    };")


def cmd_emojidata(a):
    with open(a.file, encoding="utf-8") as fh:
        text = fh.read()
    version, date = unicode_header(text)
    cps = set()
    for line in text.splitlines():
        line = line.split("#")[0].strip()
        if not line:
            continue
        rng, prop = [x.strip() for x in line.split(";")]
        if prop != "Emoji_Presentation":
            continue
        lo, _, hi = rng.partition("..")
        cps.update(range(int(lo, 16), int(hi or lo, 16) + 1))
    s = sorted(cps)
    java_ranges("PRESENTATION", s, "Emoji_Presentation=Yes from Unicode %s emoji-data.txt (%s, sha256 %s)" % (version, date, file_sha(a.file)))
    # the characters whose (c, U+FE0F) is an emoji variation sequence
    with open(a.variations, encoding="utf-8") as fh:
        vtext = fh.read()
    vversion, vdate = unicode_header(vtext)
    bases = set()
    for line in vtext.splitlines():
        line = line.split("#")[0].strip()
        if not line:
            continue
        seq = [int(x, 16) for x in line.split(";")[0].split()]
        if len(seq) == 2 and seq[1] == 0xFE0F:
            bases.add(seq[0])
    v = sorted(bases)
    print()
    java_ranges("VARIATION", v, "(c, U+FE0F) from Unicode %s emoji-variation-sequences.txt (%s, sha256 %s)" % (vversion, vdate, file_sha(a.variations)))
    replace_lines(lambda l: l.startswith("emoji ") or l.startswith("variation "),
                  ["emoji %s %d %s" % (version, len(s), sha("".join("%X\n" % c for c in s))),
                   "variation %s %d %s" % (vversion, len(v), sha("".join("%X\n" % c for c in v)))])


# ---------------------------------------------------------------------------------------------------------------------------------------
# verify

def parse_dump(path):
    glyphs, m, u, prefix = 0, {}, {}, None
    with open(path, encoding="ascii") as fh:
        for line in fh:
            w = line.split()
            if not w:
                continue
            if w[0] == "glyphs":
                glyphs = int(w[1])
            elif w[0] == "m":
                m[int(w[1], 16)] = int(w[2])
            elif w[0] == "d":
                u.setdefault(int(w[1], 16), {})[int(w[2], 16)] = None
            elif w[0] == "u":
                u.setdefault(int(w[1], 16), {})[int(w[2], 16)] = int(w[3])
    return glyphs, m, u


def check_layout(data, problems):
    """The rules readers rely on beyond what fontTools needs: sorted records, format 4's search fields, segments, closing segment."""
    ver, num = struct.unpack(">HH", data[:4])
    recs = [struct.unpack(">HHL", data[4 + 8 * i:12 + 8 * i]) for i in range(num)]
    if ver != 0 or [r[:2] for r in recs] != sorted(r[:2] for r in recs):
        problems.append("records not sorted")
    for pid, eid, off in recs:
        fmt = struct.unpack(">H", data[off:off + 2])[0]
        if fmt == 4:
            length, lang, segx2, sr, es, rs = struct.unpack(">HHHHHH", data[off + 2:off + 14])
            seg = segx2 // 2
            pow2 = 1
            while pow2 * 2 <= seg:
                pow2 *= 2
            if sr != 2 * pow2 or es != pow2.bit_length() - 1 or rs != segx2 - sr or length > 0xFFFF or lang != 0:
                problems.append("format 4 header %r" % ((length, lang, segx2, sr, es, rs),))
            a = struct.unpack(">%dH" % (4 * seg + 1), data[off + 14:off + 16 + 8 * seg])
            ends, pad, starts = a[:seg], a[seg], a[seg + 1:2 * seg + 1]
            if pad != 0 or ends[-1] != 0xFFFF or starts[-1] != 0xFFFF:
                problems.append("format 4 closing segment / pad")
            for i in range(seg):
                if starts[i] > ends[i] or (i and starts[i] <= ends[i - 1]):
                    problems.append("format 4 segment %d out of order" % i)
                    break
            if off + length > len(data):
                problems.append("format 4 length past the table")
        elif fmt == 12:
            length, lang, n = struct.unpack(">LLL", data[off + 4:off + 16])
            groups = [struct.unpack(">LLL", data[off + 16 + 12 * i:off + 28 + 12 * i]) for i in range(n)]
            if length != 16 + 12 * n or lang != 0:
                problems.append("format 12 header")
            for i, (s, e, g) in enumerate(groups):
                if s > e or (i and s <= groups[i - 1][1]):
                    problems.append("format 12 group %d out of order" % i)
                    break
        elif fmt == 14:
            length, n = struct.unpack(">LL", data[off + 2:off + 10])
            sels = [int.from_bytes(data[off + 10 + 11 * i:off + 13 + 11 * i], "big") for i in range(n)]
            if sels != sorted(set(sels)):
                problems.append("format 14 selectors out of order")


def cmd_verify(a):
    import uharfbuzz as hb
    from fontTools.fontBuilder import FontBuilder
    new, bad = [], 0
    names = sorted(fn[:-5] for fn in os.listdir(a.dir) if fn.endswith(".cmap"))
    for name in names:
        data = open(os.path.join(a.dir, name + ".cmap"), "rb").read()
        glyphs, m, u = parse_dump(os.path.join(a.dir, name + ".txt"))
        problems = []
        check_layout(data, problems)
        order = [".notdef"] + ["g%d" % i for i in range(1, glyphs)]
        fb = FontBuilder(1000, isTTF=True)
        fb.setupGlyphOrder(order)
        fb.setupCharacterMap({})
        fb.setupGlyf({g: Glyph() for g in order})
        fb.setupHorizontalMetrics({g: (500, 0) for g in order})
        fb.setupHorizontalHeader(ascent=800, descent=-200)
        fb.setupNameTable({"familyName": "Cmap Check", "styleName": "Regular"})
        fb.setupOS2()
        fb.setupPost(keepGlyphNames=False)
        raw = DefaultTable("cmap")
        raw.data = data
        fb.font["cmap"] = raw
        import io
        buf = io.BytesIO()
        fb.font.save(buf)
        blob = buf.getvalue()
        font = TTFont(io.BytesIO(blob))
        tabs = {(t.platformID, t.platEncID): t for t in font["cmap"].tables}
        gid = font.getGlyphID
        f12 = tabs.get((3, 10))
        bmp = {c: g for c, g in m.items() if c < 0xFFFF}
        f4 = {c: gid(n) for c, n in tabs[(3, 1)].cmap.items()}
        if {c: gid(n) for c, n in tabs[(0, 3)].cmap.items()} != f4:
            problems.append("(0, 3) differs from (3, 1)")
        if f12 is None:
            if f4 != m:
                problems.append("format 4 is not the map")
        else:
            if {c: gid(n) for c, n in f12.cmap.items()} != m:
                problems.append("format 12 is not the map")
            if {c: gid(n) for c, n in tabs[(0, 4)].cmap.items()} != m:
                problems.append("(0, 4) is not the map")
            keys = sorted(bmp)
            if f4 != {c: bmp[c] for c in keys[:len(f4)]}:
                problems.append("format 4 is not a prefix of the BMP map")
        f14 = tabs.get((0, 5))
        got = {}
        if f14 is not None:
            for sel, lst in f14.uvsDict.items():
                got[sel] = {c: (None if n is None else gid(n)) for c, n in lst}
        if got != u:
            problems.append("format 14 is not the sequences")
        face = hb.Face(blob)
        hf = hb.Font(face)
        hb_seen = {c: g for c, g in m.items() if hf.get_nominal_glyph(c) == g}
        if f12 is not None and len(hb_seen) != len(m):
            problems.append("HarfBuzz misses %d characters" % (len(m) - len(hb_seen)))
        if f12 is None and len(hb_seen) != len(m):
            problems.append("HarfBuzz misses %d characters" % (len(m) - len(hb_seen)))
        for sel, d in u.items():
            for c, g in d.items():
                want = m.get(c) if g is None else g
                if want is not None and hf.get_variation_glyph(c, sel) != want:
                    problems.append("HarfBuzz variation glyph of %X %X" % (c, sel))
                    break
        if problems:
            bad += 1
            print("FAIL", name, "; ".join(problems[:5]))
        else:
            print("ok  ", name, len(m), "chars", sum(len(d) for d in u.values()), "sequences", len(data), "bytes",
                  "format 4 holds %d of %d" % (len(f4), len(bmp)))
            new.append("built %s %s" % (name, sha(data)))
    if bad:
        print(bad, "tables failed: nothing recorded")
        sys.exit(1)
    if a.no_record:
        return
    replace_lines(lambda l: l.startswith("built "), new)
    print("recorded", len(new), "hashes")


# ---------------------------------------------------------------------------------------------------------------------------------------
# check (dev time, full fonts)

def cmd_check(a):
    cls = a.classes
    bad = 0
    for path in a.fonts:
        out = subprocess.run(["java", "-cp", cls, "com.bloatware.bingblop.CmapTest", "--read", path], capture_output=True, text=True)
        got = dict(l.split(" ", 1) for l in out.stdout.splitlines() if " " in l)
        f = TTFont(path)
        pid, eid, fmt, m = best_map(f)
        u = uvs_of(f)
        want = {"best": "%d %d %d" % (pid, eid, fmt), "map": "%d %s" % (len(m), sha(map_text(m))),
                "uvs": "%d %s" % (sum(len(v) for v in u.values()), sha(uvs_text(u)))}
        ok = all(got.get(k) == v for k, v in want.items())
        bad += not ok
        print("ok  " if ok else "FAIL", os.path.basename(path), want, "" if ok else got or out.stderr[-300:])
    sys.exit(1 if bad else 0)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("fonts")
    for k in ("lato", "inter", "twemoji", "notoemoji"):
        p.add_argument("--" + k, required=True)
    p = sub.add_parser("emojidata")
    p.add_argument("file")
    p.add_argument("variations")
    sub.add_parser("expect")
    p = sub.add_parser("verify")
    p.add_argument("dir")
    p.add_argument("--no-record", action="store_true", help="check only (tables of full fonts dumped with --dump DIR FONT...)")
    p = sub.add_parser("check")
    p.add_argument("--classes", required=True)
    p.add_argument("fonts", nargs="+")
    a = ap.parse_args()
    {"fonts": cmd_fonts, "emojidata": cmd_emojidata, "expect": cmd_expect, "verify": cmd_verify, "check": cmd_check}[a.cmd](a)


if __name__ == "__main__":
    main()
