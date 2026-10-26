#!/usr/bin/env python3
"""Makes the CffToGlyf test fixtures (fonts and expected outlines). Dev time only: CI runs CffToGlyfTest on the committed files.

usage: python3 -I make_fixtures.py SourceSans3-Regular.otf SourceSans3VF-Upright.otf NotoSansCJKjp-Regular.otf [out_dir]

The three source fonts are the upstream files named in README.md. Needs fontTools 4.67 (written against 4.67.0).

Writes into out_dir (default: this script's folder):
  ss3-subset.otf     CFF 1, name-keyed: a subset of Source Sans 3 Regular, renamed (OFL Reserved Font Name 'Source')
  ss3vf-subset.otf   CFF2 variable: a subset of Source Sans 3 VF Upright, renamed
  cjk-subset.otf     CFF 1, CID-keyed: a subset of Noto Sans CJK JP Regular, renamed
  synth-cff.otf      CFF 1, name-keyed, charstrings written by hand: seac (endchar with 4 / 5 numbers), flex, hflex, hflex1, flex1 (both
                     branches), hstemhm + hintmask with an implied vstem and 9 stems, cntrmask, every curve and line operator with odd and even
                     argument counts, widths on every operator that can carry one, nested local + global subroutines (1240 local ones,
                     so bias 1131; global bias 107), endchar and the width inside a subroutine, 16.16 fixed and 28-encoded numbers,
                     dotsection, a moveto-only contour, degenerate and parallel-tangent cubics, a loop and a curve that needs many quadratics,
                     and a glyph built with the arithmetic operators next to its literal twin
  synth-cid.otf      CFF 1, CID-keyed, by hand: 3 Font DICTs (FDSelect format 0) with 10, 1300 and no local subroutines; one global
                     subroutine that calls callsubr, so the same call reaches different subroutines in different Font DICTs
  synth-cff2.otf     CFF2, by hand: 2 axes, 3 regions (one with an intermediate peak on two axes), 2 ItemVariationData sets, 2 Font DICTs
                     (FDSelect format 4) whose Private DICTs set vsindex and blend their BlueValues, blend with 1 to 6 values, blend in local
                     and global subroutines, a vsindex operator, hintmask / cntrmask, flex, an empty charstring
  *.expect.txt       per font and location: fontTools' outline of every glyph (see "Expected values" below)

Expected values: each glyph drawn by fontTools at the location (getGlyphSet(location, normalized=True), the location fontTools'
normalizeLocation gives, avar included, rounded to F2Dot14 as a font engine reads it) through Cu2QuPen(TTGlyphPen(glyphSet), upem / 1000,
reverse_direction=True). One line per glyph:
  s gid advance contours xMin yMin xMax yMax area crc on-curve     a simple glyph; area is the signed area of the quadratic outline
                                                                  (TrueType direction: negative for an outer contour); crc is CRC-32 of
                                                                  "x,y,o;" for every point (o 1 on-curve, 0 off) then "|" and the end
                                                                  points joined by ","; on-curve is each contour's on-curve points "x,y"
                                                                  joined by " ", contours joined by " / ", with repeated points
                                                                  (consecutive, or the last equal to the first) left out
  c gid advance g,dx,dy g,dx,dy ...                               a composite glyph (seac): its components
  e gid advance                                                   an empty glyph
Glyph N of synth-cff.otf uses the arithmetic operators, which fontTools does not run: its line is the literal twin's (N.lit), drawn the same.
fontTools blends a CFF2 glyph with vsindex 0 unless its charstring sets one (a Private DICT's vsindex only counts its regions), so
synth-cff2.otf's lines are drawn from a twin font whose Font DICT 1 glyphs set "1 vsindex" themselves: the outlines the font means.
A section starts with "loc label coords [user]": coords are the F2Dot14 values per fvar axis ("-" for the default / a CFF 1 font), user
the user-space location (tag=value,...) they come from.
"""
import io
import math
import os
import sys
import tempfile
import zlib

from fontTools import subset
from fontTools.cffLib import cffStandardStrings
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.cu2quPen import Cu2QuPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont
from fontTools.ttLib.tables.DefaultTable import DefaultTable

FIXED_DATE = 3850070400                                               # 2026-01-01 00:00 UTC in head's seconds since 1904

# ---------------------------------------------------------------------------------------------------------------------------------
# Charstrings, DICTs, INDEXes (written here by hand, so every encoding the fixtures need can be forced)
# ---------------------------------------------------------------------------------------------------------------------------------

CS_OPS = {'hstem': 1, 'vstem': 3, 'vmoveto': 4, 'rlineto': 5, 'hlineto': 6, 'vlineto': 7, 'rrcurveto': 8, 'callsubr': 10, 'return': 11,
          'endchar': 14, 'vsindex': 15, 'blend': 16, 'hstemhm': 18, 'hintmask': 19, 'cntrmask': 20, 'rmoveto': 21, 'hmoveto': 22,
          'vstemhm': 23, 'rcurveline': 24, 'rlinecurve': 25, 'vvcurveto': 26, 'hhcurveto': 27, 'callgsubr': 29, 'vhcurveto': 30,
          'hvcurveto': 31}
CS_ESC = {'dotsection': 0, 'and': 3, 'or': 4, 'not': 5, 'abs': 9, 'add': 10, 'sub': 11, 'div': 12, 'neg': 14, 'eq': 15, 'drop': 18,
          'put': 20, 'get': 21, 'ifelse': 22, 'random': 23, 'mul': 24, 'sqrt': 26, 'dup': 27, 'exch': 28, 'index': 29, 'roll': 30,
          'hflex': 34, 'flex': 35, 'hflex1': 36, 'flex1': 37}


class Fixed:                 # a number forced into the 5-byte 16.16 form
    def __init__(self, v): self.v = v


class Short:                 # a number forced into the 3-byte form (28)
    def __init__(self, v): self.v = v


def csnum(v):
    if isinstance(v, Fixed) or (isinstance(v, float) and not v.is_integer()):
        v = v.v if isinstance(v, Fixed) else v
        return bytes([255]) + (int(round(v * 65536)) & 0xFFFFFFFF).to_bytes(4, 'big')
    if isinstance(v, Short):
        return bytes([28]) + (v.v & 0xFFFF).to_bytes(2, 'big')
    v = int(v)
    if -107 <= v <= 107:
        return bytes([v + 139])
    if 108 <= v <= 1131:
        v -= 108
        return bytes([247 + (v >> 8), v & 0xFF])
    if -1131 <= v <= -108:
        v = -v - 108
        return bytes([251 + (v >> 8), v & 0xFF])
    assert -32768 <= v <= 32767
    return bytes([28]) + (v & 0xFFFF).to_bytes(2, 'big')


