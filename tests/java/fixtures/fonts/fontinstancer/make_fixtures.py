#!/usr/bin/env python3
"""Fixtures and expected values of the fontinstancer Java suite (FontInstancerTest). Dev time only: CI runs the suite with the JDK alone.

Needs fontTools (4.67), uharfbuzz (0.57) and freetype-py. Run with python -I, from anywhere:

  make_fixtures.py fonts --inter F --notosans F --robotoflex F --ss3 F --roboto F
      subsets the downloads named in README.md into the fixture fonts next to this script (outlines, gvar, HVAR, MVAR, avar, GDEF, GSUB,
      GPOS and hinting kept for the kept glyphs; Source Sans 3 renamed, 'Source' being a Reserved Font Name) and builds the two synthetic
      fonts (synth-gvar.ttf: every gvar / cvar / MVAR / HVAR case the real fonts do not show; synth-avar2.ttf: avar version 2)
  make_fixtures.py expect
      writes <font>.expect.txt for every fixture (cff2-ss3vf.expect.txt for ../cfftoglyf/ss3vf-subset.otf): at every location the
      normalized coordinates (HarfBuzz's, and fontTools' own), and from fontTools' instantiateVariableFont at HarfBuzz's coordinates
      every glyph's advance, bearing, bounds and a CRC-32 of its points, all the points of sample glyphs, the OS/2 / hhea / post / head /
      maxp values, the cvt values, the kerning and mark attachment of sample glyphs (an evaluator first checked against HarfBuzz) and the
      glyphs FeatureVariations give sample characters (checked against HarfBuzz shaping the variable font); for the CFF2 fixture the
      outlines of the variable font drawn at the location and converted to quadratics, as CffToGlyfTest compares them
  java ... com.bloatware.bingblop.FontInstancerTest --dump DIR
      (the suite's dump mode) writes FontInstancer's instance of every fixture at every location, hinting kept
  make_fixtures.py verify DIR
      compares those instances with fontTools' (at HarfBuzz's coordinates and at fontTools' own), shapes four texts per font in HarfBuzz
      against the variable font at the same location (set_variations), and draws and renders every glyph in FreeType against the
      variable font at that location (unhinted outlines at 1 unit = 1 pixel, 48-pixel renderings, hinted 16-pixel outlines for Roboto);
      exits non-zero outside 1 unit (fontTools, HarfBuzz; FreeType: simple glyphs) or 2 (FreeType: composites)
"""
import argparse, array, hashlib, io, math, os, random, struct, sys, zlib

from fontTools.ttLib import TTFont, newTable
from fontTools.ttLib.tables import otTables as ot
from fontTools.ttLib.tables import ttProgram
from fontTools.ttLib.tables._g_l_y_f import Glyph, GlyphComponent, GlyphCoordinates, flagOnCurve
from fontTools.ttLib.tables._f_v_a_r import Axis, NamedInstance
from fontTools.ttLib.tables.TupleVariation import TupleVariation
from fontTools.fontBuilder import FontBuilder
from fontTools.varLib import builder as vb
from fontTools.varLib.featureVars import addFeatureVariations
from fontTools.varLib import instancer
from fontTools.varLib.instancer import instantiateVariableFont, NormalizedAxisLimits
from fontTools.pens.cu2quPen import Cu2QuPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools import subset
import uharfbuzz as hb

HERE = os.path.dirname(os.path.abspath(__file__))
CFF2 = "../cfftoglyf/ss3vf-subset.otf"


def path(name): return os.path.normpath(os.path.join(HERE, name))


# Subsets of real fonts: (source argument, characters, layout features kept, sample glyphs whose points expected.txt lists in full)
LATIN = "U+0020-007E,U+00C0-00C5,U+00C7-00CF,U+00D1-00D6,U+00E0-00E5,U+00E7-00EF,U+00F1-00F6,U+0300-0304,U+0306-0308,U+030A,U+030C,U+0323,U+0327"
SUBSETS = {
    "Inter-var.ttf": ("inter", LATIN + ",U+2013,U+2014,U+2018,U+2019,U+201C,U+201D", ["kern", "mark", "mkmk"]),
    "NotoSans-var.ttf": ("notosans", LATIN + ",U+0391-0395,U+03B1-03B5,U+0410-0415,U+0430-0435", ["kern", "mark", "mkmk"]),
    "RobotoFlex-var.ttf": ("robotoflex", "U+0020,U+0024,U+002C,U+002E,U+0041,U+0054,U+0056,U+0059,U+0061,U+0065,U+006F,U+0076,U+00A2,"
                                         "U+00C0,U+00E0,U+0300,U+0301,U+0308,U+20A6,U+20B1,U+20B4,U+20B5", ["rvrn", "kern", "mark", "mkmk"]),
    "FixtureSans3-var.ttf": ("ss3", LATIN, ["kern", "mark", "mkmk"]),
    "Roboto-hinted.ttf": ("roboto", "U+0020-007E,U+00C0-00C5,U+00E0-00E5,U+0300-0302,U+0308", ["kern", "mark", "mkmk"]),
}
SYNTH = ["synth-gvar.ttf", "synth-avar2.ttf"]
SAMPLES = {"Inter-var.ttf": "Aagos&", "NotoSans-var.ttf": "AaRgQ", "RobotoFlex-var.ttf": "Aae$", "FixtureSans3-var.ttf": "AagQ",
           "Roboto-hinted.ttf": "Aag", "synth-gvar.ttf": "ABEFGH.", "synth-avar2.ttf": "AB"}
KERN = "AVTWYPLFKRaceoyvw.,"
BASES, MARKS = "AEIOUaeiou", [0x300, 0x301, 0x302, 0x303, 0x308, 0x30A, 0x323, 0x327]
FV_CHARS = [0x24, 0xA2, 0x20A6, 0x20B1, 0x20B4, 0x20B5, 0x41, 0x42, 0x43]


# ---------------------------------------------------------------------------------------------------------------------------------
# fonts
# ---------------------------------------------------------------------------------------------------------------------------------

def rename_source(font):
    """'Source' is a Reserved Font Name of Source Sans 3: the subset is called Fixture Sans 3."""
    for rec in font["name"].names:
        if rec.nameID in (0, 7, 13, 14): continue
        s = rec.toUnicode().replace("Source Sans 3", "Fixture Sans 3").replace("SourceSans3", "FixtureSans3")
        rec.string = s
        assert "Source" not in s, s


def make_subset(name, src, unicodes, features):
    opts = subset.Options()
    opts.layout_features = features
    opts.layout_scripts = ["*"]
    opts.notdef_outline = True
    opts.name_IDs = ["*"]
    opts.hinting = True
    opts.glyph_names = True
    opts.drop_tables += ["BASE", "gasp"]
    font = TTFont(src)
    s = subset.Subsetter(opts)
    s.populate(unicodes=subset.parse_unicodes(unicodes))
    s.subset(font)
    if name.startswith("FixtureSans3"): rename_source(font)
    font.recalcTimestamp = False
    font.save(path(name))
    print(name, os.path.getsize(path(name)), "bytes,", len(font.getGlyphOrder()), "glyphs,", " ".join(sorted(font.keys())))


def simple(points, ends, program=b""):
    """A TrueType glyph from (x, y, on) points and contour ends."""
    g = Glyph()
    g.numberOfContours = len(ends)
    g.coordinates = GlyphCoordinates([(x, y) for x, y, _ in points])
    g.endPtsOfContours = list(ends)
    g.flags = array.array("B", [flagOnCurve if on else 0 for _, _, on in points])
    g.program = ttProgram.Program()
    g.program.fromBytecode(program)
    return g


def composite(parts):
    """A composite glyph from (glyph name, x, y, 2x2 transform or None) or (glyph name, ('match', parent point, component point))."""
    g = Glyph()
    g.numberOfContours = -1
    g.components = []
    for p in parts:
        c = GlyphComponent()
        c.glyphName = p[0]
        c.flags = 0
        if isinstance(p[1], tuple):
            c.firstPt, c.secondPt = p[1][1], p[1][2]
        else:
            c.x, c.y = p[1], p[2]
            if p[3] is not None: c.transform = p[3]
        g.components.append(c)
    g.program = ttProgram.Program()
    g.program.fromBytecode(b"")
    return g


