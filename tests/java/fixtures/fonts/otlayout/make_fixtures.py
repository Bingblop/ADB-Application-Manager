#!/usr/bin/env python3
"""Fixtures and expected values of the otlayout Java suite (OtLayoutTest). Dev time only: CI runs the suite with the JDK alone.

Needs fontTools (4.67) and uharfbuzz. Run with python -I, from anywhere:

  make_fixtures.py fonts --inter F --notosans F --robotoflex F --notoemoji F --twemoji F
      subsets the source fonts (paths of the downloads named in README.md) into the five fixture fonts next to this script:
      layout tables kept whole for the kept glyphs, outlines emptied, gvar / HVAR / MVAR / COLR / CPAL dropped (the suite reads only
      GSUB, GPOS, GDEF, fvar and avar)
  make_fixtures.py expect
      writes expected.txt from fontTools: each table's model (lookups, features, scripts), and at each instance location the kerning,
      mark attachment, FeatureVariations result and ligature carets of fontTools' instancer (instantiateVariableFont); the kerning and
      mark evaluators used here are first checked against HarfBuzz shaping
  java ... com.bloatware.bingblop.OtLayoutTest --dump DIR
      (the suite's dump mode) writes what OtLayout makes of every fixture: rewritten, shifted, instanced and merged tables
  make_fixtures.py verify DIR
      checks those files: rewritten / merged tables shape like the originals in HarfBuzz, shifted tables are the originals with every glyph
      id (lookup index) + delta in fontTools' view, instanced tables shape like fontTools' instance within 1 unit; then records their
      SHA-256 in expected.txt, which the suite compares in CI
"""
import argparse, hashlib, io, os, random, re, struct, sys

from fontTools.ttLib import TTFont, newTable
from fontTools.ttLib.sfnt import SFNTReader, SFNTWriter
from fontTools.ttLib.tables._g_l_y_f import Glyph
from fontTools.ttLib.tables._c_m_a_p import cmap_format_4, cmap_format_12
from fontTools.misc.xmlWriter import XMLWriter
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools import subset
import uharfbuzz as hb

HERE = os.path.dirname(os.path.abspath(__file__))
EXPECTED = os.path.join(HERE, "expected.txt")

EMOJI_SET = ("U+0023,U+002A,U+0030-0039,U+00A9,U+00AE,U+200D,U+20E3,U+2640,U+2642,U+2764,U+FE0F,U+1F1E6-1F1FF,U+1F3F3,U+1F3F4,U+1F308,"
             "U+26A7,U+1F3FB-1F3FF,U+1F44D,U+1F466-1F469,U+1F9D1,U+1F91D,U+1F48B,U+1F926,U+1F9B0-1F9B3,U+E0061-E007A,U+E007F,U+263A,U+1F600,"
             "U+1F441,U+1F5E8,U+1F46A")
FIXTURES = {
    "Inter-sub.ttf": ("inter", "U+0020-007E,U+00C0-00FF,U+0300-0304,U+0306-0308,U+030A-030C,U+0323,U+0327,U+0328,U+2013,U+2014,U+2018,U+2019,U+201C,U+201D"),
    "NotoSans-sub.ttf": ("notosans", "U+0020-007E,U+00C0-00C5,U+00E0-00E5,U+0300-0304,U+0308,U+0323,U+0391-0395,U+03B1-03B5,U+0410-0415,U+0430-0435,"
                                     "U+0915-0939,U+093C-094D,U+0958-095F,U+FB01,U+FB02"),
    "RobotoFlex-sub.ttf": ("robotoflex", "U+0020-007E,U+00A2,U+20A1,U+20A6,U+20A9,U+20B1,U+20B4,U+015E-015F,U+0162-0163,U+03AE"),
    "NotoColorEmoji-sub.ttf": ("notoemoji", EMOJI_SET),
    "Twemoji-sub.ttf": ("twemoji", EMOJI_SET),
}
TEXT_FONTS = ["Inter-sub.ttf", "NotoSans-sub.ttf", "RobotoFlex-sub.ttf"]
EMOJI_FONTS = ["NotoColorEmoji-sub.ttf", "Twemoji-sub.ttf"]
# glyph id shift (shiftGlyphs) and lookup index shift (shiftLookupIndexes) tried on each fixture
DELTAS = {"Inter-sub.ttf": (3000, 40), "NotoSans-sub.ttf": (50000, 300), "RobotoFlex-sub.ttf": (65000, 7),
          "NotoColorEmoji-sub.ttf": (1073, 79), "Twemoji-sub.ttf": (698, 169)}
