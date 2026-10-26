#!/usr/bin/env python3
"""Fixtures and expected values of the emojimerge Java suite (EmojiMergeTest). Dev time only: CI runs the suite with the JDK alone.

Needs fontTools (4.67), uharfbuzz (0.57, HarfBuzz 15) and freetype-py (FreeType 2.13). Run with python -I, from anywhere:

  make_fixtures.py fonts --lato F --inter F --notosans F --noto F --twemoji F
      makes the fixture fonts next to this script from the downloads named in README.md: three text fonts (Lato and static instances of
      Inter and Noto Sans, subset to Latin-1 and the symbols emoji fonts also have; Noto Sans without GSUB / GPOS / GDEF and with a kern
      table instead), two emoji fonts (Noto Color Emoji and Twemoji subset to the SEQUENCES below, with their COLR, CPAL, GSUB and hinting)
      and two synthetic emoji fonts (every COLR v1 paint format, PaintColrGlyph, a glyph both drawn by a paint and mapped by the cmap,
      GDEF classes and lookup flags; COLR v0 with composite, hinted layers)
  make_fixtures.py expect
      writes expected.txt: each fixture's glyph count, units per em and SHA-256, and what HarfBuzz shapes each sequence and text run to with
      each font alone (glyph ids, default ignorables removed). Sequences also for "Synth-COLRv1.ttf/noGDEF" (that font without its GDEF, as
      it shapes in a merged font whose text font has none: the merge leaves the emoji classes out then); text runs for the script HarfBuzz
      guesses and for Common (Zyyy), each line naming the script
  java ... com.bloatware.bingblop.EmojiMergeTest --dump DIR
      (the suite's dump mode) writes the merged fonts the suite makes, one per MERGES pair
  make_fixtures.py verify DIR
      checks those fonts against their two sources (the checks of `check` below, with the SEQUENCES instead of emoji-test.txt) and records
      the SHA-256 of each one that passed in expected.txt; the suite notes a merged font whose hash is not there
  make_fixtures.py check TEXT EMOJI MERGED --emoji-test FILE
      the same checks on full fonts merged at dev time, with every sequence of Unicode's emoji-test.txt: HarfBuzz shapes each sequence as
      the emoji font alone (its glyph ids + the text font's glyph count; each glyph advancing by the emoji font's own hmtx advance times s,
      as the merge leaves an emoji GPOS out), every character of the emoji font alone, with U+FE0F and with U+FE0E as the rules say, every
      text character and text run as the text font alone, sequences in 12 scripts (right to left in Arabic and Hebrew); fontTools reads
      every table and glyph; FreeType walks the COLR v0 layers (and renders them) or the COLR v1 paint graph and clip boxes; HarfBuzz
      rasterizes colour glyphs of both fonts at 128 px and compares the pixels
"""
import argparse, ctypes, hashlib, io, os, re, sys, tempfile, time

from fontTools.ttLib import TTFont, newTable
from fontTools.ttLib.tables import otTables as ot
from fontTools.ttLib.tables import ttProgram
from fontTools.ttLib.tables._k_e_r_n import KernTable_format_0
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.colorLib.builder import buildCOLR, buildCPAL
from fontTools.feaLib.builder import addOpenTypeFeaturesFromString
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.misc.timeTools import timestampFromString
from fontTools import subset
import uharfbuzz as hb

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", "..", "..", ".."))
EXPECTED = os.path.join(HERE, "expected.txt")
EMOJIDATA = os.path.join(REPO, "src", "com", "bloatware", "bingblop", "EmojiData.java")

# The sequences the suite shapes: plain emoji, VS16 / VS15 forms, keycaps, skin tones, ZWJ sequences (fully and minimally qualified), flags,
# subdivision flags, text-default characters alone and with a selector
SEQUENCES = """
1F600; 1F44D; 1F44D 1F3FD; 2764; 2764 FE0F; 2764 FE0E; 263A; 263A FE0F; A9; A9 FE0F; A9 FE0E; AE FE0F; 2122; 2122 FE0F; 2122 FE0E;
2194; 2194 FE0F; 2194 FE0E; 23 FE0F 20E3; 31 FE0F 20E3; 31 20E3; 2A FE0F 20E3; 31; 31 FE0F; 1F468 200D 1F469 200D 1F467 200D 1F466;
1F469 200D 1F4BB; 1F469 1F3FD 200D 2695 FE0F; 1F469 1F3FD 200D 2695; 1F3C3 200D 2640 FE0F; 1F3C3 200D 2640; 1F642 200D 2194 FE0F;
1F642 200D 2194; 1F1FA 1F1F8; 1F1EF 1F1F5; 1F1FA; 1F3F4 E0067 E0062 E0065 E006E E0067 E007F; 1F3F4 E0067 E0062 E0073 E0063 E0074 E007F;
1F3F4 E0067 E007F; 1F3F4; 2764 FE0F 200D 1F525; 2764 200D 1F525; 261D 1F3FD; 261D; 2640; 2640 FE0F; 2B50; 1F3F3 FE0F 200D 1F308;
1F600 1F44D 1F3FF
"""
SYNTH_SEQUENCES = """
1F7E0; 1F7E0 1F7E0; 1F7E0 302 1F7E0; 1F7E1; 1F7E2; 1F7E3; 1F7E3 302; 1F7E4; 1F7E4 302; 1F7E5; 1F7E6; 1F7E7; 1F7E7 FE0F; 1F7E0 200D 1F7E1
"""
# Text runs shaped with each text font (the text font's own GSUB must give the same glyphs in the merged font)
RUNS = ["AVATAR Toyota WAVE Te Yo LT", "office affluent fjord ffi ffl fi fl", "Hello, World! 0123456789 #1 *2",
        "café naïve Ångström àéîõü", "© 2026 ® ™ (c) ↔ ↕",
        "1/2 ½ ¼ x² ªº", "«» ¡¿ §¶ ° ± × ÷"]
TEXT_SET = "U+0020-007E,U+00A0-00FF,U+2122,U+2194,U+2195,U+2764,U+200D,U+2640,U+263A,U+261D,U+0301,U+2013,U+2014,U+2018,U+2019,U+201C,U+201D"

