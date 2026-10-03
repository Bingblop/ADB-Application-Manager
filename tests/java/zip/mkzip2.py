import zipfile, io, os, struct, zlib, random, subprocess, sys
random.seed(11)
os.makedirs(sys.argv[1], exist_ok=True)
os.chdir(sys.argv[1])           # where the fixtures are written

def crc(b): return zlib.crc32(b) & 0xFFFFFFFF

def deflate_raw(b):
    c = zlib.compressobj(6, zlib.DEFLATED, -15)
    return c.compress(b) + c.flush()

class ZW:
    """Tiny hand-rolled zip writer so every header field is under my control."""
    def __init__(self):
        self.buf = bytearray()
        self.cd = []
    def add(self, name_bytes, data=b'', method=8, flags=0, extra_local=b'', extra_cd=b'', comment=b'',
            descriptor=None, external=0, madeby=0x031E, raw_comp=None, csize_override=None, size_override=None,
            crc_override=None, lho_override=None):
        comp = raw_comp if raw_comp is not None else (deflate_raw(data) if method == 8 else data)
        c = crc(data)
        lho = len(self.buf)
        f = flags | (8 if descriptor else 0)
        csz = len(comp) if csize_override is None else csize_override
        sz = len(data) if size_override is None else size_override
        c_cd = c if crc_override is None else crc_override
        lh_c, lh_cs, lh_s = (0, 0, 0) if descriptor else (c, len(comp), len(data))
        self.buf += struct.pack('<IHHHHHIIIHH', 0x04034b50, 20, f, method, 0x6000, 0x5A21, lh_c, lh_cs, lh_s,
                                len(name_bytes), len(extra_local)) + name_bytes + extra_local + comp
        if descriptor == 'sig':
            self.buf += struct.pack('<IIII', 0x08074b50, c, len(comp), len(data))
        elif descriptor == 'nosig':
            self.buf += struct.pack('<III', c, len(comp), len(data))
        self.cd.append((name_bytes, f, method, c_cd, csz, sz, extra_cd, comment, external, madeby,
                        lho if lho_override is None else lho_override))
    def finish(self, zcomment=b'', trailing=b'', eocd_count=None):
        cdstart = len(self.buf)
        for (n, f, m, c, cs, s, ex, cm, ext, mb, lho) in self.cd:
            self.buf += struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, mb, 20, f, m, 0x6000, 0x5A21, c, cs, s,
                                    len(n), len(ex), len(cm), 0, 0, ext, lho) + n + ex + cm
        cdsize = len(self.buf) - cdstart
        cnt = len(self.cd) if eocd_count is None else eocd_count
        self.buf += struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, cnt, cnt, cdsize, cdstart, len(zcomment)) + zcomment + trailing
        return bytes(self.buf)

def write(name, data):
    open(name, 'wb').write(data)

# --- 1. prepended data (CRX3-like header + ordinary zip; offsets inside the zip are relative to the zip start)
z = ZW(); z.add(b'manifest.json', b'{"name":"x"}'); z.add(b'a/b.txt', b'hello' * 100)
plain = z.finish()
write('plain.zip', plain)
hdr = b'Cr24' + struct.pack('<II', 3, 40) + bytes(40)
write('prepended.crx', hdr + plain)
write('prepended_sfx.zip', b'#!/bin/sh\nexit 0\n' + plain)

# --- 2. fake EOCD inside the real archive comment (fake has comment-length 0 and cd size/offset 0)
fake = struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, 0, 0, 0, 0, 0)
z = ZW(); z.add(b'real1.txt', b'one'); z.add(b'real2.txt', b'two')
write('fake_eocd.zip', z.finish(zcomment=b'archive made by tool X; embedded sample: ' + fake))
# comment that merely has the 4-byte signature in the middle of text (fake cl field = garbage)
z = ZW(); z.add(b'real1.txt', b'one'); z.add(b'real2.txt', b'two')
write('fake_eocd_text.zip', z.finish(zcomment=b'see PK\x05\x06 in the spec, ok? ' + b'x' * 40))

# --- 3. duplicate names
z = ZW(); z.add(b'dup.txt', b'first'); z.add(b'dup.txt', b'second'); z.add(b'other.txt', b'o')
write('dups.zip', z.finish())

# --- 4. odd names
z = ZW()
for n in [b'/abs/file.txt', b'a//b.txt', b'win\\dir\\f.txt', b'./dot.txt', b'normal/ok.txt', b'trail.dir/']:
    z.add(n, b'x' if not n.endswith(b'/') else b'', method=0 if n.endswith(b'/') else 8)
write('odd_names.zip', z.finish())

# --- 5. non-UTF8 raw names (CP437 'é' = 0x82, GBK) under a folder
z = ZW()
z.add(b'dir/', b'', method=0)
z.add(b'dir/caf\x82.txt', b'cp437 name')
z.add(b'dir/\xd6\xd0\xce\xc4.txt', b'gbk name')   # 中文 in GBK
z.add('dir/utf8-é.txt'.encode('utf-8'), b'utf8 name', flags=0x800)
write('cp437_names.zip', z.finish())

# --- 6. empty deflated entry with csize 0 (some writers), plus a normal one
z = ZW(); z.add(b'empty_d.txt', b'', method=8, raw_comp=b''); z.add(b'after.txt', b'after')
write('empty_deflate0.zip', z.finish())

# --- 7. data descriptors with and without signature
z = ZW(); z.add(b'sig.txt', b'S' * 1000, descriptor='sig'); z.add(b'nosig.txt', b'N' * 1000, descriptor='nosig')
z.add(b'stored_dd.bin', bytes(range(200)), method=0, descriptor='sig')
write('dd.zip', z.finish())