LOCATIONS = {"Inter-sub.ttf": ["wght=700", "wght=900", "wght=900,opsz=32"], "NotoSans-sub.ttf": ["wght=700", "wght=900,wdth=62.5"],
             "RobotoFlex-sub.ttf": ["wght=700", "wght=900", "wght=300,wdth=25", "wght=400", "opsz=8"]}
# text font (or - for none) + emoji font, as EmojiMerge combines them
MERGES = [("Inter-sub.ttf", "NotoColorEmoji-sub.ttf"), ("NotoSans-sub.ttf", "Twemoji-sub.ttf"), ("-", "Twemoji-sub.ttf")]

LATIN = ["AVATAR Tomorrow WAVE Yo To P. F, LT AV Wa Ty Vo", "àéîõü ÅÇ ñ", "á ë ộ Ǻ i̇́ ñ",
         "ffi fl office affluent ff ﬁ ﬂ", "1/2 3/4 0123456789 #1 No. 10:30", "Quick brown fox jumps over the lazy dog?!", "«Привет» ΑΒΓ αβγ",
         "क्षत्रिय क्ष त्र ज्ञ द्व र्क कि कु", "(H) [x] {y} @ & %", "$12 ¢ ₦₩₡₱₴ US$ 4¢ ŞşŢţ ή"]
FEATS = [None, {"frac": True}, {"ss01": True, "ss02": True}, {"case": True}, {"tnum": True, "zero": True}, {"dlig": True},
         {"cv01": True, "cv05": True, "cv11": True}, {"kern": False}, {"liga": False, "calt": False}, {"sups": True}, {"onum": True}]
EMOJI = ["\U0001F468‍\U0001F469‍\U0001F467‍\U0001F466", "\U0001F44D\U0001F3FD", "\U0001F1EB\U0001F1F7\U0001F1EF\U0001F1F5",
         "#️⃣ 1️⃣ *⃣", "\U0001F3F4\U000E0067\U000E0062\U000E0073\U000E0063\U000E0074\U000E007F",
         "\U0001F469‍❤️‍\U0001F48B‍\U0001F468", "\U0001F9D1\U0001F3FF‍\U0001F91D‍\U0001F9D1\U0001F3FB",
         "❤️ ☺️ ©️", "\U0001F3F3️‍\U0001F308 \U0001F3F3️‍⚧️", "\U0001F469\U0001F3FE‍\U0001F9B0",
         "\U0001F926\U0001F3FC‍♂️", "\U0001F441️‍\U0001F5E8️"]


def path(name): return os.path.join(HERE, name)


# ---------------------------------------------------------------------------------------------------------------------------------
# fonts
# ---------------------------------------------------------------------------------------------------------------------------------

def make_fonts(args):
    for out, (key, unicodes) in FIXTURES.items():
        src = getattr(args, key)
        opts = subset.Options()
        opts.layout_features = ["*"]
        opts.layout_scripts = ["*"]
        opts.notdef_outline = True
        opts.name_IDs = ["*"]
        opts.drop_tables += ["COLR", "CPAL", "CBDT", "CBLC", "sbix", "SVG "]     # their glyph closure would keep thousands of glyphs
        font = subset.load_font(src, opts)
        s = subset.Subsetter(opts)
        s.populate(unicodes=subset.parse_unicodes(unicodes))
        s.subset(font)
        for t in ("gvar", "HVAR", "MVAR", "STAT", "cvar", "fpgm", "prep", "cvt ", "gasp", "hdmx", "LTSH", "VDMX", "DSIG", "meta", "kern",
                  "vhea", "vmtx", "VVAR"):
            if t in font: del font[t]
        for g in font.getGlyphOrder(): font["glyf"][g] = Glyph()
        font["post"].formatType = 3.0
        font["name"].names = [n for n in font["name"].names if n.nameID in (0, 1, 2, 3, 4, 5, 6) or n.nameID > 255]
        font.save(path(out))
        print(out, os.path.getsize(path(out)), "bytes,", len(font.getGlyphOrder()), "glyphs")