TEXT_FONTS = {"Lato-sub.ttf": "lato", "Inter-sub.ttf": "inter", "NotoSans-kern.ttf": "notosans"}
EMOJI_FONTS = {"Noto-sub.ttf": "noto", "Twemoji-sub.ttf": "twemoji"}
SYNTH_FONTS = ["Synth-COLRv1.ttf", "Synth-COLRv0.ttf"]
# the pairs the suite merges (text, emoji)
MERGES = [(t, e) for t in TEXT_FONTS for e in EMOJI_FONTS] + [("Inter-sub.ttf", "Synth-COLRv1.ttf"), ("NotoSans-kern.ttf", "Synth-COLRv1.ttf"),
                                                              ("Lato-sub.ttf", "Synth-COLRv0.ttf")]


def path(name): return os.path.join(HERE, name)


def parse_seqs(text):
    return [[int(c, 16) for c in s.split()] for s in text.replace("\n", " ").split(";") if s.strip()]


def fmt(cps): return " ".join("%X" % c for c in cps)


def sha(data): return hashlib.sha256(data).hexdigest()


FIXED_TIME = timestampFromString("Thu Jan  1 00:00:00 2026")


def save(font, out):
    font.recalcTimestamp = False                                    # the same inputs give the same file
    font.save(out)
    print(out, os.path.getsize(out))


# ---------------------------------------------------------------------------------------------------------------------------------------
# fonts

def rename(font, old, new):
    for rec in font["name"].names:
        if rec.nameID in (0, 7, 13, 14):
            continue                                                # copyright, trademark and license notices stay as they are
        s = rec.toUnicode()
        if old in s:
            rec.string = s.replace(old, new)


def subset_font(font, unicodes, hinting=True, closure=True):
    opts = subset.Options()
    opts.layout_features = ["*"]
    opts.layout_closure = closure
    opts.name_IDs = ["*"]
    opts.name_languages = ["*"]
    opts.notdef_outline = True
    opts.glyph_names = False
    opts.hinting = hinting
    opts.drop_tables += ["DSIG"]
    s = subset.Subsetter(opts)
    s.populate(unicodes=unicodes)
    s.subset(font)
    return font


def unicodes_of(spec):
    out = []
    for part in spec.split(","):
        part = part.strip()[2:]
        a, _, b = part.partition("-")
        out += list(range(int(a, 16), int(b or a, 16) + 1))
    return out


def kern_pairs(font_bytes, glyph_order, best):
    """The kerning of letter pairs as HarfBuzz applies it from GPOS (advance with kern minus without), for a kern table."""
    face = hb.Face(font_bytes)
    f = hb.Font(face)
    pairs = {}
    letters = [c for c in "AVTWYLPFKRXOCacdegoqsuvwy.,"]
    for a in letters:
        for b in letters:
            res = []
            for kern in (True, False):
                buf = hb.Buffer()
                buf.add_str(a + b)
                buf.guess_segment_properties()
                hb.shape(f, buf, {"kern": kern})
                res.append((buf.glyph_infos, buf.glyph_positions))
            (gi, pk), (_, pn) = res
            d = pk[0].x_advance - pn[0].x_advance
            if d and len(gi) == 2:
                pairs[(glyph_order[gi[0].codepoint], glyph_order[gi[1].codepoint])] = d
    return pairs


def make_text_fonts(a):
    f = subset_font(TTFont(a.lato), unicodes_of(TEXT_SET), hinting=True)
    rename(f, "Lato", "Fixture L")                                   # "Lato" is a Reserved Font Name
    save(f, path("Lato-sub.ttf"))
    inter = instantiateVariableFont(TTFont(a.inter), {"wght": 400, "opsz": 14}, updateFontNames=False)
    f = subset_font(inter, unicodes_of(TEXT_SET), hinting=False)
    save(f, path("Inter-sub.ttf"))
    noto = instantiateVariableFont(TTFont(a.notosans), {"wght": 400, "wdth": 100}, updateFontNames=False)
    f = subset_font(noto, unicodes_of(TEXT_SET), hinting=False)
    b = io.BytesIO()
    f.recalcTimestamp = False
    f.save(b)
    pairs = kern_pairs(b.getvalue(), f.getGlyphOrder(), f.getBestCmap())
    for tag in ("GSUB", "GPOS", "GDEF"):
        if tag in f: del f[tag]
    kern = newTable("kern")
    kern.version = 0
    st = KernTable_format_0()
    st.version, st.coverage, st.format = 0, 1, 0
    st.kernTable = pairs
    kern.kernTables = [st]
    f["kern"] = kern
    save(f, path("NotoSans-kern.ttf"))


def make_emoji_fonts(a):
    cps = sorted({c for s in parse_seqs(SEQUENCES) for c in s} | set(range(0x1F3FB, 0x1F400)) | {0xFE0E, 0xFE0F, 0x200D, 0x20E3})
    f = subset_font(TTFont(a.noto), cps)
    save(f, path("Noto-sub.ttf"))
    f = subset_font(TTFont(a.twemoji), cps, hinting=True)
    save(f, path("Twemoji-sub.ttf"))


def draw(pts_list, components=None, glyphset=None):
    pen = TTGlyphPen(glyphset)
    for pts in pts_list:
        pen.moveTo(pts[0])
        for p in pts[1:]:
            pen.lineTo(p)
        pen.closePath()
    for name, (dx, dy) in components or []:
        pen.addComponent(name, (1, 0, 0, 1, dx, dy))
    return pen.glyph()


def solid(i, alpha=1.0): return {"Format": ot.PaintFormat.PaintSolid, "PaletteIndex": i, "Alpha": alpha}


def line(var=False):
    return {"Extend": "pad", "ColorStop": [dict({"StopOffset": 0.0, "PaletteIndex": 0, "Alpha": 1.0}, **({"VarIndexBase": 0xFFFFFFFF} if var else {})),
                                           dict({"StopOffset": 1.0, "PaletteIndex": 1, "Alpha": 1.0}, **({"VarIndexBase": 0xFFFFFFFF} if var else {}))]}


def pglyph(name, paint): return {"Format": ot.PaintFormat.PaintGlyph, "Glyph": name, "Paint": paint}


