# A tiny variable font for the Custom Fonts tab scripts (t586-t591): the letters, digits and the space of Fixture-Boxes (mkfont.py), with one axis,
# wght 100..900 (default 400), that makes every box wider as the weight goes up, and nine named instances (Thin .. Black). Text set in it is easy to
# measure, so a script can tell that the page's preview really uses font-variation-settings. Made here, no outside design.
#   python3 mkvarfont.py <folder>      (needs fontTools)
import sys
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib.tables.TupleVariation import TupleVariation

def box(w, h, pad=60):
    pen = TTGlyphPen(None)
    pen.moveTo((pad, 0)); pen.lineTo((pad, h)); pen.lineTo((w - pad, h)); pen.lineTo((w - pad, 0)); pen.closePath()
    return pen.glyph()

def build(path):
    chars = [chr(c) for c in range(ord('A'), ord('Z') + 1)] + [chr(c) for c in range(ord('a'), ord('z') + 1)] + [chr(c) for c in range(ord('0'), ord('9') + 1)] + [' ']
    names = ['.notdef'] + [('space' if c == ' ' else c) for c in chars]
    fb = FontBuilder(1000, isTTF=True)
    fb.setupGlyphOrder(names)
    fb.setupCharacterMap({ord(c): ('space' if c == ' ' else c) for c in chars})
    glyphs, metrics, variations = {}, {}, {}
    for n in names:
        if n == 'space':
            glyphs[n] = TTGlyphPen(None).glyph(); metrics[n] = (350, 0)
            continue
        wide = n == '.notdef' or n.isupper()
        w, h = (800, 700) if wide else (600, 500)
        glyphs[n] = box(w, h); metrics[n] = (w, 60)
        # points 0-3 are the box, 4-7 the phantom points (left, right, top, bottom): the right edge and the advance move together
        heavy = [None, None, (300, 0), (300, 0), (0, 0), (300, 0), (0, 0), (0, 0)]
        light = [None, None, (-200, 0), (-200, 0), (0, 0), (-200, 0), (0, 0), (0, 0)]
        for v in (heavy, light):
            for i in (0, 1): v[i] = (0, 0)
        variations[n] = [TupleVariation({'wght': (0, 1, 1)}, heavy), TupleVariation({'wght': (-1, -1, 0)}, light)]
    fb.setupGlyf(glyphs)
    fb.setupHorizontalMetrics(metrics)
    fb.setupHorizontalHeader(ascent=800, descent=-200)
    fb.setupNameTable({'familyName': 'Fixture Boxes VF', 'styleName': 'Regular'})
    fb.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=900, usWinDescent=250, usWeightClass=400)
    fb.setupPost()
    inst = [('Thin', 100), ('ExtraLight', 200), ('Light', 300), ('Regular', 400), ('Medium', 500), ('SemiBold', 600), ('Bold', 700), ('ExtraBold', 800), ('Black', 900)]
    fb.setupFvar([('wght', 100, 400, 900, 'Weight')], [{'location': {'wght': v}, 'stylename': s} for s, v in inst])
    fb.setupGvar(variations)
    fb.save(path)

build(sys.argv[1] + '/Fixture-Boxes-VF.ttf')