# ---------------------------------------------------------------------------------------------------------------------------------
# models
# ---------------------------------------------------------------------------------------------------------------------------------

def params_hex(tag, fp):
    if fp is None: return "-"
    if tag == "size":
        return struct.pack(">5H", fp.DesignSize, fp.SubfamilyID, fp.SubfamilyNameID, fp.RangeStart, fp.RangeEnd).hex()
    if re.match(r"ss(0[1-9]|1[0-9]|20)$", tag):
        return struct.pack(">2H", fp.Version, fp.UINameID).hex()
    if re.match(r"cv(0[1-9]|[1-9][0-9])$", tag):
        b = struct.pack(">7H", fp.Format, fp.FeatUILabelNameID, fp.FeatUITooltipTextNameID, fp.SampleTextNameID, fp.NumNamedParameters,
                        fp.FirstParamUILabelNameID, len(fp.Character))
        return (b + b"".join(struct.pack(">I", c)[1:] for c in fp.Character)).hex()
    return "-"


def model_lines(font, tag):
    t = font[tag].table
    ext = 7 if tag == "GSUB" else 9
    out = []
    for l in t.LookupList.Lookup:
        subs = [s for s in l.SubTable if s is not None]
        typ = l.LookupType
        if typ == ext and subs: typ = subs[0].ExtSubTable.LookupType
        mfs = l.MarkFilteringSet if l.LookupFlag & 0x10 else -1
        out.append("L %d %d %d %d" % (typ, l.LookupFlag, len(subs), mfs))
    nl = len(t.LookupList.Lookup)
    for fr in t.FeatureList.FeatureRecord:
        ls = [i for i in fr.Feature.LookupListIndex if i < nl]
        out.append("F %s %s %s" % (fr.FeatureTag, ",".join(map(str, ls)) or "-", params_hex(fr.FeatureTag, fr.Feature.FeatureParams)))
    nf = len(t.FeatureList.FeatureRecord)
    for sr in t.ScriptList.ScriptRecord:
        out.append("S %s - - -" % sr.ScriptTag)
        def ls_line(lang, ls):
            req = ls.ReqFeatureIndex if ls.ReqFeatureIndex < nf else 0xFFFF
            fs = [i for i in ls.FeatureIndex if i < nf]
            return "S %s %s %d %s" % (sr.ScriptTag, lang, req, ",".join(map(str, fs)) or "-")
        if sr.Script.DefaultLangSys is not None: out.append(ls_line("*", sr.Script.DefaultLangSys))
        seen = set()
        for lr in sorted(sr.Script.LangSysRecord, key=lambda r: r.LangSysTag):
            if lr.LangSysTag in seen: continue
            seen.add(lr.LangSysTag)
            out.append(ls_line(lr.LangSysTag, lr.LangSys))
    return out


# ---------------------------------------------------------------------------------------------------------------------------------
# GPOS evaluators (HarfBuzz's rules for a two-glyph run; checked against HarfBuzz below)
# ---------------------------------------------------------------------------------------------------------------------------------

def feature_lookups(table, feature):
    """Lookups of `feature` in latn's default language system (DFLT's when there is no latn), in lookup order."""
    sl = {s.ScriptTag: s.Script for s in table.ScriptList.ScriptRecord}
    sc = sl.get("latn") or sl.get("DFLT")
    if sc is None or sc.DefaultLangSys is None: return []
    out = set()
    for fi in sc.DefaultLangSys.FeatureIndex:
        fr = table.FeatureList.FeatureRecord[fi]
        if fr.FeatureTag == feature: out |= set(fr.Feature.LookupListIndex)
    return sorted(out)


def subtables(table, li):
    l = table.LookupList.Lookup[li]
    for st in l.SubTable:
        yield st.ExtSubTable if l.LookupType == 9 else st