def make_synth_v1():
    """Every COLR v1 paint format; PaintColrGlyph; 'both' drawn by a paint and mapped by the cmap; GDEF classes, lookup flags, GPOS."""
    order = [".notdef", "space", "sq", "circ", "tri", "comp", "both", "mk", "e1", "e2", "e3", "e4", "lig"]
    fb = FontBuilder(1000, isTTF=True)
    fb.setupGlyphOrder(order)
    # the mark is U+0302, which the text fixtures do not have (their U+0301 would take its place in a merged font)
    fb.setupCharacterMap({0x20: "space", 0x302: "mk", 0x1F7E0: "e1", 0x1F7E1: "e2", 0x1F7E2: "both", 0x1F7E3: "e3", 0x1F7E4: "e4"})
    sq = [[(100, 0), (100, 800), (900, 800), (900, 0)]]
    glyphs = {".notdef": draw([[(50, 0), (50, 700), (450, 700), (450, 0)]]), "space": draw([]), "sq": draw(sq),
              "circ": draw([[(500, 0), (150, 150), (0, 500), (150, 850), (500, 1000), (850, 850), (1000, 500), (850, 150)]]),
              "tri": draw([[(0, -100), (500, 900), (1000, -100)]]), "both": draw([[(500, -50), (0, 400), (500, 850), (1000, 400)]]),
              "mk": draw([[(200, 700), (200, 900), (400, 900), (400, 700)]]), "e1": draw(sq), "e2": draw([]), "e3": draw([]),
              "e4": draw([]), "lig": draw([])}
    glyphs["comp"] = draw([], [("sq", (0, 0)), ("tri", (37, -63))], glyphs)
    fb.setupGlyf(glyphs)
    fb.setupHorizontalMetrics({n: (0 if n == "mk" else 1000, 0) for n in order})
    fb.setupHorizontalHeader(ascent=900, descent=-200)
    fb.setupNameTable({"familyName": "Fixture Synth COLRv1", "styleName": "Regular"})
    fb.setupOS2(sTypoAscender=900, sTypoDescender=-200, usWinAscent=900, usWinDescent=200)
    fb.setupPost()
    V = 0xFFFFFFFF
    e1 = {"Format": ot.PaintFormat.PaintColrLayers, "Layers": [
        pglyph("sq", solid(0)),
        pglyph("circ", {"Format": ot.PaintFormat.PaintLinearGradient, "ColorLine": line(), "x0": 0, "y0": 0, "x1": 800, "y1": 800, "x2": 0, "y2": 800}),
        pglyph("comp", {"Format": ot.PaintFormat.PaintVarSolid, "PaletteIndex": 2, "Alpha": 0.5, "VarIndexBase": V})]}
    e2 = {"Format": ot.PaintFormat.PaintComposite, "CompositeMode": "src_over",
          "SourcePaint": {"Format": ot.PaintFormat.PaintTranslate, "dx": 100, "dy": -50, "Paint": pglyph("both", {
              "Format": ot.PaintFormat.PaintRadialGradient, "ColorLine": line(), "x0": 400, "y0": 400, "r0": 0, "x1": 450, "y1": 420, "r1": 300})},
          "BackdropPaint": {"Format": ot.PaintFormat.PaintTransform, "Transform": {"xx": 0.9, "yx": 0.1, "xy": -0.1, "yy": 0.9, "dx": 30, "dy": 40},
                            "Paint": pglyph("tri", {"Format": ot.PaintFormat.PaintSweepGradient, "ColorLine": line(), "centerX": 400,
                                                    "centerY": 400, "startAngle": 0, "endAngle": 270})}}
    e3 = {"Format": ot.PaintFormat.PaintTranslate, "dx": 200, "dy": 0, "Paint": {"Format": ot.PaintFormat.PaintColrGlyph, "Glyph": "e1"}}
    P = ot.PaintFormat
    leaf = lambda k: pglyph(["sq", "tri", "circ"][k % 3], solid(k % 3))
    wraps = [
        {"Format": P.PaintVarTransform, "Transform": {"xx": 1.0, "yx": 0.0, "xy": 0.2, "yy": 1.0, "dx": 10, "dy": 0, "VarIndexBase": V},
         "Paint": pglyph("sq", {"Format": P.PaintVarLinearGradient, "ColorLine": line(True), "x0": 0, "y0": 0, "x1": 900, "y1": 0, "x2": 0,
                                "y2": 900, "VarIndexBase": V})},
        {"Format": P.PaintVarTranslate, "dx": 10, "dy": 10, "VarIndexBase": V,
         "Paint": pglyph("circ", {"Format": P.PaintVarRadialGradient, "ColorLine": line(True), "x0": 500, "y0": 500, "r0": 10, "x1": 500,
                                  "y1": 500, "r1": 500, "VarIndexBase": V})},
        {"Format": P.PaintScale, "scaleX": 1.1, "scaleY": 0.9, "Paint": pglyph("tri", {
            "Format": P.PaintVarSweepGradient, "ColorLine": line(True), "centerX": 500, "centerY": 400, "startAngle": 10, "endAngle": 350,
            "VarIndexBase": V})},
        {"Format": P.PaintVarScale, "scaleX": 0.8, "scaleY": 1.0, "VarIndexBase": V, "Paint": leaf(0)},
        {"Format": P.PaintScaleAroundCenter, "scaleX": 0.5, "scaleY": 0.7, "centerX": 500, "centerY": 400, "Paint": leaf(1)},
        {"Format": P.PaintVarScaleAroundCenter, "scaleX": 0.6, "scaleY": 0.6, "centerX": -300, "centerY": 1200, "VarIndexBase": V, "Paint": leaf(2)},
        {"Format": P.PaintScaleUniform, "scale": 0.9, "Paint": leaf(3)},
        {"Format": P.PaintVarScaleUniform, "scale": 0.4, "VarIndexBase": V, "Paint": leaf(4)},
        {"Format": P.PaintScaleUniformAroundCenter, "scale": 0.3, "centerX": 500, "centerY": 500, "Paint": leaf(5)},
        {"Format": P.PaintVarScaleUniformAroundCenter, "scale": 0.2, "centerX": 900, "centerY": 100, "VarIndexBase": V, "Paint": leaf(6)},
        {"Format": P.PaintRotate, "angle": 15, "Paint": leaf(7)},
        {"Format": P.PaintVarRotate, "angle": -15, "VarIndexBase": V, "Paint": leaf(8)},
        {"Format": P.PaintRotateAroundCenter, "angle": 45, "centerX": 500, "centerY": 400, "Paint": leaf(9)},
        {"Format": P.PaintVarRotateAroundCenter, "angle": 90, "centerX": 32000, "centerY": -32000, "VarIndexBase": V, "Paint": leaf(10)},
        {"Format": P.PaintSkew, "xSkewAngle": 10, "ySkewAngle": 0, "Paint": leaf(11)},
        {"Format": P.PaintVarSkew, "xSkewAngle": 0, "ySkewAngle": 10, "VarIndexBase": V, "Paint": leaf(12)},
        {"Format": P.PaintSkewAroundCenter, "xSkewAngle": -20, "ySkewAngle": 5, "centerX": 500, "centerY": 0, "Paint": leaf(13)},
        {"Format": P.PaintVarSkewAroundCenter, "xSkewAngle": 5, "ySkewAngle": -5, "centerX": 0, "centerY": 500, "VarIndexBase": V, "Paint": leaf(14)},
    ]
    e4 = {"Format": P.PaintColrLayers, "Layers": wraps}
    lig = pglyph("sq", solid(1))
    colr = buildCOLR({"e1": e1, "e2": e2, "e3": e3, "e4": e4, "lig": lig}, version=1,
                     glyphMap={n: i for i, n in enumerate(order)},
                     clipBoxes={"e1": (100, 0, 900, 800), "e2": (-100, -150, 1100, 1000), "lig": (99, -1, 901, 801), "e4": (-200, -200, 1200, 1200, V)})
    fb.font["COLR"] = colr
    fb.font["CPAL"] = buildCPAL([[(1, 0, 0, 1), (0, 0.5, 1, 1), (0.2, 0.8, 0.2, 1)]])
    addOpenTypeFeaturesFromString(fb.font, """
        languagesystem DFLT dflt;
        languagesystem latn dflt;
        table GDEF { GlyphClassDef [sq circ tri comp both e1 e2 e3 e4], [lig], [mk], ; } GDEF;
        markClass mk <anchor 0 0> @TOP;
        lookup Lig { lookupflag IgnoreMarks; sub e1 e1 by lig; } Lig;
        lookup Swap { lookupflag UseMarkFilteringSet [mk]; sub e4 by e2; } Swap;
        lookup Attach { lookupflag MarkAttachmentType @TOP; sub e3 mk by e1; } Attach;
        feature ccmp { lookup Lig; lookup Swap; lookup Attach; } ccmp;
        feature kern { pos e1 10; } kern;
    """)
    fb.font["head"].created = fb.font["head"].modified = FIXED_TIME
    save(fb.font, path("Synth-COLRv1.ttf"))


