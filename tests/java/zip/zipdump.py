#!/usr/bin/env python3
"""Dumps every entry of a zip as JSON using only struct: raw name (hex), flags, method, local/CD extra (hex), sizes, lho. Also reports zipfile's own verdict."""
import sys, json, struct, zipfile
data = open(sys.argv[1], 'rb').read()
# find EOCD
i = data.rfind(b'PK\x05\x06')
cnt, cdsize, cdoff = struct.unpack_from('<HII', data, i + 10)[0], struct.unpack_from('<I', data, i + 12)[0], struct.unpack_from('<I', data, i + 16)[0]
prefix = i - cdsize - cdoff
if prefix < 0: prefix = 0
if data[cdoff:cdoff+4] != b'PK\x01\x02' and data[cdoff+prefix:cdoff+prefix+4] == b'PK\x01\x02': cdoff += prefix
else: prefix = 0
out = []
p = cdoff
for _ in range(cnt):
    sig, = struct.unpack_from('<I', data, p)
    assert sig == 0x02014b50, 'bad cd sig at %d' % p
    (madeby, need, flags, method, t, d, crc, csz, sz, nl, el, cl, disk, iattr, eattr, lho) = struct.unpack_from('<HHHHHHIIIHHHHHII', data, p + 4)
    name = data[p+46:p+46+nl]
    cdx = data[p+46+nl:p+46+nl+el]
    l = lho + prefix
    lnl, lel = struct.unpack_from('<HH', data, l + 26)
    lx = data[l+30+lnl:l+30+lnl+lel]
    out.append({'name': name.hex(), 'flags': flags, 'method': method, 'cdx': cdx.hex(), 'lx': lx.hex(), 'csize': csz, 'size': sz, 'lho': lho, 'dataoff': l + 30 + lnl + lel, 'crc': crc})
    p += 46 + nl + el + cl
res = {'count': cnt, 'prefix': prefix, 'entries': out}
try:
    zf = zipfile.ZipFile(sys.argv[1])
    res['pyok'] = True
    res['pynames'] = [i.filename for i in zf.infolist()]
except Exception as e:
    res['pyok'] = False
    res['pyerr'] = str(e)
print(json.dumps(res))