def xadv(v): return (getattr(v, "XAdvance", 0) or 0) if v is not None else 0


def kern(table, lookups, left, right):
    v1 = v2 = 0
    for li in lookups:
        for st in subtables(table, li):
            if st.LookupType != 2 or left not in st.Coverage.glyphs: continue
            if st.Format == 1:
                ps = st.PairSet[st.Coverage.glyphs.index(left)]
                rec = next((r for r in ps.PairValueRecord if r.SecondGlyph == right), None)
                if rec is None: continue
                v1 += xadv(rec.Value1); v2 += xadv(getattr(rec, "Value2", None))
                break
            c2 = st.ClassDef2.classDefs.get(right, 0)
            c1 = st.ClassDef1.classDefs.get(left, 0)
            if c2 == 0 or c1 >= st.Class1Count or c2 >= st.Class2Count: continue
            r = st.Class1Record[c1].Class2Record[c2]
            v1 += xadv(r.Value1); v2 += xadv(getattr(r, "Value2", None))
            break
    return v1, v2


def mark_attach(table, lookups, base, mark):
    got = None
    for li in lookups:
        for st in subtables(table, li):
            if st.LookupType != 4 or mark not in st.MarkCoverage.glyphs or base not in st.BaseCoverage.glyphs: continue
            mr = st.MarkArray.MarkRecord[st.MarkCoverage.glyphs.index(mark)]
            if mr.Class >= st.ClassCount: continue
            ba = st.BaseArray.BaseRecord[st.BaseCoverage.glyphs.index(base)].BaseAnchor[mr.Class]
            if ba is None: continue
            ma = mr.MarkAnchor
            got = (ba.XCoordinate - (ma.XCoordinate if ma else 0), ba.YCoordinate - (ma.YCoordinate if ma else 0))
            break
    return got


def shape(data, text, feats=None, script=None):
    face = hb.Face(data)
    font = hb.Font(face)
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    if script: buf.script = script
    hb.shape(font, buf, feats or {})
    return [(i.codepoint, i.cluster, p.x_advance, p.y_advance, p.x_offset, p.y_offset) for i, p in zip(buf.glyph_infos, buf.glyph_positions)]


def loc_dict(font, loc):
    full = {a.axisTag: a.defaultValue for a in font["fvar"].axes}
    for kv in loc.split(","):
        k, v = kv.split("=")
        full[k] = float(v)
    return full


def instance(name, loc):
    f = TTFont(path(name))
    inst = instantiateVariableFont(f, loc_dict(f, loc), inplace=False)
    b = io.BytesIO(); inst.save(b)
    return inst, b.getvalue()


def normalized(font, loc):
    from fontTools.varLib.models import normalizeLocation, piecewiseLinearMap
    axes = {a.axisTag: (a.minValue, a.defaultValue, a.maxValue) for a in font["fvar"].axes}
    n = normalizeLocation(loc_dict(font, loc), axes)
    if "avar" in font:
        for k, m in font["avar"].segments.items():
            if m: n[k] = piecewiseLinearMap(n[k], m)
    return [int(__import__("math").floor(n[a.axisTag] * 16384 + 0.5)) for a in font["fvar"].axes]


# ---------------------------------------------------------------------------------------------------------------------------------
# expect
# ---------------------------------------------------------------------------------------------------------------------------------