def tv(axes, deltas):
    return TupleVariation(axes, list(deltas))


def all_points(pts, f):
    return [f(i, x, y) for i, (x, y, _) in enumerate(pts)]


def var_store(axis_tags, items):
    """An ItemVariationStore with one VarData per entry of items: (regions as {tag: (start, peak, end)}, rows of deltas)."""
    regions, datas = [], []
    for regs, rows in items:
        idx = []
        for r in regs:
            if r not in regions: regions.append(r)
            idx.append(regions.index(r))
        datas.append(vb.buildVarData(idx, rows, optimize=False))
    return vb.buildVarStore(vb.buildVarRegionList(regions, axis_tags), datas)


def synth_gvar():
    """Every case of gvar, cvar, MVAR, HVAR and avar 1 that the real fixtures do not show (see README.md)."""
    order = [".notdef", "space", "A", "B", "C", "D", "E", "F", "G", "H", "A.rvrn", "B.rvrn", "I", "period"]
    fb = FontBuilder(1000, isTTF=True)
    fb.setupGlyphOrder(order)
    fb.setupCharacterMap({0x20: "space", 0x41: "A", 0x42: "B", 0x43: "C", 0x44: "D", 0x45: "E", 0x46: "F", 0x47: "G", 0x48: "H",
                          0x49: "I", 0x2E: "period"})
    A = [(50, 0, 1), (200, 700, 1), (260, 740, 0), (320, 700, 1), (470, 0, 1), (380, 0, 1), (300, 260, 0), (220, 260, 0), (140, 0, 1),
         (200, 340, 1), (260, 560, 1), (320, 340, 1)]
    B = [(100, 0, 1), (100, 300, 1), (100, 600, 1), (300, 700, 0), (500, 600, 1), (500, 300, 1), (500, 0, 1), (300, -50, 0),
         (200, 200, 1), (400, 200, 1), (400, 400, 1), (200, 400, 1), (250, 250, 1), (350, 250, 1), (300, 350, 1)]
    E = [(60, -10, 1), (60, 650, 1), (420, 650, 0), (420, -10, 1)]
    F = [(80, 0, 1), (80, 700, 1), (400, 700, 1), (400, 620, 1), (170, 620, 1), (170, 400, 1), (360, 400, 1), (360, 320, 1), (170, 320, 1),
         (170, 0, 1)]
    G = [(i * 37 % 500 + 40, i * 53 % 700, i % 3 != 0) for i in range(24)]
    H = [(100, 0, 1), (100, 500, 1), (400, 500, 1), (400, 0, 1)]
    P = [(40, 0, 1), (40, 90, 1), (130, 90, 1), (130, 0, 1)]
    glyphs = {
        ".notdef": simple([(50, 0, 1), (50, 700, 1), (450, 700, 1), (450, 0, 1)], [3]),
        "space": Glyph(),
        "A": simple(A, [8, 11]),
        "B": simple(B, [7, 11, 14]),
        "C": composite([("A", 0, 0, [[0.5, 0], [0, 0.5]]), ("B", 300, 50, [[0.9, 0.2], [-0.2, 0.9]])]),
        "D": composite([("A", 0, 0, None), ("period", ("match", 4, 0))]),
        "E": simple(E, [3]),
        "F": simple(F, [9], program=bytes([0xB0, 0x01, 0x21, 0xB0, 0x02, 0x21])),     # PUSHB[0] 1, POP, PUSHB[0] 2, POP
        "G": simple(G, [7, 15, 23]),
        "H": simple(H, [3]),
        "A.rvrn": simple([(x + 10, y, on) for x, y, on in A], [8, 11]),
        "B.rvrn": simple([(x, y + 5, on) for x, y, on in B], [7, 11, 14]),
        "I": composite([("C", 50, -20, None), ("period", 600, 0, None)]),
        "period": simple(P, [3]),
    }
    fb.setupGlyf(glyphs)
    adv = {".notdef": 500, "space": 250, "A": 520, "B": 600, "C": 900, "D": 650, "E": 480, "F": 450, "G": 600, "H": 500, "A.rvrn": 530,
           "B.rvrn": 600, "I": 1000, "period": 170}
    glyf = fb.font["glyf"]
    fb.setupHorizontalMetrics({g: (adv[g], getattr(glyf[g], "xMin", 0)) for g in order})
    fb.setupHorizontalHeader(ascent=800, descent=-200)
    fb.setupOS2(version=4, sTypoAscender=800, sTypoDescender=-200, sTypoLineGap=90, usWinAscent=900, usWinDescent=250, sxHeight=500,
                sCapHeight=700, ySubscriptXSize=650, ySubscriptYSize=600, ySubscriptXOffset=0, ySubscriptYOffset=75, ySuperscriptXSize=650,
                ySuperscriptYSize=600, ySuperscriptXOffset=0, ySuperscriptYOffset=350, yStrikeoutSize=50, yStrikeoutPosition=300,
                usWeightClass=400, usWidthClass=5, achVendID="NONE")
    fb.font["hhea"].lineGap = 90                                        # OS/2 typo values = hhea's: MVAR's hasc etc. move both
    fb.setupNameTable({"familyName": "Instancer Synth", "styleName": "Regular", "uniqueFontIdentifier": "InstancerSynth-Regular",
                       "fullName": "Instancer Synth Regular", "psName": "InstancerSynth-Regular", "version": "Version 1.000"})
    fb.setupPost(underlinePosition=-100, underlineThickness=50)
    axes = [("wght", 100, 400, 900, "Weight"), ("wdth", 50, 100, 200, "Width"), ("slnt", -12, 0, 0, "Slant"), ("XHGT", 0, 0, 100, "X height")]
    instances = [dict(location=dict(wght=300, wdth=100, slnt=0, XHGT=0), stylename="Light"),
                 dict(location=dict(wght=400, wdth=100, slnt=0, XHGT=0), stylename="Regular"),
                 dict(location=dict(wght=700, wdth=100, slnt=0, XHGT=0), stylename="Bold"),
                 dict(location=dict(wght=700, wdth=75, slnt=0, XHGT=0), stylename="Condensed Bold"),
                 dict(location=dict(wght=400, wdth=100, slnt=-12, XHGT=0), stylename="Italic"),
                 dict(location=dict(wght=900, wdth=200, slnt=-12, XHGT=100), stylename="Everything")]
    fb.setupFvar(axes, instances)
    avar = newTable("avar")
    avar.segments = {"wght": {-1.0: -1.0, -0.5: -0.4, 0.0: 0.0, 0.5: 0.65, 1.0: 1.0}, "wdth": {-1.0: -1.0, 0.0: 0.0, 1.0: 1.0},
                     "slnt": {-1.0: -1.0, -0.25: -0.3, 0.0: 0.0, 1.0: 1.0}, "XHGT": {-1.0: -1.0, 0.0: 0.0, 1.0: 1.0}}
    fb.font["avar"] = avar

    W, Wl, N, S, X = {"wght": (0, 1, 1)}, {"wght": (-1, -1, 0)}, {"wdth": (-1, -1, 0)}, {"slnt": (-1, -1, 0)}, {"XHGT": (0, 1, 1)}
    Ww = {"wdth": (0, 1, 1)}
    ph = lambda d2: [(0, 0), (d2, 0), (0, 0), (0, 0)]                 # phantom points: left, right (the advance), top, bottom
    sparse = lambda n, d: [d.get(i) for i in range(n)]
    var = {}
    # A: every point listed (gvar's "all points" encoding), a corner and a sparse tuple (inferred deltas), slant, an axis sorted first
    var["A"] = [tv(W, all_points(A, lambda i, x, y: ((i * 7) % 23 - 11 + (x - 260) // 8, (i * 5) % 17 - 8)) + ph(60)),
                tv(N, all_points(A, lambda i, x, y: (-(x - 260) * 3 // 10, 0)) + ph(-150)),
                tv(dict(W, **N), sparse(16, {0: (13, 3), 4: (-21, 0), 9: (5, 9), 13: (7, 0)})),
                tv(S, all_points(A, lambda i, x, y: (y * 2126 // 10000, 0)) + [(0, 0)] * 4),
                tv(X, sparse(16, {1: (0, 40), 3: (0, 41), 10: (0, 30)})),
                tv(dict(W, XHGT=(0, 1, 1), wdth=(0, 1, 1)), all_points(A, lambda i, x, y: (i * 3 - 17, 11 - i)) + ph(9))]
    # B: inferred deltas between, outside and at the same coordinate as listed points; a contour with one listed point and one with none;
    # 16-bit deltas and runs of zeros
    var["B"] = [tv(W, sparse(19, {0: (10, -5), 2: (-20, 15), 4: (300, 25), 8: (7, -9), 16: (45, 0)})),
                tv(N, sparse(19, {1: (-3, 0), 5: (-140, 0), 6: (-140, 2), 11: (0, 0), 12: (-9, 1), 16: (-160, 0)})),
                tv(Wl, all_points(B, lambda i, x, y: ((i * 61) % 401 - 200, (i * 29) % 301 - 150 if i % 4 else 0)) + ph(-80))]
    # C: a composite with scaled and 2x2-transformed components; an intermediate region listing only the first component
    var["C"] = [tv(W, [(20, 0), (40, 10)] + ph(60)), tv(N, [(-10, 0), (-80, 0)] + ph(-100)),
                tv({"wght": (0.25, 0.5, 1)}, [(5, 5), None, None, None, None, None])]
    # D: a point-matched component (its "offset" delta is not used) next to an offset one
    var["D"] = [tv(W, [(30, 0), (999, -999)] + ph(40))]
    # E: intermediate regions, one negative, and a region on three axes (the order fontTools multiplies the factors in)
    var["E"] = [tv({"wght": (0.2, 0.5, 0.8)}, [(17, 0), (17, 33), (-29, 33), (-29, 0)] + ph(23)),
                tv({"slnt": (-1, -0.5, 0)}, [(30, 0), (77, 0), (77, 0), (30, 0)] + ph(0)),
                tv({"wght": (0, 0.6, 1), "wdth": (-1, -0.4, 0), "XHGT": (0, 0.3, 1)}, [(11, 13), (-7, 5), (3, -3), (1, 1)] + ph(-5))]
    var["F"] = [tv(W, all_points(F, lambda i, x, y: (25 if x > 100 else 0, 0)) + ph(30))]
    # G: three tuples listing the same points (shared point numbers)
    gp = {1: (5, 5), 6: (-7, 3), 9: (11, -2), 17: (4, 4), 22: (-3, -8)}
    var["G"] = [tv(W, sparse(28, {k: (v[0] * 3, v[1] * 3) for k, v in gp.items()})), tv(N, sparse(28, gp)),
                tv(X, sparse(28, {k: (-v[1], v[0]) for k, v in gp.items()}))]
    # H: only the right phantom point varies (the outline stays, the advance changes); then only the left one (the bearing changes)
    var["H"] = [tv(W, [None] * 4 + [None, (100, 0), None, None]), tv(N, [None] * 4 + [(-40, 0), None, None, None])]
    var["I"] = [tv(W, [(10, 0), (35, 0)] + ph(45))]
    var["space"] = [tv(W, ph(50)), tv(N, ph(-60))]
    var["period"] = [tv(W, [(-10, 0), (-10, 20), (20, 20), (20, 0)] + ph(10))]
    fb.setupGvar(var)

    # Hinting: fpgm, prep, cvt and a cvar with a sparse tuple, an all-entries tuple and an intermediate one
    fpgm, prep = newTable("fpgm"), newTable("prep")
    fpgm.program = ttProgram.Program(); fpgm.program.fromBytecode(bytes([0xB0, 0x00, 0x2C, 0x21, 0x2D]))   # FDEF 0: POP, ENDF
    prep.program = ttProgram.Program(); prep.program.fromBytecode(bytes([0xB1, 0x07, 0x00, 0x2B]))         # PUSHB 7 0, CALL
    fb.font["fpgm"], fb.font["prep"] = fpgm, prep
    cvt = newTable("cvt ")
    cvt.values = array.array("h", [0, 50, 80, 120, -200, 700, 520, 30, 0, 1000])
    fb.font["cvt "] = cvt
    cvar = newTable("cvar")
    cvar.majorVersion, cvar.minorVersion = 1, 0
    cvar.variations = [TupleVariation(W, [None, 5, 7, -3, None, None, None, None, None, None]),
                       TupleVariation(N, [i * 2 - 9 for i in range(10)]),
                       TupleVariation({"wght": (0.2, 0.5, 0.8)}, [None] * 5 + [37] + [None] * 4)]
    fb.font["cvar"] = cvar

    tags = [a[0] for a in axes]
    # MVAR: every tag the instancer applies, plus a vertical one and a gasp one that it leaves alone (fontTools too)
    mvar_tags = ["hasc", "hdsc", "hlgp", "hcla", "hcld", "hcrs", "hcrn", "hcof", "xhgt", "cpht", "sbxs", "sbys", "sbxo", "sbyo", "spxs",
                 "spys", "spxo", "spyo", "strs", "stro", "unds", "undo", "vasc", "gsp0"]
    rows = [[(k * 13) % 41 - 20, (k * 7) % 23 - 11] for k in range(len(mvar_tags))]
    rows[mvar_tags.index("hcld")] = [-200, 0]
    rows[mvar_tags.index("vasc")] = [0, 0]                               # fontTools fails on a vertical tag that moves (there is no vhea)
    store = var_store(tags, [([W, N], rows)])
    mvar = newTable("MVAR")
    mvar.table = ot.MVAR()
    mvar.table.Version, mvar.table.Reserved, mvar.table.ValueRecordSize = 0x00010000, 0, 8
    mvar.table.VarStore = store
    recs = []
    for k, tag in sorted(enumerate(mvar_tags), key=lambda e: e[1]):
        r = ot.MetricsValueRecord()
        r.ValueTag, r.VarIdx = tag, k
        recs.append(r)
    mvar.table.ValueRecord, mvar.table.ValueRecordCount = recs, len(recs)
    fb.font["MVAR"] = mvar
    # HVAR with an advance map. Its rows do not follow gvar's phantom points, and .notdef, A.rvrn and B.rvrn have no gvar data at all:
    # the app takes every advance from HVAR, as HarfBuzz does (fontTools takes them from the phantom points)
    hrows = [[(i * 17) % 31 - 15, (i * 11) % 19 - 9] for i in range(len(order))]
    hstore = var_store(tags, [([W, N], hrows)])
    hvar = newTable("HVAR")
    hvar.table = ot.HVAR()
    hvar.table.Version, hvar.table.VarStore = 0x00010000, hstore
    hvar.table.AdvWidthMap = vb.buildDeltaSetIndexMap([len(order) - 1 - i for i in range(len(order))])
    hvar.table.LsbMap = hvar.table.RsbMap = None
    fb.font["HVAR"] = hvar
    # rvrn: A -> A.rvrn from wght 0.6 (normalized), B -> B.rvrn when narrow and not light; the first matching record wins
    addFeatureVariations(fb.font, [([{"wght": (0.6, 1.0)}], {"A": "A.rvrn"}),
                                   ([{"wdth": (-1.0, -0.5), "wght": (0.0, 1.0)}], {"B": "B.rvrn"}),
                                   ([{"wdth": (-1.0, -0.3)}], {"A": "A.rvrn", "B": "B.rvrn"})], featureTag="rvrn")
    fb.setupStat([dict(tag=a[0], name=a[4]) for a in axes])
    fb.setupDummyDSIG()
    maxp = fb.font["maxp"]
    maxp.maxZones, maxp.maxFunctionDefs, maxp.maxStackElements, maxp.maxSizeOfInstructions = 2, 1, 2, 6
    fb.font["head"].created = fb.font["head"].modified = 3_800_000_000
    fb.font.recalcTimestamp = False
    fb.save(path("synth-gvar.ttf"))
    print("synth-gvar.ttf", os.path.getsize(path("synth-gvar.ttf")), "bytes")


def synth_avar2():
    """avar version 2: the width an instance is drawn at also follows its weight (and the weight its width)."""
    order = [".notdef", "A", "B"]
    fb = FontBuilder(1000, isTTF=True)
    fb.setupGlyphOrder(order)
    fb.setupCharacterMap({0x41: "A", 0x42: "B"})
    A = [(50, 0, 1), (250, 700, 1), (450, 0, 1)]
    B = [(100, 0, 1), (100, 700, 1), (500, 700, 1), (500, 0, 1)]
    fb.setupGlyf({".notdef": simple([(0, 0, 1), (0, 500, 1), (400, 500, 1)], [2]), "A": simple(A, [2]), "B": simple(B, [3])})
    fb.setupHorizontalMetrics({".notdef": (500, 0), "A": (500, 50), "B": (600, 100)})
    fb.setupHorizontalHeader(ascent=800, descent=-200)
    fb.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200, achVendID="NONE")
    fb.setupNameTable({"familyName": "Instancer Avar2", "styleName": "Regular", "psName": "InstancerAvar2-Regular"})
    fb.setupPost()
    axes = [("wght", 100, 400, 900, "Weight"), ("wdth", 75, 100, 125, "Width")]
    fb.setupFvar(axes, [dict(location=dict(wght=900, wdth=100), stylename="Black")])
    W, Wl, N, Nw = {"wght": (0, 1, 1)}, {"wght": (-1, -1, 0)}, {"wdth": (-1, -1, 0)}, {"wdth": (0, 1, 1)}
    ph = lambda d2: [(0, 0), (d2, 0), (0, 0), (0, 0)]
    fb.setupGvar({"A": [TupleVariation(W, [(-20, 0), (0, 0), (20, 0)] + ph(40)), TupleVariation(N, [(40, 0), (0, 0), (-40, 0)] + ph(-80)),
                        TupleVariation(Nw, [(-30, 0), (0, 10), (30, 0)] + ph(60))],
                  "B": [TupleVariation(N, [(0, 0), (0, 0), (-100, 0), (-100, 0)] + ph(-100)),
                        TupleVariation(Wl, [(5, 0), (5, 0), (-5, 0), (-5, 0)] + ph(0))]})
    avar = newTable("avar")
    avar.majorVersion, avar.minorVersion = 2, 0
    avar.segments = {"wght": {-1.0: -1.0, 0.0: 0.0, 0.5: 0.4, 1.0: 1.0}, "wdth": {-1.0: -1.0, 0.0: 0.0, 1.0: 1.0}}
    avar.table = ot.avar()
    # item 0 (wght): +0.1 at wide widths; item 1 (wdth): -0.25 at the heaviest weight, +0.2 at the lightest
    avar.table.VarStore = var_store(["wght", "wdth"], [([Nw, W, Wl], [[1638, 0, 0], [0, -4096, 3277]])])
    avar.table.VarIdxMap = vb.buildDeltaSetIndexMap([0, 1])
    fb.font["avar"] = avar
    fb.font["head"].created = fb.font["head"].modified = 3_800_000_000
    fb.font.recalcTimestamp = False
    fb.save(path("synth-avar2.ttf"))
    print("synth-avar2.ttf", os.path.getsize(path("synth-avar2.ttf")), "bytes")


def make_fonts(args):
    for name, (key, unicodes, features) in SUBSETS.items():
        make_subset(name, getattr(args, key), unicodes, features)
    synth_gvar()
    synth_avar2()



# ---------------------------------------------------------------------------------------------------------------------------------
# Locations, normalization and fontTools' instance
# ---------------------------------------------------------------------------------------------------------------------------------

# Where each fixture is instanced: default / min / max (every axis), #k (named instance k), rand:N (a seeded point), or tag=value,...
LOCATIONS = {
    "Inter-var.ttf": ["default", "min", "max", "#0", "#6", "#8", "wght=650,opsz=20", "rand:1"],
    "NotoSans-var.ttf": ["default", "min", "max", "#6", "wght=550,wdth=80", "rand:2"],
    "RobotoFlex-var.ttf": ["default", "min", "max", "#6", "#16", "wght=700,wdth=50,opsz=8", "opsz=8", "rand:3", "rand:4"],
    "FixtureSans3-var.ttf": ["default", "max", "#3", "rand:5"],
    "Roboto-hinted.ttf": ["default", "min", "max", "#5", "#15", "rand:6"],
    "synth-gvar.ttf": ["default", "min", "max", "#0", "#1", "#2", "#3", "#4", "#5", "wght=650", "wdth=60,slnt=-9",
                       "wght=250,wdth=150,slnt=-5,XHGT=37", "rand:7", "rand:8", "rand:9"],
    "synth-avar2.ttf": ["default", "min", "max", "#0", "wght=100", "wdth=125", "wght=650,wdth=110"],
    CFF2: ["max", "#3", "rand:10"],
}


def expect_file(name):
    """Where the expected values of fixture `name` go (one file per font)."""
    return path("cff2-ss3vf.expect.txt" if name == CFF2 else name.rsplit(".", 1)[0] + ".expect.txt")


def user_location(font, spec):
    axes = font["fvar"].axes
    loc = {a.axisTag: a.defaultValue for a in axes}
    if spec == "min": loc = {a.axisTag: a.minValue for a in axes}
    elif spec == "max": loc = {a.axisTag: a.maxValue for a in axes}
    elif spec.startswith("#"):
        loc.update(font["fvar"].instances[int(spec[1:])].coordinates)
    elif spec.startswith("rand:"):
        r = random.Random(int(spec[5:]))
        loc = {a.axisTag: round(r.uniform(a.minValue, a.maxValue), 2) for a in axes}
    elif spec != "default":
        for kv in spec.split(","):
            k, v = kv.split("=")
            loc[k] = float(v)
    return {a.axisTag: float(min(max(loc[a.axisTag], a.minValue), a.maxValue)) for a in axes}


def hb_normalized(data, loc):
    """HarfBuzz's normalized coordinates (F2Dot14): default normalization, avar 1 and avar 2, quantized as the app quantizes them."""
    font = hb.Font(hb.Face(data))
    font.set_variations(loc)
    return [int(round(v * 16384)) for v in font.get_var_coords_normalized()]


def ft_normalized(font, loc):
    """fontTools' own normalized coordinates for a full instance (it quantizes only after avar 1)."""
    axes = font["fvar"].axes
    if "avar" in font and getattr(font["avar"], "majorVersion", 1) >= 2:
        n = {a.axisTag: instancer.normalize(loc[a.axisTag], (a.minValue, a.defaultValue, a.maxValue), None) for a in axes}
        n = font["avar"].renormalizeLocation(n, font, dropZeroes=False)
    else:
        seg = font["avar"].segments if "avar" in font else {}
        n = {a.axisTag: instancer.normalize(loc[a.axisTag], (a.minValue, a.defaultValue, a.maxValue), seg.get(a.axisTag)) for a in axes}
    return [int(round(n[a.axisTag] * 16384)) for a in axes]


def ft_instance(name, loc, norm=None):
    """fontTools' full instance, read back from its bytes; at the normalized coordinates `norm` when given (instead of its own). A glyf
    font with HVAR gets HarfBuzz's advances at the same coordinates (and the average width that follows from them): fontTools takes a glyf
    font's advances from gvar's phantom points, where HarfBuzz, FreeType and the app take them from HVAR, and some fonts move an advance
    in HVAR only (Fraunces' gcircumflex, Literata's ldot)."""
    vf = TTFont(path(name))
    hvar = "HVAR" in vf and "glyf" in vf
    coords = norm if norm is not None else ft_normalized(vf, loc)
    saved = instancer.AxisLimits.normalize
    if norm is not None:
        tags = [a.axisTag for a in vf["fvar"].axes]
        instancer.AxisLimits.normalize = lambda self, varfont, usingAvar=True: NormalizedAxisLimits(
            **{t: v / 16384 for t, v in zip(tags, norm)})
    try:
        inst = instantiateVariableFont(vf, loc)
    finally:
        instancer.AxisLimits.normalize = saved
    if hvar:
        hf = hb.Font(hb.Face(open(path(name), "rb").read()))
        hf.set_var_coords_normalized([v / 16384 for v in coords])
        for g, gname in enumerate(inst.getGlyphOrder()):
            inst["hmtx"][gname] = (hf.get_glyph_h_advance(g), inst["hmtx"][gname][1])
        inst["OS/2"].recalcAvgCharWidth(inst)
    b = io.BytesIO()
    inst.save(b)
    return TTFont(io.BytesIO(b.getvalue())), b.getvalue()


# ---------------------------------------------------------------------------------------------------------------------------------
# What expected.txt records of a glyph, and the GPOS / GSUB evaluators (checked against HarfBuzz in expect)
# ---------------------------------------------------------------------------------------------------------------------------------

def glyph_key(font, name):
    """The canonical text whose CRC-32 expected.txt keeps: points (x, y, on-curve) and contour ends, or the components (the suite builds
    the same text)."""
    g = font["glyf"][name]
    if g.isComposite():
        order = font.getGlyphOrder()
        out = ""
        for c in g.components:
            gid = order.index(c.glyphName)
            out += ("m%d,%d,%d;" % (gid, c.firstPt, c.secondPt)) if hasattr(c, "firstPt") else ("c%d,%d,%d;" % (gid, c.x, c.y))
        return out
    if g.numberOfContours <= 0: return ""
    return "".join("%d,%d,%d;" % (x, y, f & 1) for (x, y), f in zip(g.coordinates, g.flags)) + "|" + ",".join(map(str, g.endPtsOfContours))


def bounds(font, name):
    g = font["glyf"][name]
    return (g.xMin, g.yMin, g.xMax, g.yMax) if g.numberOfContours != 0 else (0, 0, 0, 0)


METRICS = [("OS/2", f) for f in ("xAvgCharWidth", "usWeightClass", "usWidthClass", "ySubscriptXSize", "ySubscriptYSize", "ySubscriptXOffset",
                                  "ySubscriptYOffset", "ySuperscriptXSize", "ySuperscriptYSize", "ySuperscriptXOffset", "ySuperscriptYOffset",
                                  "yStrikeoutSize", "yStrikeoutPosition", "sTypoAscender", "sTypoDescender", "sTypoLineGap", "usWinAscent",
                                  "usWinDescent", "sxHeight", "sCapHeight")] + \
          [("hhea", f) for f in ("ascent", "descent", "lineGap", "advanceWidthMax", "minLeftSideBearing", "minRightSideBearing", "xMaxExtent",
                                 "caretSlopeRise", "caretSlopeRun", "caretOffset", "numberOfHMetrics")] + \
          [("post", f) for f in ("italicAngle", "underlinePosition", "underlineThickness")] + \
          [("head", f) for f in ("xMin", "yMin", "xMax", "yMax")] + \
          [("maxp", f) for f in ("numGlyphs", "maxPoints", "maxContours", "maxCompositePoints", "maxCompositeContours", "maxComponentElements",
                                 "maxComponentDepth")]


def metric(font, tag, field):
    v = getattr(font[tag], field, None)
    if v is None: return None
    if tag == "post" and field == "italicAngle": return int(round(v * 65536))
    return int(v)


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
        yield st.ExtSubTable if l.LookupType in (7, 9) and hasattr(st, "ExtSubTable") else st


def xadv(v): return (getattr(v, "XAdvance", 0) or 0) if v is not None else 0


def kern(table, lookups, left, right):
    """The x advance adjustments {first, second} of a two-glyph run through the PairPos lookups (HarfBuzz: the first subtable that has the
    pair applies; class 2 = 0 in format 2 does not)."""
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
            c1, c2 = st.ClassDef1.classDefs.get(left, 0), st.ClassDef2.classDefs.get(right, 0)
            if c2 == 0 or c1 >= st.Class1Count or c2 >= st.Class2Count: continue
            r = st.Class1Record[c1].Class2Record[c2]
            v1 += xadv(r.Value1); v2 += xadv(getattr(r, "Value2", None))
            break
    return v1, v2


def mark_attach(table, lookups, base, mark):
    """The mark's offset from the base glyph (base anchor - mark anchor) through MarkBasePos, or None."""
    for li in lookups:
        for st in subtables(table, li):
            if st.LookupType != 4 or mark not in st.MarkCoverage.glyphs or base not in st.BaseCoverage.glyphs: continue
            mr = st.MarkArray.MarkRecord[st.MarkCoverage.glyphs.index(mark)]
            if mr.Class >= st.ClassCount: continue
            ba = st.BaseArray.BaseRecord[st.BaseCoverage.glyphs.index(base)].BaseAnchor[mr.Class]
            if ba is None: continue
            return ba.XCoordinate - mr.MarkAnchor.XCoordinate, ba.YCoordinate - mr.MarkAnchor.YCoordinate
    return None


def single_subst(table, lookups, glyph):
    """The glyph after the SingleSubst lookups (each lookup's first subtable that covers it)."""
    for li in lookups:
        for st in subtables(table, li):
            if st.LookupType == 1 and glyph in st.mapping:
                glyph = st.mapping[glyph]
                break
    return glyph


def rvrn_lookups(table):
    out = set()
    for fr in table.FeatureList.FeatureRecord:
        if fr.FeatureTag == "rvrn": out |= set(fr.Feature.LookupListIndex)
    return sorted(out)


def shape(data, text, loc=None, feats=None):
    font = hb.Font(hb.Face(data))
    if loc: font.set_variations(loc)
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(font, buf, feats or {})
    return [(i.codepoint, p.x_advance, p.x_offset, p.y_offset) for i, p in zip(buf.glyph_infos, buf.glyph_positions)]


# CFF2: the variable font drawn at the location and converted to quadratics, compared as CffToGlyfTest compares outlines

def area(coords, ends, flags):
    """Signed area of a TrueType outline (quadratic segments, implied on-curve points included)."""
    total, s = 0.0, 0
    for e in ends:
        pts = [(coords[i][0], coords[i][1], flags[i] & 1) for i in range(s, e + 1)]
        s = e + 1
        full = []
        for i, p in enumerate(pts):
            q = pts[(i + 1) % len(pts)]
            full.append(p)
            if not p[2] and not q[2]: full.append(((p[0] + q[0]) / 2, (p[1] + q[1]) / 2, 1))
        k = next((i for i, p in enumerate(full) if p[2]), None)
        if k is None: continue
        full = full[k:] + full[:k]
        cr = lambda u, v: u[0] * v[1] - v[0] * u[1]
        i, m = 0, len(full)
        while i < m:
            p0, p1 = full[i], full[(i + 1) % m]
            if p1[2]:
                total += cr(p0, p1) / 2
                i += 1
            else:
                p2 = full[(i + 2) % m]
                total += (cr(p0, p1) + cr(p1, p2)) / 3 + cr(p0, p2) / 6
                i += 2
    return total


def oncurve(coords, ends, flags):
    """Each contour's on-curve points, repeats (and a last point equal to the first) removed."""
    out, s = [], 0
    for e in ends:
        pts = [(coords[i][0], coords[i][1], flags[i] & 1) for i in range(s, e + 1)]
        s = e + 1
        d = [p for i, p in enumerate(pts) if i == 0 or p != pts[i - 1]]
        while len(d) > 1 and d[-1] == d[0]: d.pop()
        out.append([(p[0], p[1]) for p in d if p[2]])
    return out


def drawn(vf, norm, name):
    tags = [a.axisTag for a in vf["fvar"].axes]
    gs = vf.getGlyphSet(location={t: v / 16384 for t, v in zip(tags, norm)}, normalized=True)
    pen = TTGlyphPen(gs)
    gs[name].draw(Cu2QuPen(pen, vf["head"].unitsPerEm / 1000, reverse_direction=True))
    g = pen.glyph()
    if g.numberOfContours <= 0: return None
    g.recalcBounds(None)
    return g


# ---------------------------------------------------------------------------------------------------------------------------------
# expect
# ---------------------------------------------------------------------------------------------------------------------------------

def sha256(name): return hashlib.sha256(open(path(name), "rb").read()).hexdigest()


def expect(args):
    hbruns = 0
    for name, specs in LOCATIONS.items():
        out = ["# %s: fontinstancer expectations, written by make_fixtures.py expect (fontTools %s, HarfBuzz %s). Do not edit by hand."
               % (name, __import__("fontTools").version, hb.__version__)]
        out.append("MF " + ",".join("%s.%s" % m for m in METRICS))
        data = open(path(name), "rb").read()
        vf = TTFont(path(name))
        order = vf.getGlyphOrder()
        gid = {g: i for i, g in enumerate(order)}
        cmap = vf.getBestCmap()
        cff2 = "CFF2" in vf
        out.append("font %s %s %d" % (name, sha256(name), len(order)))
        samples = [gid[cmap[ord(c)]] for c in SAMPLES.get(name, "") if ord(c) in cmap]
        for spec in specs:
            loc = user_location(vf, spec)
            norm, ftnorm = hb_normalized(data, loc), ft_normalized(vf, loc)
            out.append("loc %s %s" % (spec, ",".join("%s=%r" % (t, v) for t, v in loc.items())))
            out.append("norm " + ",".join(map(str, norm)))
            out.append("ftnorm " + ",".join(map(str, ftnorm)))
            inst, idata = ft_instance(name, loc, norm)
            if cff2:
                for g, gname in enumerate(order):
                    adv = inst["hmtx"][gname][0]
                    t = drawn(vf, norm, gname)
                    if t is None:
                        out.append("e %d %d" % (g, adv))
                        continue
                    co, ends, fl = list(t.coordinates), list(t.endPtsOfContours), list(t.flags)
                    oc = " / ".join(" ".join("%d,%d" % p for p in c) for c in oncurve(co, ends, fl))
                    out.append("c %d %d %d %d %d %d %d %.1f %s" % (g, adv, len(ends), t.xMin, t.yMin, t.xMax, t.yMax, area(co, ends, fl), oc))
            else:
                for g, gname in enumerate(order):
                    adv, lsb = inst["hmtx"][gname]
                    out.append("g %d %d %d %d %d %d %d %08x" % ((g, adv, lsb) + bounds(inst, gname)
                                                              + (zlib.crc32(glyph_key(inst, gname).encode()) & 0xFFFFFFFF,)))
                for g in samples[:3]:
                    gl = inst["glyf"][order[g]]
                    if gl.isComposite() or gl.numberOfContours <= 0: continue
                    out.append("P %d %s %s" % (g, ",".join(map(str, gl.endPtsOfContours)),
                                               ";".join("%d,%d,%d" % (x, y, f & 1) for (x, y), f in zip(gl.coordinates, gl.flags))))
            out.append("M " + " ".join("-" if v is None else str(v) for v in (metric(inst, t, f) for t, f in METRICS)))
            out.append("T " + ",".join(sorted(t for t in inst.keys() if t != "GlyphOrder")))
            if "cvt " in inst: out.append("V " + ",".join(map(str, inst["cvt "].values)))
            if name == "synth-gvar.ttf":
                # Without gvar, HVAR gives the advances (fontTools ignores it in a glyf font): HarfBuzz's at the same location
                f = TTFont(path(name))
                del f["gvar"]
                b = io.BytesIO()
                f.save(b)
                hf = hb.Font(hb.Face(b.getvalue()))
                hf.set_variations(loc)
                out.append("H " + ",".join(str(hf.get_glyph_h_advance(g)) for g in range(len(order))))
            if "GPOS" in inst:
                gpos = inst["GPOS"].table
                kl, ml = feature_lookups(gpos, "kern"), feature_lookups(gpos, "mark")
                letters = [c for c in map(ord, KERN) if c in cmap]
                out.append("KG " + ",".join(str(gid[cmap[c]]) for c in letters))
                for a in letters:
                    for b in letters:
                        v1, v2 = kern(gpos, kl, cmap[a], cmap[b])
                        if v1 or v2: out.append("K %d %d %d %d" % (gid[cmap[a]], gid[cmap[b]], v1, v2))
                        s = shape(idata, chr(a) + chr(b), feats={"kern": True})
                        if [x[0] for x in s] == [gid[cmap[a]], gid[cmap[b]]]:
                            if (s[0][1] - inst["hmtx"][cmap[a]][0], s[1][1] - inst["hmtx"][cmap[b]][0]) != (v1, v2):
                                sys.exit("%s %s: the kerning evaluator disagrees with HarfBuzz on %s%s" % (name, spec, chr(a), chr(b)))
                            hbruns += 1
                bases, marks = [c for c in map(ord, BASES) if c in cmap], [c for c in MARKS if c in cmap]
                out.append("AG " + ",".join(str(gid[cmap[c]]) for c in bases))
                out.append("AM " + (",".join(str(gid[cmap[c]]) for c in marks) or "-"))
                for a in bases:
                    for m in marks:
                        at = mark_attach(gpos, ml, cmap[a], cmap[m])
                        if at is None: continue
                        out.append("A %d %d %d %d" % (gid[cmap[a]], gid[cmap[m]], at[0], at[1]))
                        s = shape(idata, chr(a) + chr(m))
                        if [x[0] for x in s] == [gid[cmap[a]], gid[cmap[m]]]:
                            if (s[1][2] + s[0][1] - s[0][2], s[1][3] - s[0][3]) != at:
                                sys.exit("%s %s: the mark evaluator disagrees with HarfBuzz on %s + U+%04X" % (name, spec, chr(a), m))
                            hbruns += 1
            if "GSUB" in inst and getattr(vf["GSUB"].table, "FeatureVariations", None) is not None:
                gsub = inst["GSUB"].table
                rl = rvrn_lookups(gsub)
                for c in FV_CHARS:
                    if c not in cmap: continue
                    got = gid[single_subst(gsub, rl, cmap[c])]
                    out.append("S %d %d" % (c, got))
                    hbg = shape(data, chr(c), loc)[0][0]                 # HarfBuzz applies rvrn to the variable font at the location
                    if hbg != got: sys.exit("%s %s: U+%04X becomes glyph %d in fontTools' instance, %d in HarfBuzz" % (name, spec, c, got, hbg))
                    hbruns += 1
            out.append("end")
        open(expect_file(name), "w").write("\n".join(out) + "\n")
        print("wrote", os.path.basename(expect_file(name)), len(specs), "locations,", len(out), "lines,", os.path.getsize(expect_file(name)), "bytes")
    print("the kerning, mark and rvrn evaluators agree with HarfBuzz on", hbruns, "runs")


# ---------------------------------------------------------------------------------------------------------------------------------
# verify: FontInstancer's instances (the suite's --dump) against fontTools, HarfBuzz and FreeType
# ---------------------------------------------------------------------------------------------------------------------------------

def dump_name(name, spec):
    """The file the suite's --dump writes for fixture `name` at `spec` (FontInstancerTest.dumpName)."""
    base = os.path.basename(expect_file(name))[:-len(".expect.txt")]
    return base + "@" + spec.replace("#", "i").replace(":", "-").replace("=", "-").replace(",", "_") + ".ttf"


def glyph_diff(a, an, b, bn):
    """"structure" when glyph an of font a and glyph bn of font b are built differently, else None; with their largest coordinate
    difference."""
    ga, gb = a["glyf"][an], b["glyf"][bn]
    if ga.isComposite() != gb.isComposite() or ga.numberOfContours != gb.numberOfContours: return "structure", 0
    if ga.isComposite():
        if [a.getGlyphID(c.glyphName) for c in ga.components] != [b.getGlyphID(c.glyphName) for c in gb.components]: return "structure", 0
        d = max((max(abs(getattr(c, "x", 0) - getattr(e, "x", 0)), abs(getattr(c, "y", 0) - getattr(e, "y", 0)))
                 for c, e in zip(ga.components, gb.components)), default=0)
        return None, d
    if ga.numberOfContours <= 0: return None, 0
    if list(ga.endPtsOfContours) != list(gb.endPtsOfContours) or [f & 1 for f in ga.flags] != [f & 1 for f in gb.flags]:
        return "structure", 0
    return None, max((max(abs(p[0] - q[0]), abs(p[1] - q[1])) for p, q in zip(ga.coordinates, gb.coordinates)), default=0)


def against_fonttools(mine, ft):
    """How far FontInstancer's instance is from fontTools' (glyph by glyph id): glyphs with other points and their largest difference,
    advance / bearing / bounds differences (count, largest) and the metrics that differ."""
    r = {"glyphs": 0, "points": 0, "pmax": 0, "adv": 0, "amax": 0, "lsb": 0, "lmax": 0, "bbox": 0, "bmax": 0, "structure": 0, "metrics": []}
    for mn, fn in zip(mine.getGlyphOrder(), ft.getGlyphOrder()):
        r["glyphs"] += 1
        if "glyf" in ft:
            why, d = glyph_diff(mine, mn, ft, fn)
            if why: r["structure"] += 1
            elif d:
                r["points"] += 1
                r["pmax"] = max(r["pmax"], d)
            bd = max(abs(x - y) for x, y in zip(bounds(mine, mn), bounds(ft, fn)))
            if bd:
                r["bbox"] += 1
                r["bmax"] = max(r["bmax"], bd)
        (a1, l1), (a2, l2) = mine["hmtx"][mn], ft["hmtx"][fn]
        if a1 != a2:
            r["adv"] += 1
            r["amax"] = max(r["amax"], abs(a1 - a2))
        if l1 != l2 and "glyf" in ft:                       # fontTools' CFF2 instance keeps the default's bearings
            r["lsb"] += 1
            r["lmax"] = max(r["lmax"], abs(l1 - l2))
    for t, f in METRICS:
        if "glyf" not in ft and (t in ("maxp", "head") or f in ("minLeftSideBearing", "minRightSideBearing", "xMaxExtent")):
            continue                                        # fontTools' CFF2 instance: exact cubic bounds, the default's bearings
        if t in ft and t in mine:
            v1, v2 = metric(mine, t, f), metric(ft, t, f)
            if v1 != v2: r["metrics"].append("%s.%s %s/%s" % (t, f, v1, v2))
    return r


def texts(vf):
    """What verify shapes: every character of the font (one by one: a run of stacked marks adds up each mark's rounding, so its last mark
    can sit several units from where the variable font puts it while every mark is within 1 of the one it sits on), the kerning letters in
    pairs, bases with marks, the FeatureVariations characters."""
    cmap = vf.getBestCmap()
    chars = " ".join(chr(c) for c in sorted(cmap) if c > 0x20 and not 0x300 <= c < 0x370)
    pairs = " ".join(a + b for a in KERN for b in KERN if ord(a) in cmap and ord(b) in cmap)
    marks = " ".join(b + chr(m) for b in BASES for m in MARKS if ord(b) in cmap and m in cmap)
    fv = "".join(chr(c) for c in FV_CHARS if c in cmap)
    return [t for t in (chars, pairs, marks, fv) if t]


def against_harfbuzz(vf_data, mine_data, loc, runs):
    """Shapes each text in the variable font at loc (set_variations) and in the instance: (glyphs, runs with other glyphs, largest
    advance difference, largest offset difference)."""
    glyphs = other = amax = omax = 0
    for t in runs:
        a, b = shape(vf_data, t, loc), shape(mine_data, t)
        glyphs += len(a)
        if [x[0] for x in a] != [x[0] for x in b]:
            other += 1
            continue
        for x, y in zip(a, b):
            amax = max(amax, abs(x[1] - y[1]))
            omax = max(omax, abs(x[2] - y[2]), abs(x[3] - y[3]))
    return glyphs, other, amax, omax


def overlap_flags(data, on):
    """The font with OVERLAP_SIMPLE on every simple glyph's first point and OVERLAP_COMPOUND on every composite's first component, as
    FontInstancer sets them (on), or with none (off). FreeType renders a flagged glyph supersampled, so the pixels are compared with the
    variable font flagged the same way (a CFF2 font has no flags: the instance is compared without them)."""
    f = TTFont(io.BytesIO(data))
    for n in f.getGlyphOrder():
        g = f["glyf"][n]
        if g.isComposite():
            for i, c in enumerate(g.components): c.flags = (c.flags | 0x400) if on and i == 0 else (c.flags & ~0x400)
        elif g.numberOfContours > 0:
            g.flags[0] = (g.flags[0] | 0x40) if on else (g.flags[0] & ~0x40)
    b = io.BytesIO()
    f.save(b)
    return b.getvalue()


def ft_face(data, loc=None, tags=None):
    import freetype
    face = freetype.Face(io.BytesIO(data))
    if loc is not None: face.set_var_design_coords([loc[t] for t in tags])
    return face


def ft_outline(face, gid, flags):
    face.load_glyph(gid, flags)
    o = face.glyph.outline
    return list(o.points), list(o.contours), [t & 1 for t in o.tags], face.glyph.linearHoriAdvance / 65536.0


def ft_bitmap(face, gid, flags):
    import freetype, numpy
    face.load_glyph(gid, flags | freetype.FT_LOAD_RENDER)
    bm, g = face.glyph.bitmap, face.glyph
    a = numpy.array(bm.buffer, dtype=numpy.int16).reshape(bm.rows, bm.pitch)[:, :bm.width] if bm.rows else numpy.zeros((0, 0), numpy.int16)
    return g.bitmap_left, g.bitmap_top, a


def pixel_diff(p, q):
    """(pixels covered by either, largest difference, pixels more than 16 apart) of two bitmaps placed at their offsets."""
    import numpy
    (l1, t1, a1), (l2, t2, a2) = p, q
    left, top = min(l1, l2), max(t1, t2)
    w = max(l1 + a1.shape[1], l2 + a2.shape[1]) - left
    h = top - min(t1 - a1.shape[0], t2 - a2.shape[0])
    c1, c2 = numpy.zeros((h, w), numpy.int16), numpy.zeros((h, w), numpy.int16)
    c1[top - t1:top - t1 + a1.shape[0], l1 - left:l1 - left + a1.shape[1]] = a1
    c2[top - t2:top - t2 + a2.shape[0], l2 - left:l2 - left + a2.shape[1]] = a2
    d = numpy.abs(c1 - c2)
    return int(numpy.count_nonzero((c1 > 0) | (c2 > 0))), int(d.max()) if d.size else 0, int(numpy.count_nonzero(d > 16))


def against_freetype(vf_data, mine_data, loc, tags, upem, cff2, hinted, adv_data=None):
    """FreeType, variable font at loc against the instance: unhinted outlines at 1 unit = 1 pixel (glyphs built otherwise, the largest
    point difference of simple glyphs and of composites, in units; the largest advance difference, read from adv_data when given) and
    48-pixel renderings (largest pixel difference of 255, pixels more than 16 apart); for a hinted font also the largest hinted 16-pixel
    point difference."""
    import freetype
    nh = freetype.FT_LOAD_NO_HINTING | freetype.FT_LOAD_NO_BITMAP
    vf, mine = ft_face(vf_data, loc, tags), ft_face(mine_data)
    adv = ft_face(adv_data, loc, tags) if adv_data is not None and adv_data != vf_data else vf
    composite = set()
    if not cff2:
        t = TTFont(io.BytesIO(mine_data))
        composite = {i for i, n in enumerate(t.getGlyphOrder()) if t["glyf"][n].isComposite()}
    r = {"glyphs": mine.num_glyphs, "structure": 0, "simple": 0.0, "composite": 0.0, "amax": 0.0, "pxmax": 0, "pxover": 0, "pixels": 0,
         "hinted": 0.0}
    for f in (vf, mine, adv): f.set_pixel_sizes(0, upem)
    for g in range(mine.num_glyphs):
        p1, c1, t1, a1 = ft_outline(vf, g, nh)
        p2, c2, t2, a2 = ft_outline(mine, g, nh)
        if adv is not vf: a1 = ft_outline(adv, g, nh)[3]
        r["amax"] = max(r["amax"], abs(a1 - a2))
        if cff2: continue                                  # cubic against quadratic: compared as pixels only
        if c1 != c2 or t1 != t2:
            r["structure"] += 1
            continue
        if p1:
            k = "composite" if g in composite else "simple"
            r[k] = max(r[k], max(max(abs(x1 - x2), abs(y1 - y2)) for (x1, y1), (x2, y2) in zip(p1, p2)) / 64.0)
    if cff2: vf2, mine2 = vf, ft_face(overlap_flags(mine_data, False))
    else: vf2, mine2 = ft_face(overlap_flags(vf_data, True), loc, tags), mine
    for f in (vf2, mine2): f.set_pixel_sizes(0, 48)
    for g in range(mine.num_glyphs):
        n, m, o = pixel_diff(ft_bitmap(vf2, g, nh), ft_bitmap(mine2, g, nh))
        r["pixels"] += n
        r["pxmax"] = max(r["pxmax"], m)
        r["pxover"] += o
    if hinted:
        for f in (vf, mine): f.set_pixel_sizes(0, 16)
        for g in range(mine.num_glyphs):
            p1, c1, t1, _ = ft_outline(vf, g, freetype.FT_LOAD_NO_BITMAP)
            p2, c2, t2, _ = ft_outline(mine, g, freetype.FT_LOAD_NO_BITMAP)
            if c1 == c2 and p1:
                r["hinted"] = max(r["hinted"], max(max(abs(x1 - x2), abs(y1 - y2)) for (x1, y1), (x2, y2) in zip(p1, p2)) / 64.0)
    return r


def engine_font(name):
    """The variable font as HarfBuzz and FreeType are given it, and the one FreeType draws its outlines from: the font as it is, but
    synth-gvar.ttf is drawn by FreeType without its HVAR. Its glyph H moves gvar's left phantom point, which HarfBuzz, fontTools and the
    app move the outline by, and which FreeType leaves where it is when the font has HVAR (the real fixtures do not move it)."""
    data = open(path(name), "rb").read()
    if name != "synth-gvar.ttf": return data, data
    f = TTFont(path(name))
    del f["HVAR"]
    b = io.BytesIO()
    f.save(b)
    return data, b.getvalue()


def verify(args):
    bad, tot = [], {"locations": 0, "glyphs": 0, "hb": 0, "ftpoints": 0, "ownpoints": 0, "simple": 0.0, "composite": 0.0, "hbadv": 0,
                    "hboff": 0, "pxmax": 0, "pxover": 0, "pixels": 0, "hinted": 0.0}
    for name, specs in LOCATIONS.items():
        data = open(path(name), "rb").read()
        edata, ftdata = engine_font(name)
        vf = TTFont(path(name))
        tags = [a.axisTag for a in vf["fvar"].axes]
        upem, cff2 = vf["head"].unitsPerEm, "CFF2" in vf
        runs = texts(vf)
        for spec in specs:
            mine_data = open(os.path.join(args.dir, dump_name(name, spec)), "rb").read()
            mine = TTFont(io.BytesIO(mine_data))
            loc = user_location(vf, spec)
            norm, ftnorm = hb_normalized(data, loc), ft_normalized(vf, loc)
            at_hb = against_fonttools(mine, ft_instance(name, loc, norm)[0])
            own = against_fonttools(mine, ft_instance(name, loc)[0]) if ftnorm != norm else at_hb
            sh = against_harfbuzz(edata, mine_data, loc, runs)
            fr = against_freetype(ftdata, mine_data, loc, tags, upem, cff2, "fpgm" in vf, edata)
            tot["locations"] += 1
            tot["glyphs"] += at_hb["glyphs"]
            tot["hb"] += sh[0]
            tot["ftpoints"] += at_hb["points"] + at_hb["structure"]
            tot["ownpoints"] += own["points"] + own["structure"]
            for k in ("simple", "composite", "pxmax", "hinted"): tot[k] = max(tot[k], fr[k])
            tot["hbadv"], tot["hboff"] = max(tot["hbadv"], sh[2]), max(tot["hboff"], sh[3])
            tot["pxover"] += fr["pxover"]
            tot["pixels"] += fr["pixels"]
            print("%s %s:\n  fontTools at HarfBuzz's location: %d of %d glyphs with other points (max %d), %d built otherwise, advances %d "
                  "(max %d), bearings %d (max %d), bounds %d (max %d), metrics %s\n  fontTools at its own location (%s): %d glyphs with other "
                  "points (max %d), advances %d (max %d)\n  HarfBuzz: %d glyphs, %d runs with other glyphs, advances max %d, offsets max %d\n"
                  "  FreeType: %d built otherwise, points max %.3f (simple) %.3f (composite), advances max %.3f, 48 px: max %d of 255, %d of "
                  "%d pixels more than 16 apart%s"
                  % (os.path.basename(name), spec, at_hb["points"], at_hb["glyphs"], at_hb["pmax"], at_hb["structure"], at_hb["adv"],
                     at_hb["amax"], at_hb["lsb"], at_hb["lmax"], at_hb["bbox"], at_hb["bmax"], ", ".join(at_hb["metrics"]) or "equal",
                     "the same" if ftnorm == norm else "1 apart", own["points"] + own["structure"], own["pmax"], own["adv"], own["amax"],
                     sh[0], sh[1], sh[2], sh[3], fr["structure"], fr["simple"], fr["composite"], fr["amax"], fr["pxmax"], fr["pxover"],
                     fr["pixels"], "; hinted at 16 px: points max %.3f px" % fr["hinted"] if "fpgm" in vf else ""))
            if (at_hb["structure"] or at_hb["pmax"] > 1 or at_hb["amax"] > 1 or sh[1] or sh[2] > 1 or sh[3] > 1 or fr["structure"]
                    or fr["simple"] > 1 or fr["composite"] > 2 or fr["amax"] > 1):
                bad.append(os.path.basename(name) + " " + spec)
    print("\n%d locations, %d glyphs. fontTools (at HarfBuzz's location): %d glyphs with other points; at its own location: %d. HarfBuzz: "
          "%d glyphs shaped, advances max %d, offsets max %d apart. FreeType: points max %.3f (simple glyphs) and %.3f (composites) units "
          "apart, 48 px renderings max %d of 255 and %d of %d pixels more than 16 apart, hinted 16 px points max %.3f px apart"
          % (tot["locations"], tot["glyphs"], tot["ftpoints"], tot["ownpoints"], tot["hb"], tot["hbadv"], tot["hboff"], tot["simple"],
             tot["composite"], tot["pxmax"], tot["pxover"], tot["pixels"], tot["hinted"]))
    if bad: sys.exit("outside the limits: " + "; ".join(bad))
    print("every instance within the limits (fontTools and HarfBuzz 1 unit, FreeType 1 unit for simple glyphs and 2 for composites)")


# ---------------------------------------------------------------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fonts")
    for k in ("inter", "notosans", "robotoflex", "ss3", "roboto"): f.add_argument("--" + k, required=True)
    sub.add_parser("expect")
    v = sub.add_parser("verify")
    v.add_argument("dir")
    a = ap.parse_args()
    {"fonts": make_fonts, "expect": expect, "verify": verify}[a.cmd](a)


if __name__ == "__main__":
    main()