def charstring(prog):
    out = bytearray()
    for tok in prog:
        if isinstance(tok, str):
            out += bytes([CS_OPS[tok]]) if tok in CS_OPS else bytes([12, CS_ESC[tok]])
        elif isinstance(tok, (bytes, bytearray)):
            out += tok                                       # hintmask / cntrmask bytes
        else:
            out += csnum(tok)
    return bytes(out)


class Off:                   # a DICT offset, always 5 bytes so the layout can be computed before the offsets are known
    def __init__(self, v): self.v = v


def dictnum(v):
    if isinstance(v, Off):
        return bytes([29]) + (v.v & 0xFFFFFFFF).to_bytes(4, 'big')
    if isinstance(v, float) and not v.is_integer():
        s = repr(v).lower().replace('e-', 'c').replace('e+', 'b').replace('e', 'b')
        nib = [{'.': 0xA, 'b': 0xB, 'c': 0xC, '-': 0xE}.get(ch, None) if not ch.isdigit() else int(ch) for ch in s] + [0xF]
        if len(nib) % 2:
            nib.append(0xF)
        return bytes([30]) + bytes((nib[i] << 4) | nib[i + 1] for i in range(0, len(nib), 2))
    v = int(v)
    if -107 <= v <= 107:
        return bytes([v + 139])
    if 108 <= v <= 1131:
        v -= 108
        return bytes([247 + (v >> 8), v & 0xFF])
    if -1131 <= v <= -108:
        v = -v - 108
        return bytes([251 + (v >> 8), v & 0xFF])
    if -32768 <= v <= 32767:
        return bytes([28]) + (v & 0xFFFF).to_bytes(2, 'big')
    return bytes([29]) + (v & 0xFFFFFFFF).to_bytes(4, 'big')


def dictop(op):
    return bytes([op]) if op < 1200 else bytes([12, op - 1200])


def dict_bytes(entries):
    """entries: (op, [operands]) pairs; an operand list may hold ('op', n) tokens for blend (23) / vsindex (22) inside a CFF2 DICT."""
    out = bytearray()
    for op, args in entries:
        for a in args:
            out += dictop(a[1]) if isinstance(a, tuple) else dictnum(a)
        out += dictop(op)
    return bytes(out)


def index(items, cff2=False):
    if not items:
        return bytes(4 if cff2 else 2)
    offs = [1]
    for it in items:
        offs.append(offs[-1] + len(it))
    size = 1 if offs[-1] < 0x100 else 2 if offs[-1] < 0x10000 else 3 if offs[-1] < 0x1000000 else 4
    return (len(items).to_bytes(4 if cff2 else 2, 'big') + bytes([size]) + b''.join(o.to_bytes(size, 'big') for o in offs)
            + b''.join(items))


# DICT operators
VERSION, FULLNAME, FAMILYNAME, FONTBBOX, CHARSET, CHARSTRINGS, PRIVATE, SUBRS, DEFAULTWIDTH, NOMINALWIDTH = 0, 2, 3, 5, 15, 17, 18, 19, 20, 21
BLUEVALUES, VSINDEX, VSTORE = 6, 22, 24
ROS, CIDCOUNT, FDARRAY, FDSELECT, FONTNAME, FONTMATRIX = 1230, 1234, 1236, 1237, 1238, 1207


class Strings:
    def __init__(self):
        self.custom = []

    def sid(self, s):
        if s in cffStandardStrings:
            return cffStandardStrings.index(s)
        if s not in self.custom:
            self.custom.append(s)
        return 391 + self.custom.index(s)


def layout(parts):
    """parts: list of (name, bytes or function(pos) -> bytes of a fixed size). Returns {name: offset}, the joined bytes."""
    pos, at = 0, {}
    sizes = []
    for name, p in parts:
        b = p if isinstance(p, (bytes, bytearray)) else p(None)
        at[name] = pos
        sizes.append(len(b))
        pos += len(b)
    out = bytearray()
    for (name, p), size in zip(parts, sizes):
        b = p if isinstance(p, (bytes, bytearray)) else p(at)
        assert len(b) == size, name
        out += b
    return at, bytes(out)


def private_with_subrs(entries, subrs, cff2=False):
    """A Private DICT followed by its Subrs INDEX: the bytes and the DICT's size."""
    if subrs is None:
        d = dict_bytes(entries)
        return d, len(d)
    probe = dict_bytes(entries + [(SUBRS, [Off(0)])])
    d = dict_bytes(entries + [(SUBRS, [Off(len(probe))])])
    return d + index([charstring(s) for s in subrs], cff2), len(d)


def build_cff1(ps_name, names, progs, gsubrs, private, lsubrs, top_extra=()):
    """A name-keyed CFF table: one Private DICT (with lsubrs as its Subrs unless None), charset format 0."""
    st = Strings()
    top_static = [(FULLNAME, [st.sid(ps_name.replace('-', ' '))]), (FAMILYNAME, [st.sid(ps_name.split('-')[0])])] + list(top_extra)
    charset = bytes([0]) + b''.join(st.sid(n).to_bytes(2, 'big') for n in names[1:])
    priv, priv_size = private_with_subrs(private, lsubrs)

    def top(at):
        at = at or {}
        return dict_bytes(top_static + [(CHARSET, [Off(at.get('charset', 0))]), (CHARSTRINGS, [Off(at.get('charstrings', 0))]),
                                        (PRIVATE, [Off(priv_size), Off(at.get('private', 0))])])
    # every custom string is named above, so the String INDEX is complete before the layout is fixed
    header = bytes([1, 0, 4, 4])
    name_index = index([ps_name.encode('ascii')])

    def top_index(at):
        return index([top(at)])
    strings = index([s.encode('ascii') for s in st.custom])
    parts = [('header', header), ('names', name_index), ('top', top_index), ('strings', strings), ('gsubrs', index([charstring(s) for s in gsubrs])),
             ('charset', charset), ('charstrings', index([charstring(p) for p in progs])), ('private', priv)]
    return layout(parts)[1]