def expect(args):
    out = ["# otlayout expectations, written by make_fixtures.py (fontTools / HarfBuzz at dev time). Do not edit by hand."]
    for name in TEXT_FONTS + EMOJI_FONTS:
        font = TTFont(path(name))
        out.append("glyphs %s %d" % (name, len(font.getGlyphOrder())))
        out.append("delta %s %d %d" % ((name,) + DELTAS[name]))
        for tag in ("GSUB", "GPOS"):
            if tag not in font: continue
            out.append("model %s %s" % (name, tag))
            out += model_lines(font, tag)
            out.append("end")
    for name, locs in LOCATIONS.items():
        src = TTFont(path(name))
        cmap = src.getBestCmap()
        order = src.getGlyphOrder()
        gid = {g: i for i, g in enumerate(order)}
        letters = [c for c in list(range(0x41, 0x5B)) + list(range(0x61, 0x7B)) + [0x2C, 0x2E] if c in cmap]
        marks = [c for c in [0x300, 0x301, 0x302, 0x303, 0x304, 0x306, 0x307, 0x308, 0x30A, 0x30B, 0x30C, 0x323, 0x327, 0x328] if c in cmap]
        orig_lookups = list(src["GSUB"].table.LookupList.Lookup) if "GSUB" in src else []
        for loc in locs:
            inst, data = instance(name, loc)
            out.append("location %s %s %s" % (name, loc, ",".join(map(str, normalized(src, loc)))))
            gpos = inst["GPOS"].table
            kl, ml = feature_lookups(gpos, "kern"), feature_lookups(gpos, "mark")
            hbok = hbbad = 0
            out.append("kern %s %s" % (name, loc))
            out.append("G " + ",".join(str(gid[cmap[c]]) for c in letters))
            for a in letters:
                for b in letters:
                    v1, v2 = kern(gpos, kl, cmap[a], cmap[b])
                    if v1 or v2: out.append("K %d %d %d %d" % (gid[cmap[a]], gid[cmap[b]], v1, v2))
                    s = shape(data, chr(a) + chr(b), {"kern": True})
                    if [x[0] for x in s] == [gid[cmap[a]], gid[cmap[b]]]:
                        ok = s[0][2] - inst["hmtx"][cmap[a]][0] == v1 and s[1][2] - inst["hmtx"][cmap[b]][0] == v2
                        hbok += ok; hbbad += not ok
            out.append("end")
            out.append("marks %s %s" % (name, loc))
            out.append("B " + ",".join(str(gid[cmap[c]]) for c in letters))
            out.append("M " + (",".join(str(gid[cmap[c]]) for c in marks) or "-"))
            for a in letters:
                for m in marks:
                    at = mark_attach(gpos, ml, cmap[a], cmap[m])
                    if at is None: continue
                    out.append("A %d %d %d %d" % (gid[cmap[a]], gid[cmap[m]], at[0], at[1]))
                    s = shape(data, chr(a) + chr(m))
                    if [x[0] for x in s] == [gid[cmap[a]], gid[cmap[m]]]:
                        ok = (s[1][4] + s[0][2] - s[0][4], s[1][5] - s[0][5]) == at
                        hbok += ok; hbbad += not ok
            out.append("end")
            if hbbad: sys.exit("%s %s: the kerning / mark evaluator disagrees with HarfBuzz on %d of %d runs" % (name, loc, hbbad, hbok + hbbad))
            print(name, loc, "kerning and mark evaluator agree with HarfBuzz on", hbok, "runs")
            if orig_lookups and getattr(TTFont(path(name))["GSUB"].table, "FeatureVariations", None) is not None:
                f = TTFont(path(name))
                before = list(f["GSUB"].table.LookupList.Lookup)
                instantiateVariableFont(f, loc_dict(f, loc), inplace=True)
                g = f["GSUB"].table
                idx = {id(l): i for i, l in enumerate(before)}
                out.append("fv %s %s" % (name, loc))
                for i, fr in enumerate(g.FeatureList.FeatureRecord):
                    ls = [idx[id(g.LookupList.Lookup[k])] for k in fr.Feature.LookupListIndex]
                    out.append("F %d %s %s" % (i, fr.FeatureTag, ",".join(map(str, ls)) or "-"))
                out.append("end")
            lc = inst["GDEF"].table.LigCaretList if "GDEF" in inst else None
            if lc is not None and lc.LigGlyphCount:
                out.append("carets %s %s" % (name, loc))
                for i, lg in enumerate(lc.LigGlyph):
                    out.append("R %d %s" % (i, ",".join(("p%d" % c.CaretValuePoint) if c.Format == 2 else str(c.Coordinate) for c in lg.CaretValue)))
                out.append("end")
    for t, e in MERGES:
        out.append("merge %s %s" % (t, e))
    old = [l for l in open(EXPECTED).read().splitlines() if l.startswith("sha ")] if os.path.exists(EXPECTED) else []
    open(EXPECTED, "w").write("\n".join(out + old) + "\n")
    print("wrote", EXPECTED, len(out), "lines (kept", len(old), "sha lines: run verify after changing the fixtures)")