def make_synth_v0():
    """COLR v0 with a composite, hinted layer and a layer glyph the cmap maps too; units per em 2048, fpgm / prep."""
    order = [".notdef", "space", "a1", "a2", "b", "e1", "e2"]
    fb = FontBuilder(2048, isTTF=True)
    fb.setupGlyphOrder(order)
    fb.setupCharacterMap({0x20: "space", 0x1F7E5: "e1", 0x1F7E6: "e2", 0x1F7E7: "a1"})
    g = {".notdef": draw([[(100, 0), (100, 1400), (900, 1400), (900, 0)]]), "space": draw([]),
         "a1": draw([[(0, 0), (0, 1000), (1000, 1000), (1000, 0)]]), "b": draw([[(1023, 1), (513, 2047), (3, 1)]]),
         "e1": draw([[(0, 0), (0, 1800), (1800, 1800), (1800, 0)]]), "e2": draw([])}
    g["a2"] = draw([], [("a1", (0, 0)), ("a1", (801, 799))], g)
    prog = ttProgram.Program()
    prog.fromAssembly(["SVTCA[0]", "PUSHB[ ]", "1", "MDAP[1]"])
    g["a1"].program = prog
    prog2 = ttProgram.Program()
    prog2.fromAssembly(["SVTCA[1]"])
    g["a2"].program = prog2
    fb.setupGlyf(g)
    fb.setupHorizontalMetrics({"space": (512, 0), ".notdef": (1000, 100), "a1": (1000, 0), "a2": (1801, 0), "b": (1025, 3), "e1": (2048, 0),
                               "e2": (2047, 0)})
    fb.setupHorizontalHeader(ascent=1900, descent=-400)
    fb.setupNameTable({"familyName": "Fixture Synth COLRv0", "styleName": "Regular"})
    fb.setupOS2(sTypoAscender=1900, sTypoDescender=-400, usWinAscent=1900, usWinDescent=400)
    fb.setupPost()
    fpgm = newTable("fpgm"); fpgm.program = ttProgram.Program(); fpgm.program.fromAssembly(["PUSHB[ ]", "0", "FDEF[ ]", "ENDF[ ]"])
    prep = newTable("prep"); prep.program = ttProgram.Program(); prep.program.fromAssembly(["SVTCA[0]"])
    fb.font["fpgm"], fb.font["prep"] = fpgm, prep
    fb.font["COLR"] = buildCOLR({"e1": [("a1", 0), ("a2", 1)], "e2": [("b", 2), ("a2", 0), ("a1", 1)]}, version=0)
    fb.font["CPAL"] = buildCPAL([[(1, 0, 0, 1), (0, 0, 1, 1), (0, 0.6, 0, 1)]])
    fb.font["head"].created = fb.font["head"].modified = FIXED_TIME
    save(fb.font, path("Synth-COLRv0.ttf"))


def cmd_fonts(a):
    make_text_fonts(a)
    make_emoji_fonts(a)
    make_synth_v1()
    make_synth_v0()


# ---------------------------------------------------------------------------------------------------------------------------------------
# shaping

class Shaper:
    def __init__(self, data):
        self.data = data
        self.face = hb.Face(data)
        self.font = hb.Font(self.face)
        self.upem = self.face.upem

    def shape(self, cps, script=None, lang=None, features=None, keep_ignorables=False):
        b = hb.Buffer()
        b.add_codepoints(cps)
        b.guess_segment_properties()
        if script:
            b.script = script
            b.direction = "rtl" if script in RTL else "ltr"              # as in a paragraph of that script (guessed: emoji are Common)
        b.language = lang or "und"                                    # not the dev machine's locale (a Turkish one picks Lato's TRK)
        if not keep_ignorables: b.flags = hb.BufferFlags.REMOVE_DEFAULT_IGNORABLES
        hb.shape(self.font, b, features or {})
        return [(i.codepoint, i.cluster) for i in b.glyph_infos], [(p.x_advance, p.y_advance, p.x_offset, p.y_offset) for p in b.glyph_positions]


def shaper(name): return Shaper(open(path(name), "rb").read())


def without(file, tag):
    """The font's bytes without one table."""
    f = TTFont(file)
    del f[tag]
    b = io.BytesIO()
    f.save(b)
    return b.getvalue()


def read_expected():
    if not os.path.exists(EXPECTED): return []
    return [l.rstrip("\n") for l in open(EXPECTED, encoding="utf-8") if l.strip() and not l.startswith("#")]