def build_cid(ps_name, n_glyphs, progs, gsubrs, fds, fd_of):
    """A CID-keyed CFF table: fds is a list of (name, private entries, subrs or None); fd_of the Font DICT of each glyph (FDSelect 0)."""
    st = Strings()
    ros = [st.sid('Adobe'), st.sid('Identity'), 0]
    fd_names = [st.sid(ps_name + '-' + n) for n, _, _ in fds]
    full = st.sid(ps_name.replace('-', ' '))
    charset = bytes([2]) + (1).to_bytes(2, 'big') + (n_glyphs - 2).to_bytes(2, 'big')          # CIDs 1 .. n-1 for glyphs 1 .. n-1
    fdselect = bytes([0]) + bytes(fd_of)
    privs = [private_with_subrs(entries, subrs) for _, entries, subrs in fds]

    def top(at):
        at = at or {}
        return dict_bytes([(ROS, ros), (FULLNAME, [full]), (CIDCOUNT, [n_glyphs]), (CHARSET, [Off(at.get('charset', 0))]),
                           (FDSELECT, [Off(at.get('fdselect', 0))]), (CHARSTRINGS, [Off(at.get('charstrings', 0))]),
                           (FDARRAY, [Off(at.get('fdarray', 0))])])

    def fdarray(at):
        at = at or {}
        return index([dict_bytes([(FONTNAME, [fd_names[i]]), (PRIVATE, [Off(privs[i][1]), Off(at.get('private%d' % i, 0))])])
                      for i in range(len(fds))])
    parts = [('header', bytes([1, 0, 4, 4])), ('names', index([ps_name.encode('ascii')])), ('top', lambda at: index([top(at)])),
             ('strings', index([s.encode('ascii') for s in st.custom])), ('gsubrs', index([charstring(s) for s in gsubrs])),
             ('charset', charset), ('fdselect', fdselect), ('charstrings', index([charstring(p) for p in progs])), ('fdarray', fdarray)]
    parts += [('private%d' % i, privs[i][0]) for i in range(len(fds))]
    return layout(parts)[1]


def f2dot14(v):
    return int(math.floor(v * 16384 + 0.5)) & 0xFFFF


def item_variation_store(axis_count, regions, data_sets):
    """regions: per region, per axis (start, peak, end); data_sets: per ItemVariationData, its region indexes (no delta rows: CFF2)."""
    region_list = axis_count.to_bytes(2, 'big') + len(regions).to_bytes(2, 'big') + b''.join(
        b''.join(f2dot14(a).to_bytes(2, 'big') + f2dot14(p).to_bytes(2, 'big') + f2dot14(e).to_bytes(2, 'big') for a, p, e in r)
        for r in regions)
    datas = [bytes(4) + len(d).to_bytes(2, 'big') + b''.join(i.to_bytes(2, 'big') for i in d) for d in data_sets]
    head = 2 + 4 + 2 + 4 * len(datas)
    offs, pos = [], head + len(region_list)
    for d in datas:
        offs.append(pos)
        pos += len(d)
    store = ((1).to_bytes(2, 'big') + head.to_bytes(4, 'big') + len(datas).to_bytes(2, 'big') + b''.join(o.to_bytes(4, 'big') for o in offs)
             + region_list + b''.join(datas))
    return len(store).to_bytes(2, 'big') + store


def build_cff2(progs, gsubrs, fds, fd_ranges, axis_count, regions, data_sets):
    """A CFF2 table: fds is a list of (private entries, subrs or None); fd_ranges (first glyph, fd) pairs for an FDSelect format 4."""
    vstore = item_variation_store(axis_count, regions, data_sets)
    fdselect = bytes([4]) + len(fd_ranges).to_bytes(4, 'big') + b''.join(
        g.to_bytes(4, 'big') + fd.to_bytes(2, 'big') for g, fd in fd_ranges) + len(progs).to_bytes(4, 'big')
    privs = [private_with_subrs(entries, subrs, cff2=True) for entries, subrs in fds]

    def top(at):
        at = at or {}
        return dict_bytes([(CHARSTRINGS, [Off(at.get('charstrings', 0))]), (VSTORE, [Off(at.get('vstore', 0))]),
                           (FDARRAY, [Off(at.get('fdarray', 0))]), (FDSELECT, [Off(at.get('fdselect', 0))])])
    top_len = len(top(None))

    def fdarray(at):
        at = at or {}
        return index([dict_bytes([(PRIVATE, [Off(privs[i][1]), Off(at.get('private%d' % i, 0))])]) for i in range(len(fds))], True)
    parts = [('header', bytes([2, 0, 5]) + top_len.to_bytes(2, 'big')), ('top', top), ('gsubrs', index([charstring(s) for s in gsubrs], True)),
             ('vstore', vstore), ('fdselect', fdselect), ('charstrings', index([charstring(p) for p in progs], True)), ('fdarray', fdarray)]
    parts += [('private%d' % i, privs[i][0]) for i in range(len(fds))]
    return layout(parts)[1]


# ---------------------------------------------------------------------------------------------------------------------------------
# Hand-written fonts
# ---------------------------------------------------------------------------------------------------------------------------------

def resolve(prog, nlocal, nglobal):
    """Subroutine calls written as ('L', i) / ('G', i): replaced by the biased number."""
    bias = lambda c: 107 if c < 1240 else 1131 if c < 33900 else 32768
    out = []
    for tok in prog:
        if isinstance(tok, tuple) and tok[0] == 'L':
            out += [tok[1] - bias(nlocal), 'callsubr']
        elif isinstance(tok, tuple) and tok[0] == 'G':
            out += [tok[1] - bias(nglobal), 'callgsubr']
        else:
            out.append(tok)
    return out