# ---------------------------------------------------------------------------------------------------------------------------------
# verify (the suite's --dump output)
# ---------------------------------------------------------------------------------------------------------------------------------

def inject(data, tables):
    r = SFNTReader(io.BytesIO(data))
    tags = sorted(set(r.keys()) | set(tables))
    out = io.BytesIO()
    w = SFNTWriter(out, len(tags), r.sfntVersion)
    for t in tags: w[t] = tables[t] if t in tables else r[t]
    w.close()
    return out.getvalue()


def ligature_texts(f):
    rev = {}
    for cp, g in sorted(f.getBestCmap().items(), reverse=True): rev[g] = cp
    texts = set()
    for l in f["GSUB"].table.LookupList.Lookup:
        for st in l.SubTable:
            if l.LookupType == 7: st = st.ExtSubTable
            if st.LookupType != 4: continue
            for first, ligs in st.ligatures.items():
                for lig in ligs:
                    seq = [first] + list(lig.Component)
                    if all(g in rev for g in seq): texts.add("".join(chr(rev[g]) for g in seq))
    return sorted(texts)


def texts_for(name, n=300, seed=5):
    f = TTFont(path(name))
    pool = [c for c in sorted(f.getBestCmap()) if c > 0x20]
    rnd = random.Random(seed)
    if name in EMOJI_FONTS: base = EMOJI + ligature_texts(f)
    else: base = list(LATIN)
    return base + ["".join(chr(rnd.choice(pool)) for _ in range(rnd.randint(2, 10))) for _ in range(n)]


def same_shaping(a, b, texts, feats, scripts, tol=0):
    n = bad = 0
    first = None
    for t in texts:
        for fe in feats:
            for sc in scripts:
                x, y = shape(a, t, fe, sc), shape(b, t, fe, sc)
                n += 1
                d = len(x) != len(y) or any(p[0] != q[0] or p[1] != q[1] or max(abs(p[k] - q[k]) for k in range(2, 6)) > tol for p, q in zip(x, y))
                if d:
                    bad += 1
                    first = first or (t, fe, sc, x[:6], y[:6])
    return n, bad, first


def xml(data, tag):
    f = TTFont()
    f.setGlyphOrder(["g_%05d_" % i for i in range(65536)])
    t = newTable(tag)
    t.decompile(data, f)
    f[tag] = t
    s = io.StringIO()
    t.toXML(XMLWriter(s), f)
    return s.getvalue()


def merged_font(text, emoji, gsub):
    """A font of the text font's tables (or none) + the emoji glyphs appended, emoji winning in cmap except ASCII letters and space."""
    ef = TTFont(path(emoji))
    tf = TTFont(path(text)) if text != "-" else None
    order = (tf.getGlyphOrder() if tf else [".notdef"]) + ["e%d" % i for i in range(len(ef.getGlyphOrder()))]
    N = len(order) - len(ef.getGlyphOrder())
    tables = {}
    if tf:
        r = SFNTReader(open(path(text), "rb"))
        for t in ("head", "hhea", "OS/2", "name", "GPOS", "GDEF"):
            if t in r: tables[t] = r[t]
        tm = [tf["hmtx"][g] for g in tf.getGlyphOrder()]
        tc = tf.getBestCmap()
        m = {cp: tf.getGlyphID(g) for cp, g in tc.items()}
    else:
        r = SFNTReader(open(path(emoji), "rb"))
        for t in ("head", "hhea", "OS/2", "name"): tables[t] = r[t]
        tm = [(500, 0)]
        m = {}
    for cp, g in ef.getBestCmap().items():
        if cp == 0x20 or 0x41 <= cp <= 0x5A or 0x61 <= cp <= 0x7A: continue
        m[cp] = N + ef.getGlyphID(g)
    tables["GSUB"] = gsub
    tables["maxp"] = struct.pack(">IH", 0x00005000, len(order))
    hh = bytearray(tables["hhea"]); hh[34:36] = struct.pack(">H", len(order)); tables["hhea"] = bytes(hh)
    tables["hmtx"] = b"".join(struct.pack(">Hh", *x) for x in tm + [ef["hmtx"][g] for g in ef.getGlyphOrder()])
    tables["post"] = struct.pack(">IiHhI", 0x00030000, 0, 0, 0, 0) + b"\0" * 16
    font = TTFont(); font.setGlyphOrder(order)
    cm = newTable("cmap"); cm.tableVersion = 0
    s12 = cmap_format_12(12); s12.platformID, s12.platEncID, s12.language, s12.cmap = 3, 10, 0, {c: order[g] for c, g in m.items()}
    s4 = cmap_format_4(4); s4.platformID, s4.platEncID, s4.language, s4.cmap = 3, 1, 0, {c: order[g] for c, g in m.items() if c <= 0xFFFF}
    cm.tables = [s4, s12]
    tables["cmap"] = cm.compile(font)
    out = io.BytesIO()
    w = SFNTWriter(out, len(tables), b"\x00\x01\x00\x00")
    for t in sorted(tables): w[t] = tables[t]
    w.close()
    return out.getvalue(), N, m