def write_expected(lines):
    head = ["# Written by make_fixtures.py (expect, verify): what fontTools and HarfBuzz say about the fixtures, for EmojiMergeTest. See README.md."]
    with open(EXPECTED, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(head + lines) + "\n")


def cmd_expect(a):
    lines = []
    for name in list(TEXT_FONTS) + list(EMOJI_FONTS) + SYNTH_FONTS:
        data = open(path(name), "rb").read()
        f = TTFont(path(name), lazy=True)
        lines.append("font %s %s %d %d" % (name, sha(data), f["maxp"].numGlyphs, f["head"].unitsPerEm))
    seqs, synth = parse_seqs(SEQUENCES), parse_seqs(SYNTH_SEQUENCES)
    shapers = [(name, shaper(name)) for name in list(TEXT_FONTS) + list(EMOJI_FONTS) + SYNTH_FONTS]
    # a merged font without GDEF (text font without one) shapes the emoji lookups with the classes HarfBuzz guesses from Unicode
    shapers.append(("Synth-COLRv1.ttf/noGDEF", Shaper(without(path("Synth-COLRv1.ttf"), "GDEF"))))
    for name, s in shapers:
        for cps in (synth if name.startswith("Synth") else seqs) + (synth if name in TEXT_FONTS else []):
            g, _ = s.shape(cps)
            lines.append("seq %s %s : %s" % (name, fmt(cps), " ".join(str(x) for x, _ in g)))
    for name in TEXT_FONTS:
        s = shaper(name)
        for r in RUNS:
            cps = [ord(c) for c in r]
            b = hb.Buffer()
            b.add_codepoints(cps)
            b.guess_segment_properties()
            for script in sorted({b.script or "Zyyy", "Zyyy"}):      # the script HarfBuzz guesses, and Common (DFLT)
                g, _ = s.shape(cps, script=script, keep_ignorables=True)
                lines.append("run %s %s %s : %s" % (name, script, fmt(cps), " ".join(str(x) for x, _ in g)))
    keep = [l for l in read_expected() if l.startswith("verified ")]
    write_expected(lines + keep)
    print("expected.txt:", len(lines), "lines")


# ---------------------------------------------------------------------------------------------------------------------------------------
# the checks on a merged font (verify, check)

def presentation_ranges():
    src = open(EMOJIDATA, encoding="utf-8").read()
    body = src[src.index("PRESENTATION = {") + len("PRESENTATION = {"):]
    body = body[:body.index("};")]
    vals = [int(x, 16) for x in re.findall(r"0x([0-9A-Fa-f]+)", body)]
    return [(vals[i], vals[i + 1]) for i in range(0, len(vals), 2)]


class Rules:
    """EmojiData.emojiWins and EmojiMerge's emoji bases, from the Java source's table."""
    def __init__(self):
        self.ranges = presentation_ranges()

    def emoji_wins(self, cp):
        if 0 <= cp <= 0xFF: return False
        return (any(a <= cp <= b for a, b in self.ranges) or 0x1F000 <= cp <= 0x1FFFF or cp in (0x200D, 0xFE0F, 0x20E3)
                or 0xE0020 <= cp <= 0xE007F)

    @staticmethod
    def emoji_base(cp):
        return cp > 0xFF or cp in (0x23, 0x2A, 0xA9, 0xAE) or 0x30 <= cp <= 0x39

    def text_expected(self, cps, tcmap):
        """True when the merged font must shape cps as the text font alone does (else as the emoji font alone)."""
        c = cps[0]
        if c not in tcmap: return False
        if len(cps) == 1: return not self.emoji_wins(c)
        if len(cps) == 2 and cps[1] == 0xFE0E: return True
        if len(cps) == 2 and cps[1] == 0xFE0F: return not self.emoji_wins(c) and not self.emoji_base(c)
        return False