def synth_cff_glyphs():
    """(name, unicode or None, advance, program) for synth-cff.otf, and its local / global subroutines."""
    g = []
    g.append(('.notdef', None, 500, [500, 50, 0, 'rmoveto', 400, 0, 0, 700, -400, 0, 'rlineto',              # width on rmoveto
                                     50, -650, 'rmoveto', 0, 600, 300, 0, 0, -600, 'rlineto', 'endchar']))
    g.append(('space', 0x20, 250, [250, 'endchar']))                                                      # width on endchar: empty
    g.append(('A', 0x41, 600, [600, 0, 50, 650, 50, 'hstem',                                              # width on hstem (odd count)
                               0, 0, 'rmoveto', 300, 700, 300, -700, -100, 0, -200, 466, -200, -466, 'rlineto', 'endchar']))
    g.append(('acute', 0xB4, 300, [300, 100, 550, 'rmoveto', 100, 150, 60, 0, -120, -150, 'rlineto', 'endchar']))
    g.append(('grave', 0x60, 300, [300, 550, 'vmoveto', 140, 0, -80, 150, -60, 0, 'rlineto', 'endchar']))  # width on vmoveto
    g.append(('B', 0x42, 560, [560, 50, 'hmoveto', 300, 300, -250, 400, -50, 'hlineto',                  # width on hmoveto; 5 alternating
                               100, -650, 'rmoveto', 200, 150, -200, -150, 'vlineto', 'endchar']))      # 4 alternating, back to start
    g.append(('C', 0x43, 600, [600, 100, 'vmoveto', 300, 'hmoveto',                                       # a moveto-only contour (dropped)
                               138, 112, 112, 138, 138, -112, 112, -138, 'hvcurveto',                     # 8 numbers
                               -138, -112, -112, -138, -138, 112, -112, 138, 10, 'hvcurveto', 'endchar']))  # 9: ends 10 units up
    g.append(('D', 0x44, 560, [0, 50, 'hstem', 100, 0, 'rmoveto',                                         # no width
                               200, 0, 150, 100, 0, 250, 0, 250, -150, 100, -200, 0, 'rrcurveto',         # two curves in one rrcurveto
                               100, -550, 'rmoveto', 10, 150, 20, 50, 50, 'vvcurveto',                    # odd: dx1 first
                               40, 50, 40, 20, 30, 'hhcurveto',                                           # odd: dy1 first
                               -100, 10, -60, -150, 'vvcurveto', -160, 'hlineto', 'endchar']))           # even; closing line
    stems9 = [520, 0, 50, 325, 50, 650, 50, 900, 20, 'hstemhm']                                           # width + 4 hstems
    g.append(('E', 0x45, 520, stems9 + [50, 50, 100, 40, 150, 40, 100, 40, 50, 30,                        # 5 implied vstems: 9 stems
                                        'hintmask', b'\xff\x80', 50, 0, 'rmoveto', 400, 0, 0, 50, -350, 0, 0, 275, 300, 0, 'rlineto',
                                        'hintmask', b'\x0e\x00',                                           # mask bytes that look like
                                        0, 50, -300, 0, 0, 275, 350, 0, 0, 50, -400, 0, 'rlineto',         # endchar / nothing
                                        'cntrmask', b'\x15\x80', 'endchar']))
    g.append(('F', 0x46, 480, [480, 50, 50, 300, 50, 'hintmask', b'\xc0',                                 # width + 2 implied vstems
                               50, 0, 'rmoveto', 50, 0, 0, 325, 300, 0, 0, 50, -300, 0, 0, 275, 350, 0, 0, 50, -400, 0, 'rlineto',
                               'endchar']))
    g.append(('G', 0x47, 520, [520, 0, 20, 'hstem', 50, 0, 'rmoveto',
                               100, -20, 50, 0, 50, 0, 50, 0, 50, 0, 100, 20, 50, 'flex',                 # bottom
                               20, 100, 0, 150, 0, 100, 0, 100, -20, 150, 100, 'flex1',                   # right side: dy branch
                               -100, -50, 30, -50, -50, -50, -100, 'hflex',                               # top
                               50, -400, 'rmoveto', 50, 30, 50, 20, 50, 50, 50, -20, 50, 'hflex1',        # a lens: hflex1 ...
                               -50, -30, -50, -20, -50, 0, -50, 0, -50, 20, -50, 'flex1', 'endchar']))   # ... and flex1, dx branch
    g.append(('H', 0x48, 560, [560, 50, 0, 'rmoveto', 300, 0, 50, 50, 50, 0, 50, 50, 0, 50, 'rlinecurve',
                               0, 100, -50, 50, -100, 0, -250, 0, 'rcurveline', 100, -50, 50, -50, 'vhcurveto',
                               200, 50, 'rmoveto', 50, 50, 50, 50, 10, 'hvcurveto', 50, -50, 50, -60, -10, 'vhcurveto',
                               -40, -40, -60, -20, 'hhcurveto', 'endchar']))
    g.append(('I', 0x49, 260, [Short(260), Fixed(100.5), 0, 'rmoveto', 99.25, 0, 0, 700.75, -99.25, 0, 'rlineto',
                               -50.5, -1700.75, 'rmoveto', 1200, 'hlineto', Short(40), 'vlineto', -1200, 'hlineto', 'endchar']))
    g.append(('J', 0x4A, 600, [('L', 600), ('G', 2), ('L', 1239)]))                    # width, outline and endchar all in subroutines
    g.append(('K', 0x4B, 640, [640, 50, 0, 'rmoveto', 'dotsection', 0, 0, 0, 0, 0, 0, 'rrcurveto',      # a cubic of zero length
                               100, 0, 100, 0, 100, 0, 'rrcurveto',                                       # parallel tangents
                               250, 0, 'rlineto', 300, 200, -300, 0, -50, 300, 'rrcurveto',               # a loop
                               -300, 0, 'rlineto', 'dotsection',
                               60, -350, 'rmoveto', 900, 1300, -1500, -1400, 1000, 120, 'rrcurveto', 'endchar']))  # many quadratics
    g.append(('Aacute', 0xC1, 600, [200, 150, 65, 194, 'endchar']))                                       # seac: A + acute
    g.append(('Agrave', 0xC0, 600, [600, 150, 150, 65, 193, 'endchar']))                                  # seac with a width: A + grave
    arith = [600, 25, 'dup', 'add', 0, 'rmoveto',                                                             # 600 50 0 rmoveto
             500, 100, 'sub', 1, 'mul', 0, 'rlineto',                                                      # 400 0
             175, 0, 'index', 'add', 0, 2, 1, 'roll', 'rlineto',                                           # 0 350
             400, 'neg', 0, 'rlineto',                                                                     # -400 0
             350, 0, 'put', 0, 'get', 'neg', 0, 'exch', 'rlineto',                                         # 0 -350
             200, 2, 'div', 100, 'rmoveto',                                                                # 100 100
             0, 10000, 'sqrt', 'rlineto',                                                                  # 0 100
             30, 70, 'add', 7, 'drop', 0, 'rlineto',                                                       # 100 0
             -100, 77, 1, 2, 'ifelse', 0, 'exch', 'rlineto',                                               # 0 -100
             0, 'not', 1, 'and', 0, 'or', 100, 'mul', 'neg', 7, 7, 'eq', 1, 'sub', -5, 'abs', 5, 'sub', 'add', 'rlineto',  # -100 0
             'endchar']
    lit = [600, 50, 0, 'rmoveto', 400, 0, 'rlineto', 0, 350, 'rlineto', -400, 0, 'rlineto', 0, -350, 'rlineto',
           100, 100, 'rmoveto', 0, 100, 'rlineto', 100, 0, 'rlineto', 0, -100, 'rlineto', -100, 0, 'rlineto', 'endchar']
    g.append(('N', 0x4E, 600, arith))
    g.append(('N.lit', None, 600, lit))
    nlocal, nglobal = 1240, 8
    lsubrs = [['return'] for _ in range(nlocal)]
    gsubrs = [['return'] for _ in range(nglobal)]
    lsubrs[600] = [600, 50, 0, 'rmoveto', 'return']
    gsubrs[2] = [400, 0, 'rlineto', ('L', 0), 'return']
    lsubrs[0] = [0, 300, 'rlineto', ('G', 3), 'return']
    gsubrs[3] = [-100, 0, 'rlineto', ('L', 700), 'return']
    lsubrs[700] = [0, 400, 'rlineto', ('G', 4), 'return']
    gsubrs[4] = [-300, 0, 0, -400, 'rlineto', 'return']
    lsubrs[1239] = [100, -200, 'rmoveto', 0, 100, 100, 0, 0, -100, 'rlineto', 'endchar']
    fix = lambda p: resolve(p, nlocal, nglobal)
    return [(n, u, a, fix(p)) for n, u, a, p in g], [fix(s) for s in lsubrs], [fix(s) for s in gsubrs]