def caret_formats(font):
    """The CaretValue format of every ligature caret of GDEF, in order (a format 3 also needs a Device: 4 when it has none)."""
    lcl = getattr(font["GDEF"].table, "LigCaretList", None) if "GDEF" in font else None
    if lcl is None: return []
    return [4 if cv.Format == 3 and cv.DeviceTable is None else cv.Format for lg in lcl.LigGlyph for cv in lg.CaretValue]


def verify(args):
    d = args.dir
    shas = []
    def rec(name, what):
        data = open(os.path.join(d, name + "." + what), "rb").read()
        shas.append("sha %s %s %s" % (name, what, hashlib.sha256(data).hexdigest()))
        return data
    fails = 0
    def report(ok, msg):
        nonlocal fails
        print(("OK   " if ok else "FAIL ") + msg)
        fails += not ok
    for name in TEXT_FONTS + EMOJI_FONTS:
        f = TTFont(path(name))
        orig = open(path(name), "rb").read()
        tables = {t: rec(name, t + ".write") for t in ("GSUB", "GPOS") if t in f}
        emoji = name in EMOJI_FONTS
        n, bad, first = same_shaping(orig, inject(orig, tables), texts_for(name), [None] if emoji else FEATS, (None,) if emoji else (None, "Latn", "Cyrl", "Deva"))
        report(bad == 0, "%s: written GSUB/GPOS shape like the originals in %d runs %s" % (name, n, first or ""))
        gd, ld = DELTAS[name]
        for t in ("GSUB", "GPOS"):
            if t not in f: continue
            a = xml(f.reader[t], t)
            b = xml(rec(name, t + ".shift"), t)
            report(re.sub(r"g_(\d+)_", lambda m: "g_%05d_" % (int(m.group(1)) + gd), a) == b,
                   "%s %s: shiftGlyphs(+%d) is the original with every one of %d glyph references + %d (fontTools)" % (name, t, gd, len(re.findall(r"g_\d+_", a)), gd))
            b = xml(rec(name, t + ".lkshift"), t)
            e = re.sub(r'<LookupListIndex value="(\d+)"/>', lambda m: '<LookupListIndex value="%d"/>' % (int(m.group(1)) + ld), a)
            for x, y in zip(re.findall(r"<FeatureList>.*?</FeatureList>", e, re.S), re.findall(r"<FeatureList>.*?</FeatureList>", a, re.S)): e = e.replace(x, y)
            report(e == b, "%s %s: shiftLookupIndexes(+%d) moves every one of %d contextual lookup indexes (fontTools)" % (name, t, ld, len(re.findall(r'<LookupListIndex value=', a)) - len(re.findall(r"<LookupListIndex value=", "".join(re.findall(r"<FeatureList>.*?</FeatureList>", a, re.S))))))
    for name, locs in LOCATIONS.items():
        for loc in locs:
            inst, data = instance(name, loc)
            tables = {t: rec(name, "inst.%s.%s" % (loc, t)) for t in ("GSUB", "GPOS", "GDEF")}
            mine = inject(data, tables)
            f = TTFont(path(name))
            cmap = inst.getBestCmap()
            letters = [chr(c) for c in list(range(0x41, 0x5B)) + list(range(0x61, 0x7B)) if c in cmap]
            marks = [chr(c) for c in range(0x300, 0x330) if c in cmap]
            texts = texts_for(name, 300, 9) + [a + b for a in letters for b in letters] + [a + m for a in letters for m in marks] + [a + m + m2 for a in letters[:10] for m in marks for m2 in marks]
            n, bad, first = same_shaping(data, mine, texts, [None], (None,), tol=1)
            n2, bad2, first2 = same_shaping(data, mine, texts_for(name, 50, 11), FEATS, (None, "Latn"), tol=1)
            report(bad + bad2 == 0, "%s at %s: instanced GSUB/GPOS/GDEF shape like fontTools' instance (within 1 unit) in %d runs %s" % (name, loc, n + n2, first or first2 or ""))
            raw = inject(data, {t: f.reader[t] for t in ("GSUB", "GPOS", "GDEF") if t in f.reader})
            n3, bad3, _ = same_shaping(data, raw, texts, [None], (None,), tol=1)
            print("     (the unchanged variable tables differ in %d of those %d runs)" % (bad3, n3))
            want, got = caret_formats(inst), caret_formats(TTFont(io.BytesIO(mine)))
            if want or got:
                report(want == got, "%s at %s: the %d ligature carets have fontTools' formats (%s; format 3 with no Device is invalid)"
                       % (name, loc, len(got), ", ".join(sorted("%d x%d" % (k, got.count(k)) for k in set(got)))))
    for text, emoji in MERGES:
        gsub = rec(text, "merge.%s.GSUB" % emoji)
        merged, N, m = merged_font(text, emoji, gsub)
        ef = TTFont(path(emoji))
        edata = open(path(emoji), "rb").read()
        et = [t for t in EMOJI + ligature_texts(ef) if all(ord(c) in ef.getBestCmap() and m.get(ord(c), -1) >= N for c in t)]
        n = bad = 0
        first = None
        for t in et:
            for sc in (None, "Latn", "Cyrl", "Grek"):
                a = [(x[0] + N,) + tuple(x[1:]) for x in shape(edata, t, None, sc)]
                b = shape(merged, t, None, sc)
                n += 1
                if a != b:
                    bad += 1
                    first = first or (t, sc, a[:4], b[:4])
        report(bad == 0, "merge %s + %s: emoji sequences shape like the emoji font (ids + %d) in %d runs %s" % (text, emoji, N, n, first or ""))
        if text != "-":
            tf = TTFont(path(text)); tc = tf.getBestCmap()
            tt = [t for t in texts_for(text, 200, 13) if all(ord(c) in tc and m.get(ord(c)) == tf.getGlyphID(tc[ord(c)]) for c in t)]
            n, bad, first = same_shaping(open(path(text), "rb").read(), merged, tt, [None, {"kern": False}], (None, "Latn", "Zyyy"))
            report(bad == 0, "merge %s + %s: text shapes like the text font in %d runs %s" % (text, emoji, n, first or ""))
    if fails:
        sys.exit("%d checks failed: expected.txt not changed" % fails)
    keep = [l for l in open(EXPECTED).read().splitlines() if not l.startswith("sha ")]
    open(EXPECTED, "w").write("\n".join(keep + shas) + "\n")
    print("recorded", len(shas), "hashes in", EXPECTED)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest="cmd", required=True)
    f = sp.add_parser("fonts")
    for k in ("inter", "notosans", "robotoflex", "notoemoji", "twemoji"): f.add_argument("--" + k, required=True)
    sp.add_parser("expect")
    v = sp.add_parser("verify"); v.add_argument("dir")
    a = ap.parse_args()
    {"fonts": make_fonts, "expect": expect, "verify": verify}[a.cmd](a)


if __name__ == "__main__":
    main()
