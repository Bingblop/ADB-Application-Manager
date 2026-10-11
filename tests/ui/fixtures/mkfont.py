# A tiny font for the tests: every letter, digit and the space are plain boxes (a wide box for the capitals, a narrow one for the others),
# so text set in it is easy to tell from text in any other font. Made here, no outside design.
import sys
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

def box(w, h, pad=60):
    pen = TTGlyphPen(None)
    pen.moveTo((pad, 0)); pen.lineTo((pad, h)); pen.lineTo((w - pad, h)); pen.lineTo((w - pad, 0)); pen.closePath()
    return pen.glyph()

def build(path, family, style, wide, narrow):
    chars = [chr(c) for c in range(ord('A'), ord('Z') + 1)] + [chr(c) for c in range(ord('a'), ord('z') + 1)] + [chr(c) for c in range(ord('0'), ord('9') + 1)] + [' ']
    names = ['.notdef'] + [('space' if c == ' ' else c) for c in chars]
    fb = FontBuilder(1000, isTTF=True)
    fb.setupGlyphOrder(names)
    fb.setupCharacterMap({ord(c): ('space' if c == ' ' else c) for c in chars})
    glyphs, metrics = {}, {}
    for n in names:
        if n == 'space': glyphs[n] = TTGlyphPen(None).glyph(); metrics[n] = (350, 0)
        elif n == '.notdef' or n.isupper(): glyphs[n] = box(wide, 700); metrics[n] = (wide, 60)
        else: glyphs[n] = box(narrow, 500); metrics[n] = (narrow, 60)
    fb.setupGlyf(glyphs)
    fb.setupHorizontalMetrics(metrics)
    fb.setupHorizontalHeader(ascent=800, descent=-200)
    fb.setupNameTable({'familyName': family, 'styleName': style})
    fb.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=900, usWinDescent=250, usWeightClass=700 if 'Bold' in style else 400)
    fb.setupPost()
    fb.save(path)

build(sys.argv[1] + '/Fixture-Boxes-Regular.ttf', 'Fixture Boxes', 'Regular', 800, 600)
build(sys.argv[1] + '/Fixture-Boxes-Bold.ttf', 'Fixture Boxes', 'Bold', 900, 700)
