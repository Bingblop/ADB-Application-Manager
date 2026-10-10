#!/bin/bash
# Runs the helper for the phone's architectures under qemu-user (apt install qemu-user) with a static test program on the terminal, and for this computer natively with python3 /.. see below.
cd "$(dirname "$0")"
python3 - <<'PY'
import subprocess, struct, time, os, select, sys
def fr(b): return b'D'+struct.pack('>H',len(b))+b
def rz(r,c): return b'R'+struct.pack('>HH',r,c)
ok=True
for arch,qemu,helper,prog in (('aarch64','qemu-aarch64','arm64-v8a/libptyexec.so','test/prog-aarch64'),('arm','qemu-arm','armeabi-v7a/libptyexec.so','test/prog-arm'),('x86_64',None,'x86_64/ptyexec','test/prog-x86_64')):
    cmd=([qemu] if qemu else [])+[helper,'33','97','--']+(['/usr/bin/'+qemu] if qemu else [])+[prog]    # the child is the emulator running the test program (this machine cannot exec a foreign binary itself)
    p=subprocess.Popen(cmd,stdin=subprocess.PIPE,stdout=subprocess.PIPE)
    time.sleep(0.5)
    p.stdin.write(fr(b'hello\n')); p.stdin.flush()
    out=b''; end=time.time()+3
    while time.time()<end:
        r,_,_=select.select([p.stdout],[],[],0.2)
        if r:
            d=os.read(p.stdout.fileno(),4096)
            if not d: break
            out+=d
    rc=p.wait(timeout=5)
    good=(b'size 33 97' in out and b'tty yes' in out and b'got:hello' in out and rc==5)
    print(('ok   ' if good else 'FAIL ')+arch+': terminal size, tty, input echoed, exit code', '' if good else (out,rc)); ok=ok and good
sys.exit(0 if ok else 1)
PY