def synth_cid_parts():
    nglobal = 3
    # FD 0: 10 local subroutines (bias 107); FD 1: 1300 (bias 1131); FD 2: none
    l0 = [['return'] for _ in range(10)]
    l1 = [['return'] for _ in range(1300)]
    l0[0] = [0, 400, 300, -400, 'rlineto', 'return']                                     # a triangle's sides
    l1[1024] = [0, 400, 400, 0, 0, -400, 'rlineto', 'return']                            # a square's sides: the same callsubr in G0
    l1[1299] = [-300, 100, 'rmoveto', 200, 0, -100, 100, 'rlineto', 'endchar']           # a hole in the square; endchar here
    l0[9] = [0, 300, 'hstemhm', 100, 50, 'hintmask', b'\xc0', 'return']
    g0 = [-107, 'callsubr', 'return']                                                    # FD 0: subroutine 0; FD 1: subroutine 1024
    g1 = [50, 0, 'rmoveto', 'return']
    g2 = [100, 50, 50, 100, 'vhcurveto', 'return']
    progs = [
        [500, 'endchar'],                                                                 # CID 0 (FD 0)
        [600, ('G', 1), ('G', 0), 'endchar'],                                             # FD 0: triangle through G0 -> L0
        [600, ('G', 1), ('G', 0), ('L1', 1299)],                                          # FD 1: square through G0 -> L1024; endchar in L1299
        [600, 100, 0, 'rmoveto', ('G', 2), 100, -50, -50, -100, 'hvcurveto', 'endchar'],  # FD 2: no local subroutines
        [600, ('L0', 9), 50, 0, 'rmoveto', 400, 0, 0, 300, -400, 0, 'rlineto', 'endchar'],   # FD 0: stems and hintmask in a subroutine
        [600, 50, 50, 'rmoveto', 200, 0, 100, 100, 0, 200, 'rrcurveto', -300, 'hlineto', 'endchar'],   # FD 1
        [300, 'endchar'],                                                                 # FD 2: empty
    ]
    fd_of = [0, 0, 1, 2, 0, 1, 2]
    bias = lambda c: 107 if c < 1240 else 1131

    def fix(p):
        out = []
        for tok in p:
            if isinstance(tok, tuple):
                kind, i = tok
                if kind == 'G':
                    out += [i - bias(nglobal), 'callgsubr']
                else:
                    out += [i - bias(len(l0) if kind == 'L0' else len(l1)), 'callsubr']
            else:
                out.append(tok)
        return out
    return [fix(p) for p in progs], [g0, g1, g2], [('A', [], l0), ('B', [], l1), ('C', [], None)], fd_of


