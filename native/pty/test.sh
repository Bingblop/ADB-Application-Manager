#!/bin/bash
# Runs the helper for the phone's architectures under qemu-user (apt install qemu-user) with a static test program on the terminal, and for this computer natively with python3 /.. see below.
cd "$(dirname "$0")"
python3 - <<'PY'
import subprocess, struct, time, os, select, sys, re
def alive(pid):                                    # a zombie (its parent is not a process that reaps) is gone
    try: return open('/proc/%d/stat'%pid).read().rsplit(')',1)[1].split()[0]!='Z'
    except OSError: return False
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
    # hang-up: the app goes away (stdin ends). A program that obeys SIGHUP ends at once with 128+1; one that ignores it (and a child of it in the same
    # process group) is killed after the 500 ms grace period: status 128+9, and nothing is left behind.
    for mode,want_rc,lo,hi in (('wait',129,0.0,0.4),('ignore',137,0.45,1.0)):
        p=subprocess.Popen(cmd+[mode],stdin=subprocess.PIPE,stdout=subprocess.PIPE)
        out=b''; end=time.time()+5
        while b'ready' not in out and time.time()<end:
            r,_,_=select.select([p.stdout],[],[],0.2)
            if r:
                d=os.read(p.stdout.fileno(),4096)
                if not d: break
                out+=d
        pids=[int(x) for x in re.findall(rb'(?:pid|child) (\d+)',out)]
        t0=time.time(); p.stdin.close()
        try: rc=p.wait(timeout=5)
        except subprocess.TimeoutExpired: p.kill(); rc=p.wait()
        dt=time.time()-t0
        time.sleep(0.2)
        left=[q for q in pids if alive(q)]
        good=(b'ready' in out and len(pids)==(1 if mode=='wait' else 2) and rc==want_rc and lo<=dt<hi and not left)
        print(('ok   ' if good else 'FAIL ')+arch+': hang-up, program that '+('obeys SIGHUP' if mode=='wait' else 'ignores SIGHUP (and its child)')+': status %d after %.2f s, left over %s'%(rc,dt,left), '' if good else out); ok=ok and good
        for q in left:
            try: os.kill(q,9)
            except OSError: pass
sys.exit(0 if ok else 1)
PY