# --- 8. exactly 65535 entries (EOCD count == 0xFFFF, no zip64)
z = ZW()
for i in range(65535):
    z.add(b'f%d' % i, b'', method=0)
write('n65535.zip', z.finish())

# --- 9. trailing garbage after EOCD
write('trailing_zeros.zip', plain + bytes(64))

# --- 10. directories without entries
z = ZW(); z.add(b'x/y/z/deep.txt', b'deep'); z.add(b'x/top.txt', b't')
write('implicit_dirs.zip', z.finish())

# --- 11. zip64 extra in the CD with a foreign extra field before it and one after it, only lho overflowing
z = ZW()
z.add(b'a.txt', b'aaaa')
body_before = len(z.buf)
# entry b: placed at its true offset but advertised through ZIP64 (lho 0xFFFFFFFF)
ex = struct.pack('<HHI', 0x5455, 5, 0) [:4] + b'\x01\x00\x00\x00\x00'   # 0x5455 ext timestamp len 5
ex_after = struct.pack('<HH', 0xCAFE, 0)
lho_true = len(z.buf)
z.add(b'b.txt', b'bbbbbbbb', lho_override=0xFFFFFFFF)
z.cd[-1] = z.cd[-1][:6] + (ex + struct.pack('<HHQ', 1, 8, lho_true) + ex_after,) + z.cd[-1][7:]
write('zip64_lho_order.zip', z.finish())

# --- 12. symlink entry (unix mode 0120777) + normal file
z = ZW(); z.add(b'link', b'/etc/passwd', method=0, external=(0o120777 << 16)); z.add(b'f.txt', b'ff')
write('symlink.zip', z.finish())

# --- 13. file/dir name conflict
z = ZW(); z.add(b'a', b'file a'); z.add(b'a/b', b'inside a')
write('filedir_conflict.zip', z.finish())

# --- 14. unsupported methods mixed with ordinary
with zipfile.ZipFile('methods.zip', 'w') as zf:
    zf.writestr('1_ok.txt', 'ok one', zipfile.ZIP_DEFLATED)
    zf.writestr('2_bz2.txt', 'bzip2 data ' * 100, zipfile.ZIP_BZIP2)
    zf.writestr('3_lzma.txt', 'lzma data ' * 100, zipfile.ZIP_LZMA)
    zf.writestr('4_ok.txt', 'ok four', zipfile.ZIP_DEFLATED)

# --- 15. entry whose compressed stream is corrupt (CD CRC fine)
z = ZW(); good = b'good content\n' * 500
comp = bytearray(deflate_raw(good)); comp[len(comp)//2] ^= 0xFF; comp[len(comp)//2 + 1] ^= 0xFF
z.add(b'corrupt.txt', good, raw_comp=bytes(comp)); z.add(b'fine.txt', b'fine')
write('corrupt_stream.zip', z.finish())

# --- 16. nested self-named archive: outer name is nested.zip, contains nested.zip
inner = plain
z = ZW(); z.add(b'nested.zip', inner, method=0); z.add(b'other.txt', b'x')
write('nested.zip', z.finish())

# --- 17. AES-style entry: method 99 + 0x9901 extra (fake ciphertext, for metadata round-trip checks)
aes_extra = struct.pack('<HHHHBH', 0x9901, 7, 2, 0x4541, 3, 8)   # AE-2, vendor 'AE', strength 3, real method 8
body = b'\x00' * 16 + b'\x11\x22' + b'ciphertext-goes-here' + b'\x00' * 10
z = ZW(); z.add(b'secret.bin', b'plain', method=99, flags=1, raw_comp=body, extra_local=aes_extra, extra_cd=aes_extra)
write('aes_fake.zip', z.finish())

# --- 18. Info-ZIP unicode path extra (0x7075) with CP437 header name
uname = 'ünï.txt'.encode('utf-8')
legacy = b'\x81n\x8b.txt'   # CP437-ish bytes
ex = struct.pack('<HHBI', 0x7075, 5 + len(uname), 1, crc(legacy)) + uname
z = ZW(); z.add(legacy, b'unicode path extra', extra_local=ex, extra_cd=ex)
write('unicode_path_extra.zip', z.finish())

# --- 19. unusual: CD size in EOCD larger than the actual CD (padding after last CD record)
z = ZW(); z.add(b'p.txt', b'pp')
raw = bytearray(z.finish())
# insert 8 zero bytes between CD and EOCD, adjust cdsize
eocd_at = len(raw) - 22
raw[eocd_at:eocd_at] = bytes(8)
cdsize = struct.unpack_from('<I', raw, eocd_at + 8 + 12)[0]
struct.pack_into('<I', raw, eocd_at + 8 + 12, cdsize + 8)
write('cd_padding.zip', bytes(raw))
# --- 20. two AES-encrypted entries (method 99, the 0x9901 record in every local and central header; fake ciphertext of the right shape)
z = ZW()
for n in (b'secret.txt', b'notes.txt'):
    body = bytes(range(16)) + b'\x11\x22' + b'ciphertext of ' + n + b'.' * 12 + bytes(10)
    z.add(n, b'plain', method=99, flags=1, raw_comp=body, extra_local=aes_extra, extra_cd=aes_extra)
write('aes256.zip', z.finish())

# --- 21. an entry of more than 4 GiB uncompressed (zeros, about 4 MB deflated, zip64 sizes): a rewrite has to refuse it, not truncate its size
with zipfile.ZipFile('big_uncompressed.zip', 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
    with zf.open('big.bin', 'w', force_zip64=True) as w:
        block = bytes(1 << 20)
        for _ in range(4096 + 16): w.write(block)

print('ok', sorted(os.listdir('.')))