def synth_cff2_parts():
    # axes: wght (100 400 900), wdth (75 100 125); regions: 0 heavier, 1 lighter, 2 narrower while heavier (peak at half weight)
    regions = [[(0, 1, 1), (0, 0, 0)], [(-1, -1, 0), (0, 0, 0)], [(0, 0.5, 1), (-1, -1, 0)]]
    data_sets = [[0, 1], [2, 0, 1]]                   # vsindex 0: 2 regions; vsindex 1: 3 regions, listed out of order
    B = 'blend'
    # CFF2 has no return operator: a subroutine simply ends. FD 1 has 1240 local subroutines (bias 1131), FD 0 five (bias 107).
    l0 = [[] for _ in range(5)]
    l1 = [[] for _ in range(1240)]
    gs = [[] for _ in range(4)]
    l0[0] = [0, 300, 40, -40, 1, B, 'rlineto']                                            # vsindex 0 (2 deltas per value)
    l0[1] = [0, 300, 30, 40, -30, 1, B, 'rlineto']                                        # for a charstring that set vsindex 1
    l1[0] = [0, 300, 30, 40, -30, 1, B, 'rlineto']                                        # vsindex 1 (3 deltas per value)
    l1[1239] = [0, -400, 'rlineto']
    gs[0] = [300, 0, 50, -50, 1, B, 'rlineto']                                            # global, blended with the caller's vsindex
    gs[1] = [0, 100, -400, 0, 'rlineto']
    gs[2] = [300, 0, 10, 20, 30, 1, B, 'rlineto']
    progs = [
        # 0 .notdef (FD 0)
        [50, 0, 'rmoveto', 400, 20, -20, 1, B, 0, 'rlineto', 0, 700, 'rlineto', -400, -20, 20, 1, B, 0, 'rlineto'],
        # 1 A (FD 0): 6 values blended at once, hstemhm + hintmask, curves
        [0, 50, 600, 50, 0, 10, 0, -10, 0, 20, 0, -20, 4, B, 'hstemhm', 'hintmask', b'\xc0',
         50, 0, 'rmoveto', 200, 700, 200, -700, -80, 0, 40, 60, -30, 20, 40, -40, 30, -20, -40, 40, -20, 0, 6, B, 'rlineto',
         -120, 280, -120, -280, 'rlineto', 'hintmask', b'\x40', 'cntrmask', b'\x80',
         100, 520, 'rmoveto', 60, 0, 50, 40, 0, 60, 'rrcurveto', 50, -40, 60, -60, 'vhcurveto', -60, -40, -50, -60, 'hvcurveto',
         -10, 10, 20, -20, 5, 5, 2, B, 'rmoveto'],                            # a trailing moveto: no contour
        # 2 B (FD 1: vsindex 1 from its Private DICT)
        [50, 0, 'rmoveto', 300, 30, 60, -30, 1, B, 0, 'rlineto', ('L1', 0), -300, -30, -60, 30, 1, B, 0, 'rlineto'],
        # 3 C (FD 0, then vsindex 1 in the charstring)
        [1, 'vsindex', 100, 0, 'rmoveto', ('G', 2), ('L0', 1), -300, -30, -60, 30, 1, B, 0, 'rlineto'],
        # 4 D (FD 1): flex and curves with blended numbers
        [50, 'hmoveto', 100, -20, 50, 0, 50, 0, 50, 0, 50, 0, 100, 20] + [10, 0, 5, -5, 0, 0, 5, -5, 0] + [0] * 24 + [10, 0, 5]
        + [12, B, 50, 'flex', 0, 300, 'rlineto', -200, 0, -100, 100, 0, 100, 'rrcurveto', ('L1', 1239)],
        # 5 E (FD 0): the last line returns to the start only at the default (fontTools then writes a zero-length closing line)
        [100, 100, 'rmoveto', 300, 'hlineto', 200, 'vlineto', -300, 'hlineto', 0, -200, 0.4, -0.3, 1, B, 'rlineto'],
        # 6 space (FD 0): an empty charstring
        [],
        # 7 F (FD 0): global subroutines only, a vstemhm and a hintmask in CFF2
        [100, 50, 'vstemhm', 'hintmask', b'\x80', 100, 0, 'rmoveto', ('G', 0), ('L0', 0), ('G', 1), 0, -400, 'rlineto'],
    ]
    fd_ranges = [(0, 0), (2, 1), (3, 0), (4, 1), (5, 0)]
    nglobal = len(gs)

    bias = lambda c: 107 if c < 1240 else 1131

    def fix(p):
        out = []
        for tok in p:
            if isinstance(tok, tuple):
                kind, i = tok
                out += [i - bias(len({'G': gs, 'L0': l0, 'L1': l1}[kind])), 'callgsubr' if kind == 'G' else 'callsubr']
            else:
                out.append(tok)
        return out
    # Private DICTs: FD 0 keeps vsindex 0 and blends its BlueValues with 2 regions; FD 1 sets vsindex 1 and blends with 3
    fd0 = [(BLUEVALUES, [-12, 0, 500, 512, -2, 1, 2, -1, 0, 4, 0, 6, 4, ('op', 23)])]
    fd1 = [(VSINDEX, [1]), (BLUEVALUES, [-12, 0, 1, 2, 3, -1, -2, -3, 2, ('op', 23)])]
    return ([fix(p) for p in progs], [fix(s) for s in gs], [(fd0, [fix(s) for s in l0]), (fd1, [fix(s) for s in l1])], fd_ranges,
            regions, data_sets, nglobal)


def scaffold(path, upem, order, cmap, advances, family, ps, tag, table, axes=None):
    """A complete font around a hand-written CFF / CFF2 table; bearings and bounds are filled in from fontTools' reading of it."""
    fb = FontBuilder(upem, isTTF=False)
    fb.font.sfntVersion = 'OTTO'                                      # (FontBuilder sets it only in setupCFF)
    fb.setupGlyphOrder(order)
    fb.setupCharacterMap(cmap)
    fb.setupHorizontalMetrics({n: (a, 0) for n, a in zip(order, advances)})
    fb.setupHorizontalHeader(ascent=800, descent=-200)
    fb.setupNameTable({'familyName': family, 'styleName': 'Regular', 'uniqueFontIdentifier': ps, 'fullName': family,
                       'psName': ps, 'version': 'Version 1.000', 'copyright': 'Test font made for the CffToGlyf tests; public domain.'})
    fb.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    fb.setupPost(keepGlyphNames=False)
    if axes:
        fb.setupFvar(axes, [])
    fb.setupMaxp()
    t = DefaultTable(tag)
    t.data = table
    fb.font[tag] = t
    fb.font.recalcBBoxes = False
    buf = io.BytesIO()
    fb.font.save(buf)
    # bearings = xMin, head / hhea bounds from fontTools' own reading of the table
    f = TTFont(io.BytesIO(buf.getvalue()), recalcBBoxes=False)
    from fontTools.pens.boundsPen import ControlBoundsPen
    gs = f.getGlyphSet()
    bb = None
    for n in order:
        p = ControlBoundsPen(gs)
        if n != 'N':                                                  # fontTools cannot run N (arithmetic operators)
            gs[n].draw(p)
        b = p.bounds
        if b:
            f['hmtx'][n] = (f['hmtx'][n][0], math.floor(b[0]))
            bb = b if bb is None else (min(bb[0], b[0]), min(bb[1], b[1]), max(bb[2], b[2]), max(bb[3], b[3]))
    if 'N' in order:
        f['hmtx']['N'] = f['hmtx']['N.lit']
    head = f['head']
    head.xMin, head.yMin, head.xMax, head.yMax = (math.floor(bb[0]), math.floor(bb[1]), math.ceil(bb[2]), math.ceil(bb[3]))
    t = DefaultTable(tag)                                             # the hand-written bytes, not fontTools' recompiled table
    t.data = table
    f[tag] = t
    head.created = head.modified = FIXED_DATE                         # the same bytes on every run
    f.recalcTimestamp = False
    f.save(path)
    assert TTFont(path).getTableData(tag) == table