class FT:
    """FreeType through ctypes: COLR v0 layers, the COLR v1 paint graph (FT_Get_Color_Glyph_Paint, FT_Get_Paint ...), clip boxes, bitmaps."""
    def __init__(self):
        import freetype, freetype.raw as raw
        self.freetype, self.lib = freetype, raw._lib
        L = self.lib

        class Paint(ctypes.Structure):
            _fields_ = [("format", ctypes.c_int), ("_pad", ctypes.c_int), ("u", ctypes.c_ubyte * 192)]

        class Opaque(ctypes.Structure):
            _fields_ = [("p", ctypes.c_void_p), ("insert_root_transform", ctypes.c_ubyte)]

        class LayerIt(ctypes.Structure):
            _fields_ = [("num_layers", ctypes.c_uint), ("layer", ctypes.c_uint), ("p", ctypes.c_void_p)]

        class StopIt(ctypes.Structure):
            _fields_ = [("num", ctypes.c_uint), ("current", ctypes.c_uint), ("p", ctypes.c_void_p), ("read_variable", ctypes.c_ubyte)]

        class Stop(ctypes.Structure):
            _fields_ = [("offset", ctypes.c_long), ("palette_index", ctypes.c_uint16), ("alpha", ctypes.c_int16)]

        class Vec(ctypes.Structure):
            _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]

        class Clip(ctypes.Structure):
            _fields_ = [("bl", Vec), ("tl", Vec), ("tr", Vec), ("br", Vec)]

        self.Paint, self.Opaque, self.LayerIt, self.StopIt, self.Stop, self.Clip = Paint, Opaque, LayerIt, StopIt, Stop, Clip
        for name, res, args in [("FT_Get_Color_Glyph_Paint", ctypes.c_ubyte, [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int, ctypes.POINTER(Opaque)]),
                                ("FT_Get_Paint", ctypes.c_ubyte, [ctypes.c_void_p, Opaque, ctypes.POINTER(Paint)]),
                                ("FT_Get_Paint_Layers", ctypes.c_ubyte, [ctypes.c_void_p, ctypes.POINTER(LayerIt), ctypes.POINTER(Opaque)]),
                                ("FT_Get_Colorline_Stops", ctypes.c_ubyte, [ctypes.c_void_p, ctypes.POINTER(Stop), ctypes.POINTER(StopIt)]),
                                ("FT_Get_Color_Glyph_ClipBox", ctypes.c_ubyte, [ctypes.c_void_p, ctypes.c_uint, ctypes.POINTER(Clip)]),
                                ("FT_Get_Color_Glyph_Layer", ctypes.c_ubyte, [ctypes.c_void_p, ctypes.c_uint, ctypes.POINTER(ctypes.c_uint),
                                                                              ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(LayerIt)])]:
            fn = getattr(L, name)
            fn.restype, fn.argtypes = res, args

    def face(self, file, ppem):
        try:
            f = self.freetype.Face(file)
            f.set_pixel_sizes(0, ppem)
            return f
        except Exception:
            # a font with bitmap strikes too (Fluent: CBDT, SVG): FreeType reads its COLR from a copy without them
            t = TTFont(file)
            for tag in ("CBDT", "CBLC", "sbix", "SVG ", "EBDT", "EBLC"):
                if tag in t: del t[tag]
            fd, tmp = tempfile.mkstemp(suffix=".ttf")
            os.close(fd)
            t.save(tmp)
            f = self.freetype.Face(tmp)
            os.unlink(tmp)
            f.set_pixel_sizes(0, ppem)
            return f

    def h(self, f): return ctypes.cast(f._FT_Face, ctypes.c_void_p)

    def layers(self, f, gid):
        it = self.LayerIt(0, 0, None)
        g, c = ctypes.c_uint(), ctypes.c_uint()
        out = []
        while self.lib.FT_Get_Color_Glyph_Layer(self.h(f), gid, ctypes.byref(g), ctypes.byref(c), ctypes.byref(it)):
            out.append((g.value, c.value))
            if len(out) > 100000: raise RuntimeError("layer loop")
        return out

    def paint_graph(self, f, gid):
        """The paint graph without the root transform, depth first: (format, glyph id / stops / None); and every PaintTransform's matrix."""
        h = self.h(f)
        op = self.Opaque()
        if not self.lib.FT_Get_Color_Glyph_Paint(h, gid, 1, ctypes.byref(op)): return None, None
        out, affines, budget = [], [], [200000]

        def walk(o, depth):
            budget[0] -= 1
            if budget[0] < 0 or depth > 64: raise RuntimeError("paint graph too deep")
            p = self.Paint()
            if not self.lib.FT_Get_Paint(h, o, ctypes.byref(p)): raise RuntimeError("FT_Get_Paint failed")
            u, fm = bytes(p.u), p.format
            opaque = lambda off: self.Opaque.from_buffer_copy(u[off:off + 16])
            fixed = lambda off: int.from_bytes(u[off:off + 8], "little", signed=True)
            if fm == 1:
                it = self.LayerIt.from_buffer_copy(u[:16])
                out.append((1, it.num_layers))
                child = self.Opaque()
                while self.lib.FT_Get_Paint_Layers(h, ctypes.byref(it), ctypes.byref(child)):
                    walk(child, depth + 1)
            elif fm == 10:
                out.append((10, int.from_bytes(u[16:20], "little")))
                walk(opaque(0), depth + 1)
            elif fm == 11:
                g = int.from_bytes(u[0:4], "little")
                out.append((11, g))
                o2 = self.Opaque()
                if not self.lib.FT_Get_Color_Glyph_Paint(h, g, 1, ctypes.byref(o2)): raise RuntimeError("PaintColrGlyph target")
                walk(o2, depth + 1)
            elif fm == 2:
                out.append((2, int.from_bytes(u[0:2], "little")))
            elif fm in (4, 6, 8):
                it = self.StopIt.from_buffer_copy(u[8:32])
                st, stops = self.Stop(), []
                while self.lib.FT_Get_Colorline_Stops(h, ctypes.byref(st), ctypes.byref(it)):
                    stops.append((st.offset, st.palette_index, st.alpha))
                    if len(stops) > 65535: raise RuntimeError("stop loop")
                out.append((fm, tuple(stops)))
            elif fm == 12:
                affines.append(tuple(fixed(16 + 8 * i) for i in range(6)))
                out.append((12, None))
                walk(opaque(0), depth + 1)
            elif fm in (14, 16, 24, 28):
                out.append((fm, None))
                walk(opaque(0), depth + 1)
            elif fm == 32:
                out.append((32, int.from_bytes(u[16:20], "little")))
                walk(opaque(0), depth + 1)
                walk(opaque(24), depth + 1)
            else:
                raise RuntimeError("paint format %d" % fm)

        walk(op, 0)
        return out, affines

    def clip(self, f, gid):
        c = self.Clip()
        if not self.lib.FT_Get_Color_Glyph_ClipBox(self.h(f), gid, ctypes.byref(c)): return None
        return (c.bl.x, c.bl.y, c.tr.x, c.tr.y)

    def bitmap(self, f, gid):
        ff = self.freetype
        f.load_glyph(gid, ff.FT_LOAD_COLOR | ff.FT_LOAD_NO_HINTING | ff.FT_LOAD_RENDER)
        bm = f.glyph.bitmap
        return (f.glyph.bitmap_left, f.glyph.bitmap_top, bm.width, bm.rows, bm.pixel_mode, bytes(bm.buffer))


def canvas_diff(a, b):
    """Two FreeType BGRA bitmaps laid on one canvas by their origins: (worst channel difference, pixels off by more than 24, pixels)."""
    if a[4] != b[4] or a[4] != 7: return None
    x0, y0 = min(a[0], b[0]), max(a[1], b[1])
    x1, y1 = max(a[0] + a[2], b[0] + b[2]), min(a[1] - a[3], b[1] - b[3])

    def px(bm, x, y):
        cx, cy = x - bm[0], bm[1] - y
        if 0 <= cx < bm[2] and 0 <= cy < bm[3]:
            i = (cy * bm[2] + cx) * 4
            return bm[5][i:i + 4]
        return b"\0\0\0\0"

    worst = off = total = 0
    for y in range(y0, y1, -1):
        for x in range(x0, x1):
            p, q = px(a, x, y), px(b, x, y)
            d = max(abs(p[k] - q[k]) for k in range(4))
            worst, off, total = max(worst, d), off + (d > 24), total + 1
    return worst, off, total


def raster(s, gid, ppem, extents=None):
    font = hb.Font(s.face)
    font.scale = (ppem, ppem)
    rp = hb.RasterPaint()
    if extents is None:
        if not rp.set_glyph_extents(font.get_glyph_extents(gid)): return None, None
    else:
        rp.extents = extents
    ext = rp.extents
    rp.paint_glyph(font, gid)
    img = rp.render()
    return ext, None if img is None else bytes(img.buffer)


