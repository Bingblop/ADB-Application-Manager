import zipfile, io, os, struct, random, subprocess, sys, time
os.makedirs(sys.argv[1], exist_ok=True)
os.chdir(sys.argv[1])           # where the fixtures are written
random.seed(7)

# ---- basic.zip
png = b'\x89PNG\r\n\x1a\n' + bytes(random.getrandbits(8) for _ in range(300))
with zipfile.ZipFile('basic.zip', 'w') as z:
    z.comment = b'hello comment'
    z.writestr('readme.txt', 'hello\nworld\n', zipfile.ZIP_DEFLATED)
    z.writestr(zipfile.ZipInfo('docs/'), '')
    z.writestr('docs/a.txt', 'alpha ' * 1000, zipfile.ZIP_DEFLATED)
    z.writestr('docs/sub/deep/b.md', '# title\n' + 'x' * 5000, zipfile.ZIP_DEFLATED)
    z.writestr('img/pic.png', png, zipfile.ZIP_STORED)
    z.writestr('empty.bin', b'', zipfile.ZIP_STORED)
    z.writestr('ünï/çödé.txt', 'unicode name ✓', zipfile.ZIP_DEFLATED)
    z.writestr('big.txt', ('line of text %d\n' * 1) * 1 + ''.join('row %d some repeated text\n' % i for i in range(120000)), zipfile.ZIP_DEFLATED)
    z.writestr('bin.dat', bytes(random.getrandbits(8) for _ in range(100000)), zipfile.ZIP_STORED)
    z.writestr('crlf.txt', 'a\r\nb\r\nc\r\n', zipfile.ZIP_DEFLATED)

# ---- stream.zip (data descriptors: written to an unseekable stream)
class Unseek(io.RawIOBase):
    def __init__(self, path): self.f = open(path, 'wb'); self.n = 0
    def writable(self): return True
    def seekable(self): return False
    def write(self, b): self.n += len(b); return self.f.write(b)
    def tell(self): return self.n
    def flush(self): self.f.flush()
    def close(self): self.f.flush(); self.f.close()
u = Unseek('stream.zip')
with zipfile.ZipFile(u, 'w') as z:
    z.writestr('a.txt', 'streamed ' * 2000, zipfile.ZIP_DEFLATED)
    z.writestr('dir/b.bin', bytes(range(256)) * 50, zipfile.ZIP_STORED)
u.close()

# ---- evil.zip (zip-slip names)
with zipfile.ZipFile('evil.zip', 'w') as z:
    for n in ['../../evil.txt', '/abs/path.txt', 'a/../../b.txt', 'ok/fine.txt', 'back\\slash\\win.txt', './dot/seg.txt']:
        z.writestr(zipfile.ZipInfo(n), 'payload ' + n)

# ---- many.zip (70000 entries -> zip64 EOCD)
with zipfile.ZipFile('many.zip', 'w', zipfile.ZIP_STORED, allowZip64=True) as z:
    for i in range(70000):
        z.writestr('d%d/f%d.txt' % (i % 50, i), '')

# ---- enc.zip (ZipCrypto)
open('secret.txt', 'w').write('top secret\n' * 100)
subprocess.run(['zip', '-q', '-P', 'pw123', 'enc.zip', 'secret.txt'], check=True)
os.remove('secret.txt')

# ---- apkish.zip: unaligned stored entries (resources.arsc, a .so) + deflated + META-INF + fake signing block
with zipfile.ZipFile('apkish.zip', 'w') as z:
    z.writestr('AndroidManifest.xml', b'\x03\x00\x08\x00' + b'\x00' * 200, zipfile.ZIP_DEFLATED)
    z.writestr('resources.arsc', bytes(random.getrandbits(8) for _ in range(1001)), zipfile.ZIP_STORED)
    z.writestr('lib/arm64-v8a/libx.so', bytes(random.getrandbits(8) for _ in range(5003)), zipfile.ZIP_STORED)
    z.writestr('classes.dex', b'dex\n035\x00' + bytes(random.getrandbits(8) for _ in range(3000)), zipfile.ZIP_DEFLATED)
    z.writestr('assets/config.json', '{"a": 1, "b": [1,2,3]}\n', zipfile.ZIP_DEFLATED)
    z.writestr('res/layout/main.xml', b'\x03\x00\x08\x00' + b'\x01' * 64, zipfile.ZIP_DEFLATED)
    z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n', zipfile.ZIP_DEFLATED)

# ---- zip64 synthetic: central entry advertises 5 GB sizes through a zip64 extra field
name = b'huge.bin'
extra = struct.pack('<HHQQQ', 1, 24, 5 * 2**30, 4 * 2**30, 0)   # size, csize, lho
cd = struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, 45, 45, 0, 8, 0, 0x21, 0, 0xFFFFFFFF, 0xFFFFFFFF, len(name), len(extra), 0, 0, 0, 0, 0xFFFFFFFF) + name + extra
lh = struct.pack('<IHHHHHIIIHH', 0x04034b50, 45, 0, 8, 0, 0x21, 0, 0, 0, len(name), 0) + name
body = lh
cd_off = len(body)
eocd = struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, 1, 1, len(cd), cd_off, 0)
open('zip64synth.zip', 'wb').write(body + cd + eocd)

# ---- junk files
open('notzip.bin', 'wb').write(os.urandom(5000))
open('tiny.zip', 'wb').write(struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, 0, 0, 0, 0, 0))
data = open('basic.zip', 'rb').read()
open('truncated.zip', 'wb').write(data[:len(data) // 2])
print('fixtures ok', os.listdir('.'))