# ---------------------------------------------------------------------------------------------------------------------------------
# Subsets of the real fonts
# ---------------------------------------------------------------------------------------------------------------------------------

def rename(font, family, ps, cff_names=True):
    for rec in font['name'].names:
        if rec.nameID in (0, 7, 13, 14):
            continue
        s = rec.toUnicode()
        for a, b in (('Source Sans 3', family), ('SourceSans3VF', ps.split('-')[0]), ('SourceSans3', ps.split('-')[0]),
                     ('Noto Sans CJK JP', family), ('NotoSansCJKjp', ps.split('-')[0])):
            s = s.replace(a, b)
        rec.string = s
    for rec in font['name'].names:
        if rec.nameID not in (0, 7, 13, 14):
            s = rec.toUnicode()
            assert 'Source' not in s and 'Noto' not in s, s
    if 'CFF ' in font and cff_names:
        cff = font['CFF '].cff
        old = cff.fontNames[0]
        cff.fontNames = [ps]
        top = cff.topDictIndex[0]
        for attr in ('FullName', 'FamilyName'):
            if hasattr(top, attr):
                setattr(top, attr, family)
        if hasattr(top, 'FDArray'):
            for fd in top.FDArray:
                if hasattr(fd, 'FontName'):
                    fd.FontName = fd.FontName.replace(old, ps)


def make_subset(src, dst, unicodes, glyph_names, family, ps):
    font = TTFont(src)
    o = subset.Options()
    o.layout_features = []
    o.drop_tables += ['GSUB', 'GPOS', 'GDEF', 'BASE', 'DSIG', 'JSTF', 'meta']
    o.name_IDs = ['*']
    o.name_languages = ['*']
    o.name_legacy = True
    o.notdef_outline = True
    o.hinting = True
    o.desubroutinize = False
    o.glyph_names = True
    s = subset.Subsetter(o)
    s.populate(unicodes=unicodes, glyphs=[g for g in glyph_names if g in font.getGlyphOrder()])
    s.subset(font)
    rename(font, family, ps)
    font.recalcTimestamp = False                                      # keeps the source's head dates: the same bytes on every run
    font.save(dst)


# ---------------------------------------------------------------------------------------------------------------------------------
# Expected values
# ---------------------------------------------------------------------------------------------------------------------------------

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
            if not p[2] and not q[2]:
                full.append(((p[0] + q[0]) / 2, (p[1] + q[1]) / 2, 1))
        k = next((i for i, p in enumerate(full) if p[2]), None)
        if k is None:
            continue
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


def on_curve(coords, ends, flags):
    out, s = [], 0
    for e in ends:
        pts = [(coords[i][0], coords[i][1], flags[i] & 1) for i in range(s, e + 1)]
        s = e + 1
        d = [p for i, p in enumerate(pts) if i == 0 or p != pts[i - 1]]
        while len(d) > 1 and d[-1] == d[0]:
            d.pop()
        out.append(' '.join('%d,%d' % (p[0], p[1]) for p in d if p[2]))
    return ' / '.join(out)


def expectations(path, locations, twins=None, draw_from=None):
    """Lines for each location: None (default) or a user-space dict. draw_from: a font with the same glyphs to draw them from instead."""
    f = TTFont(path)
    d = TTFont(draw_from) if draw_from else f
    upem = f['head'].unitsPerEm
    order = f.getGlyphOrder()
    axes = [a.axisTag for a in f['fvar'].axes] if 'fvar' in f else []
    lines = ['# %s: expected outlines, made by make_fixtures.py with fontTools %s' % (os.path.basename(path), __import__('fontTools').version)]
    for loc in locations:
        if loc:
            nl = {k: math.floor(v * 16384 + 0.5) / 16384 for k, v in f.normalizeLocation(loc).items()}
            gs = d.getGlyphSet(location=nl, normalized=True)
            coords = ','.join(str(int(round(nl.get(a, 0) * 16384))) for a in axes)
            user = ','.join('%s=%g' % (k, v) for k, v in loc.items())
            lines.append('loc %s %s %s' % (user, coords, user))
        else:
            gs = d.getGlyphSet()
            lines.append('loc default -')
        for gid, name in enumerate(order):
            pen = TTGlyphPen(gs)
            gs[(twins or {}).get(name, name)].draw(Cu2QuPen(pen, upem / 1000, reverse_direction=True))
            g = pen.glyph()
            adv = f['hmtx'][name][0]
            if g.isComposite():
                lines.append('c %d %d %s' % (gid, adv, ' '.join('%d,%d,%d' % (order.index(c.glyphName), c.x, c.y) for c in g.components)))
            elif g.numberOfContours == 0:
                lines.append('e %d %d' % (gid, adv))
            else:
                co = [(int(x), int(y)) for x, y in g.coordinates]
                ends = list(g.endPtsOfContours)
                g.recalcBounds(None)
                canon = ''.join('%d,%d,%d;' % (x, y, fl & 1) for (x, y), fl in zip(co, g.flags)) + '|' + ','.join(map(str, ends))
                lines.append('s %d %d %d %d %d %d %d %.1f %08x %s' % (gid, adv, len(ends), g.xMin, g.yMin, g.xMax, g.yMax,
                                                                    area(co, ends, g.flags), zlib.crc32(canon.encode('ascii')),
                                                                    on_curve(co, ends, g.flags)))
    return lines


def write_expect(path, locations, twins=None, draw_from=None):
    out = os.path.splitext(path)[0] + '.expect.txt'
    with open(out, 'w', newline='\n') as fh:
        fh.write('\n'.join(expectations(path, locations, twins, draw_from)) + '\n')
    return out


# ---------------------------------------------------------------------------------------------------------------------------------