def pixel_diff(a, b):
    if a is None or b is None or len(a) != len(b): return None
    worst = bad = 0
    for i in range(0, len(a), 4):
        d = max(abs(a[i + k] - b[i + k]) for k in range(4))
        worst, bad = max(worst, d), bad + (d > 24)
    return worst, bad, len(a) // 4


SCRIPTS = [(None, None), ("latn", "en"), ("arab", "ar"), ("deva", "hi"), ("beng", "bn"), ("khmr", "km"), ("mymr", "my"), ("bali", None),
           ("cyrl", "ru"), ("hani", "zh"), ("hebr", "he"), ("thai", "th"), ("hang", "ko")]
RTL = ("arab", "hebr", "syrc")


def compare(text_path, emoji_path, merged_path, seqs, render=40, show=6, script_sample=25):
    """Every check of the module comment on one merged font. Returns (report lines, number of problems)."""
    rules = Rules()
    tdata, edata, mdata = (open(p, "rb").read() for p in (text_path, emoji_path, merged_path))
    tt, te = TTFont(text_path, lazy=True), TTFont(emoji_path, lazy=True)
    if "GDEF" in te and "GDEF" not in TTFont(merged_path, lazy=True):
        edata = without(emoji_path, "GDEF")                           # see cmd_expect: the merge warned that classes were left out
    ehmtx = te["hmtx"].metrics                                        # the merge leaves the emoji GPOS out (W_EMOJI_GPOS): own advances
    T, E, M = Shaper(tdata), Shaper(edata), Shaper(mdata)
    N, m = tt["maxp"].numGlyphs, te["maxp"].numGlyphs
    s = round(T.upem / E.upem * 65536) / 65536
    out, shown, counts = [], {}, {}
    nproblems = [0]

    def note(kind, ok, msg=None):
        c = counts.setdefault(kind, [0, 0])
        c[0 if ok else 1] += 1
        if not ok:
            nproblems[0] += 1
            lst = shown.setdefault(kind, [])
            if msg and len(lst) < show: lst.append(msg)

    t0 = time.time()
    mf = TTFont(merged_path)
    for tag in sorted(mf.keys()):
        if tag != "GlyphOrder": mf[tag]
    glyf = mf["glyf"]
    for gname in mf.getGlyphOrder(): glyf[gname].expand(glyf)
    if "COLR" in mf:
        c = mf["COLR"]
        c.table if c.version == 1 else c.ColorLayers
    if "GSUB" in mf: mf["GSUB"].table.LookupList
    load_ms = (time.time() - t0) * 1000
    note("glyph count", mf["maxp"].numGlyphs >= N + m, "numGlyphs %d" % mf["maxp"].numGlyphs)
    for tag in ("vhea", "vmtx", "hdmx", "LTSH", "VDMX", "DSIG", "CBDT", "CBLC", "sbix", "SVG "):
        note("dropped tables", tag not in mf, "%s still there" % tag)
    note("post v3", mf["post"].formatType == 3.0, "post %s" % mf["post"].formatType)
    tcmap, ecmap = tt.getBestCmap(), te.getBestCmap()
    sh = lambda g: g + N if g else 0

    for cps in seqs:
        if rules.text_expected(cps, tcmap):
            tg, tp = T.shape(cps); mg, mp = M.shape(cps)
            note("sequences as text", tg == mg and tp == mp, "%s: text %s merged %s" % (fmt(cps), tg, mg))
            continue
        eg, ep = E.shape(cps); mg, mp = M.shape(cps)
        ok = mg == [(sh(g), c) for g, c in eg]
        note("sequences as emoji", ok, "%s: emoji %s merged %s" % (fmt(cps), eg, [(g - N if g >= N else "t%d" % g, c) for g, c in mg]))
        if ok:
            # each glyph advances by the emoji font's own advance times s; a mark (advance 0) may be placed by the text font's GPOS
            # or HarfBuzz's fallback, the others are not moved
            hadv = [ehmtx[te.getGlyphName(g0)][0] for g0, _ in eg]
            good = all(g0 == 0 or (abs(madv - adv * s) <= 0.5 + 1e-9 and (adv == 0 or (not xo and not yo)))
                       for adv, (madv, _, xo, yo), (g0, _) in zip(hadv, mp, eg))
            note("emoji advances", good, "%s: emoji %s merged %s" % (fmt(cps), hadv, mp))

    for cp in sorted(ecmap):
        if cp in (0xFE0F, 0xFE0E, 0x200D) or 0xE0000 <= cp <= 0xE007F or 0xFE00 <= cp <= 0xFE0F: continue
        for kind, seq in (("emoji chars alone", [cp]), ("emoji chars + FE0F", [cp, 0xFE0F]), ("emoji chars + FE0E", [cp, 0xFE0E])):
            mg, mp = M.shape(seq)
            if rules.text_expected(seq, tcmap):
                tg, tp = T.shape(seq)
                note(kind, mg == tg and mp == tp, "%s: text %s merged %s" % (fmt(seq), tg, mg))
            else:
                eg, _ = E.shape(seq)
                note(kind, mg == [(sh(g), c) for g, c in eg], "%s: emoji %s merged %s" % (fmt(seq), eg, mg))

    for cp in sorted(tcmap):
        if cp in ecmap and rules.emoji_wins(cp): continue
        tg, tp = T.shape([cp]); mg, mp = M.shape([cp])
        note("text chars", tg == mg and tp == mp, "%04X: text %s merged %s" % (cp, tg, mg))
    feats = [None, {"liga": False, "kern": False}, {"smcp": True}, {"frac": True}, {"tnum": True, "case": True}]
    for r in RUNS:
        cps = [ord(c) for c in r]
        if any(c not in tcmap or (c in ecmap and rules.emoji_wins(c)) for c in cps if c != 0x20): continue
        for ft in feats:
            for script, lang in (("latn", "en"), ("latn", "vi"), ("cyrl", "ru"), ("Zyyy", None)):
                tg, tp = T.shape(cps, script=script, lang=lang, features=ft, keep_ignorables=True)
                mg, mp = M.shape(cps, script=script, lang=lang, features=ft, keep_ignorables=True)
                note("text runs", tg == mg and tp == mp, "%r %s %s" % (r, ft, script))

    alone_differs = {}
    for cps in [c for c in seqs if len(c) > 1][::max(1, len(seqs) // script_sample if len(seqs) > 200 else 1)]:
        if rules.text_expected(cps, tcmap): continue
        eg, _ = E.shape(cps)
        for script, lang in SCRIPTS[1:]:
            mg, _ = M.shape(cps, script=script, lang=lang)
            eg2, _ = E.shape(cps, script=script, lang=lang)
            if script in RTL: mg, eg2 = mg[::-1], eg2[::-1]
            note("sequences in 12 scripts", mg == [(sh(g), c) for g, c in eg], "%s %s: emoji %s merged %s" % (fmt(cps), script, eg, mg))
            if eg2 != eg: alone_differs[script] = alone_differs.get(script, 0) + 1

    ft = FT()
    colr = te["COLR"]
    if colr.version == 0:
        bases = sorted(te.getGlyphID(g) for g in colr.ColorLayers)
    else:
        bases = [te.getGlyphID(r.BaseGlyph) for r in colr.table.BaseGlyphList.BaseGlyphPaintRecord]
    step = max(1, len(bases) // render)
    sample = bases[::step][:render]
    for cp in (0x1F600, 0x2764, 0x1F44D, 0x1F1FA, 0x1F3F4, 0xA9, 0x31):
        g = ecmap.get(cp)
        if g is not None and te.getGlyphID(g) in set(bases) and te.getGlyphID(g) not in sample: sample.append(te.getGlyphID(g))
    fe, fm = ft.face(emoji_path, 64), ft.face(merged_path, 64)
    worst_px = 0
    for g in sample:
        try:
            if colr.version == 0:
                le, lm = ft.layers(fe, g), ft.layers(fm, g + N)
                note("FreeType v0 layers", [(x + N, c) for x, c in le] == lm and len(lm) > 0, "glyph %d: %s vs %s" % (g, le[:3], lm[:3]))
                d = canvas_diff(ft.bitmap(fe, g), ft.bitmap(fm, g + N))
                note("FreeType v0 bitmaps", d is not None and d[1] <= max(2, d[2] // 200), "glyph %d: %s" % (g, d))
            else:
                ge, _ = ft.paint_graph(fe, g)
                gm, am = ft.paint_graph(fm, g + N)
                fx = round(s * 65536)
                same = gm is not None and gm[0] == (12, None) and am[0] == (fx, 0, 0, 0, fx, 0)
                if same:
                    # the merged graph: the wrapper, then the emoji's graph with its glyph ids moved (a PaintColrGlyph behind an inverse)
                    # (FreeType's graph goes on into the glyph a PaintColrGlyph names, which starts with that glyph's own wrapper)
                    rest = [x for i, x in enumerate(gm[1:]) if not (x == (12, None) and ((i + 2 < len(gm) and gm[i + 2][0] == 11)
                                                                                         or gm[i][0] == 11))]
                    want = [(f_, (v + N if f_ in (10, 11) else v)) for f_, v in ge]
                    same = rest == want or [(f_, v) for f_, v in rest] == [(f_, v if f_ != 10 else v) for f_, v in want] or \
                        [x[0] for x in rest] == [x[0] for x in want]
                note("FreeType v1 paint graphs", same, "glyph %d: %s vs %s" % (g, ge[:4], gm[:5] if gm else gm))
                ce, cm = ft.clip(fe, g), ft.clip(fm, g + N)
                note("FreeType v1 clip boxes", (ce is None) == (cm is None) and (not ce or max(abs(x - y) for x, y in zip(ce, cm)) <= 64),
                     "glyph %d: %s vs %s" % (g, ce, cm))
        except Exception as ex:
            note("FreeType", False, "glyph %d: %r" % (g, ex))
        ext, img_e = raster(E, g, 128)
        if img_e is None:                                             # nothing to draw (a v0 base glyph with an empty outline)
            note("HarfBuzz raster", raster(M, g + N, 128)[1] is None, "glyph %d: drawn only in the merged font" % g)
            continue
        _, img_m = raster(M, g + N, 128, ext)
        d = pixel_diff(img_e, img_m)
        if d: worst_px = max(worst_px, d[0])
        note("HarfBuzz raster", d is not None and d[1] <= max(2, d[2] // 400), "glyph %d: %s" % (g, d))

    out.append("== %s  N=%d M=%d s=%s  (fontTools read every table and glyph in %.0f ms)" % (os.path.basename(merged_path), N, m, s, load_ms))
    for k in counts: out.append("   %-26s ok %5d  differ %4d" % (k, counts[k][0], counts[k][1]))
    out.append("   raster worst channel difference %d; the emoji font alone shapes differently in: %s" % (worst_px, alone_differs or "-"))
    for k, lst in shown.items():
        for l in lst: out.append("     [%s] %s" % (k, l))
    return out, nproblems[0]


def cmd_verify(a):
    seqs = parse_seqs(SEQUENCES)
    synth = parse_seqs(SYNTH_SEQUENCES)
    keep = [l for l in read_expected() if not l.startswith("verified ")]
    verified, failed = [], 0
    for t, e in MERGES:
        name = "%s+%s" % (t[:-4], e)
        merged = os.path.join(a.dir, name)
        if not os.path.exists(merged):
            print("missing", merged)
            failed += 1
            continue
        lines, problems = compare(path(t), path(e), merged, synth if e.startswith("Synth") else seqs, render=60, show=8)
        print("\n".join(lines))
        if problems:
            failed += 1
            continue
        verified.append("verified %s %s" % (name, sha(open(merged, "rb").read())))
    write_expected(keep + verified)
    print("verified %d of %d merged fonts" % (len(verified), len(MERGES)))
    sys.exit(1 if failed else 0)


def cmd_check(a):
    seqs = []
    for line in open(a.emoji_test, encoding="utf-8"):
        line = line.split("#", 1)[0].strip()
        if line and ";" in line:
            seqs.append([int(c, 16) for c in line.split(";", 1)[0].split()])
    lines, problems = compare(a.text, a.emoji, a.merged, seqs, render=a.render, show=a.show)
    print("\n".join(lines))
    sys.exit(1 if problems else 0)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("fonts")
    for n in ("lato", "inter", "notosans", "noto", "twemoji"): p.add_argument("--" + n, required=True)
    sub.add_parser("expect")
    p = sub.add_parser("verify"); p.add_argument("dir")
    p = sub.add_parser("check")
    p.add_argument("text"); p.add_argument("emoji"); p.add_argument("merged")
    p.add_argument("--emoji-test", required=True)
    p.add_argument("--render", type=int, default=40)
    p.add_argument("--show", type=int, default=6)
    a = ap.parse_args()
    {"fonts": cmd_fonts, "expect": cmd_expect, "verify": cmd_verify, "check": cmd_check}[a.cmd](a)


if __name__ == "__main__":
    main()