def coverage(path):
    """Prints which charstring operators the font's glyphs run, through their subroutines (a check on the fixtures' claims)."""
    import collections
    f = TTFont(path)
    cff = f['CFF2' if 'CFF2' in f else 'CFF '].cff
    top = cff.topDictIndex[0]
    bias = lambda n: 107 if n < 1240 else 1131 if n < 33900 else 32768
    seen = collections.Counter()

    def walk(c, local, depth):
        c.decompile()
        for i, tok in enumerate(c.program):
            if isinstance(tok, str):
                seen[tok] += 1
                if tok in ('callsubr', 'callgsubr'):
                    subrs = local if tok == 'callsubr' else cff.GlobalSubrs
                    seen['depth%d' % (depth + 1)] += 1
                    seen['%s bias %d' % (tok, bias(len(subrs)))] += 1
                    walk(subrs[c.program[i - 1] + bias(len(subrs))], local, depth + 1)
    for name in f.getGlyphOrder():
        c, _ = top.CharStrings.getItemAndSelector(name)
        try:
            walk(c, getattr(c.private, 'Subrs', []), 0)
        except Exception as e:                                         # the arithmetic glyph
            seen['not run by fontTools: ' + name] += 1
    print(os.path.basename(path), ', '.join('%s %d' % kv for kv in sorted(seen.items())))


def main():
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    ss3, ss3vf, cjk = sys.argv[1:4]
    out = sys.argv[4] if len(sys.argv) > 4 else os.path.dirname(os.path.abspath(__file__))
    p = lambda n: os.path.join(out, n)

    latin = [c for c in range(0x20, 0x7F)] + [c for c in range(0xC0, 0x100)] + [0x2026, 0x2261, 0x14B, 0x1E4, 0x197, 0x110, 0xA789, 0x1EAE]
    make_subset(ss3, p('ss3-subset.otf'), latin, ['uni1EAE.s', 'colon'], 'CffToGlyf Fixture Sans', 'CffToGlyfFixtureSans-Regular')
    make_subset(ss3vf, p('ss3vf-subset.otf'), latin, ['uni1ED0', 'abreve.a', 'Alphaiotasub.s'], 'CffToGlyf Fixture Sans VF',
                'CffToGlyfFixtureSansVF-Regular')
    cjk_text = 'Aa1あいうアイウ日本語漢字永東京ｱｲ①→＋Ａ１한、。「」ー鬱龍齉'
    make_subset(cjk, p('cjk-subset.otf'), [ord(c) for c in cjk_text], ['cid06557', 'cid00095', 'cid00081'], 'CffToGlyf Fixture CJK',
                'CffToGlyfFixtureCJK-Regular')

    glyphs, lsubrs, gsubrs = synth_cff_glyphs()
    order = [n for n, _, _, _ in glyphs]
    table = build_cff1('CffToGlyfSynth-Regular', order, [g[3] for g in glyphs], gsubrs, [(DEFAULTWIDTH, [500]), (NOMINALWIDTH, [0])],
                       lsubrs, top_extra=[(FONTBBOX, [-50, -1000, 1500, 900])])
    scaffold(p('synth-cff.otf'), 1000, order, {u: n for n, u, _, _ in glyphs if u}, [g[2] for g in glyphs], 'CffToGlyf Synth',
             'CffToGlyfSynth-Regular', 'CFF ', table)

    progs, gs, fds, fd_of = synth_cid_parts()
    table = build_cid('CffToGlyfSynthCID-Regular', len(progs), progs, gs, fds, fd_of)
    corder = ['.notdef'] + ['cid%05d' % i for i in range(1, len(progs))]
    scaffold(p('synth-cid.otf'), 1000, corder, {0x41 + i: corder[i] for i in range(1, len(progs))}, [500, 600, 600, 600, 600, 600, 300],
             'CffToGlyf Synth CID', 'CffToGlyfSynthCID-Regular', 'CFF ', table)

    progs, gs, fds, fd_ranges, regions, data_sets, _ = synth_cff2_parts()
    table = build_cff2(progs, gs, fds, fd_ranges, 2, regions, data_sets)
    vorder = ['.notdef', 'A', 'B', 'C', 'D', 'E', 'space', 'F']
    vargs = (1000, vorder, {0x41: 'A', 0x42: 'B', 0x43: 'C', 0x44: 'D', 0x45: 'E', 0x20: 'space', 0x46: 'F'},
             [500, 600, 600, 600, 600, 600, 250, 600], 'CffToGlyf Synth VF', 'CffToGlyfSynthVF-Regular', 'CFF2')
    vaxes = [('wght', 100, 400, 900, 'Weight'), ('wdth', 75, 100, 125, 'Width')]
    scaffold(p('synth-cff2.otf'), *vargs, table, axes=vaxes)
    # fontTools blends with vsindex 0 unless the charstring itself sets one (it reads a Private DICT's vsindex only to count the regions),
    # so synth-cff2's expected outlines are drawn from a twin whose Font DICT 1 glyphs start with "1 vsindex" instead (same outlines).
    fd_of = lambda g: [fd for first, fd in fd_ranges if first <= g][-1]
    twin_progs = [([1, 'vsindex'] + pr if fd_of(g) == 1 and pr else pr) for g, pr in enumerate(progs)]
    twin_fds = [fds[0], ([e for e in fds[1][0] if e[0] not in (VSINDEX, BLUEVALUES)], fds[1][1])]
    twin_dir = tempfile.mkdtemp()
    twin = os.path.join(twin_dir, 'synth-cff2-twin.otf')
    scaffold(twin, *vargs, build_cff2(twin_progs, gs, twin_fds, fd_ranges, 2, regions, data_sets), axes=vaxes)

    made = [write_expect(p('ss3-subset.otf'), [None]),
            write_expect(p('ss3vf-subset.otf'), [None, {'wght': 700}]),
            write_expect(p('cjk-subset.otf'), [None]),
            write_expect(p('synth-cff.otf'), [None], twins={'N': 'N.lit'}),
            write_expect(p('synth-cid.otf'), [None]),
            write_expect(p('synth-cff2.otf'), [None, {'wght': 700}, {'wght': 900, 'wdth': 75}, {'wght': 100, 'wdth': 125},
                                               {'wght': 650, 'wdth': 87.5}], draw_from=twin)]
    os.remove(twin)
    os.rmdir(twin_dir)
    for n in ('ss3-subset.otf', 'ss3vf-subset.otf', 'cjk-subset.otf', 'synth-cff.otf', 'synth-cid.otf', 'synth-cff2.otf'):
        coverage(p(n))
    for n in sorted(os.listdir(out)):
        print('%8d  %s' % (os.path.getsize(p(n)), n))


if __name__ == '__main__':
    main()
