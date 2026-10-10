/*
 * ptyexec: runs a program on a pseudo-terminal and relays it over its own stdin and stdout, so that the app's terminal can run
 * programs that need a real terminal (vim, nano, top, htop, less, ssh, a Python prompt ...) as Termux does.
 *
 *   libptyexec.so ROWS COLS -- PROGRAM [ARGUMENTS...]
 *
 * stdin carries frames:  'D' len(2, big endian) bytes      keys typed: written to the terminal
 *                        'R' rows(2) cols(2)               the window size changed
 * stdout carries what the program wrote to its terminal, as it is. The exit status is the program's (128 + the signal when killed).
 * The environment is the caller's (TERM and the like are set by the app).
 * When the app goes away (stdin ends) the program gets SIGHUP; one that is still there 500 ms later is killed with its process group (SIGKILL).
 *
 * No C library: this is a static executable that uses system calls only, for aarch64, 32-bit ARM and (to test it on a computer) x86_64.
 * Android runs it from the app's native library folder, as it does libadb.so. Source and build: native/pty/build.sh.
 * Written for this app (ADB Application Manager Pro); no code from Termux or any other terminal.
 */
#include "sys.h"

#define O_RDWR 2
#define O_NOCTTY 0x100
#define AT_FDCWD (-100)
#define POLLIN 1
#define POLLERR 8
#define POLLHUP 16
#define SIGHUP 1
#define SIGKILL 9
#define SIGCHLD 17
#define EINTR 4
#define WNOHANG 1
#define CLOCK_MONOTONIC 1
#define GRACE_NS 500000000L      /* how long the program has to leave after the hang-up (PtyShell.close() ends this helper after 800 ms) */
#define TIOCSCTTY 0x540E
#define TIOCSWINSZ 0x5414
#define TIOCSPTLCK 0x40045431
#define TIOCGPTN 0x80045430

struct pollfd { int fd; short events; short revents; };
struct winsize { u16 row, col, xpixel, ypixel; };
struct timespec { long sec, nsec; };    /* the kernel's old layout: ppoll and clock_gettime take it on 32-bit ARM too */

void *memcpy(void *d, const void *s, unsigned long n) { u8 *a = d; const u8 *b = s; while (n--) *a++ = *b++; return d; }
void *memset(void *d, int c, unsigned long n) { u8 *a = d; while (n--) *a++ = (u8)c; return d; }

static long rd(int fd, void *b, long n) { long r; do r = sc(SYS_read, fd, (long)b, n, 0, 0); while (r == -EINTR); return r; }
static long wr_all(int fd, const void *b, long n) {
    const u8 *p = b;
    while (n > 0) { long r = sc(SYS_write, fd, (long)p, n, 0, 0); if (r == -EINTR) continue; if (r <= 0) return -1; p += r; n -= r; }
    return 0;
}
static __attribute__((noreturn)) void die(int code) { for (;;) sc(SYS_exit_group, code, 0, 0, 0, 0); }
static int num(const char *s) { int v = 0; while (*s >= '0' && *s <= '9') v = v * 10 + (*s++ - '0'); return v; }
static void put_err(const char *s) { long n = 0; while (s[n]) n++; wr_all(2, s, n); }
/* the hang-up: SIGHUP to the program, and from then on the clock runs; a program that ignores it is killed when the grace period is over (see reap) */
static struct timespec hup_at; static int hupped;
static void mono(struct timespec *t) { sc(SYS_clock_gettime, CLOCK_MONOTONIC, (long)t, 0, 0, 0); }
static void hangup(long pid) { if (hupped) return; hupped = 1; mono(&hup_at); sc(SYS_kill, pid, SIGHUP, 0, 0, 0); }
static long grace_left(void) {          /* nanoseconds of the grace period that remain, 0 when it is over (a long is 32 bits on ARM: after a second or more it is over, no sums) */
    struct timespec t; mono(&t);
    long s = t.sec - hup_at.sec; if (s > 1) return 0;
    long left = GRACE_NS - (s * 1000000000L + (t.nsec - hup_at.nsec));
    return left > 0 ? left : 0;
}
static void setsize(int fd, int rows, int cols) { struct winsize w; w.row = (u16)rows; w.col = (u16)cols; w.xpixel = 0; w.ypixel = 0; sc(SYS_ioctl, fd, TIOCSWINSZ, (long)&w, 0, 0); }

/* frames from the app: a state machine, as a read may end anywhere */
static int fstate, ftype, flen, fgot; static u8 fhead[4]; static int fhave;
static u8 fbuf[8192];
static int feed(int master, const u8 *p, long n) {
    while (n > 0) {
        if (fstate == 0) { ftype = *p++; n--; fstate = 1; fhave = 0; }
        else if (fstate == 1) {
            int need = ftype == 'D' ? 2 : ftype == 'R' ? 4 : 0;
            if (!need) { fstate = 0; continue; }
            while (n > 0 && fhave < need) { fhead[fhave++] = *p++; n--; }
            if (fhave < need) break;
            if (ftype == 'R') { setsize(master, (fhead[0] << 8) | fhead[1], (fhead[2] << 8) | fhead[3]); fstate = 0; }
            else { flen = (fhead[0] << 8) | fhead[1]; fgot = 0; fstate = flen ? 2 : 0; }
        } else {
            long take = flen - fgot; if (take > n) take = n;
            if (wr_all(master, p, take) < 0) return -1;
            p += take; n -= take; fgot += take;
            if (fgot == flen) fstate = 0;
        }
    }
    return 0;
}

__attribute__((used)) void c_start(long *sp) {
    long argc = sp[0]; char **argv = (char **)(sp + 1); char **envp = argv + argc + 1;
    if (argc < 5 || argv[3][0] != '-' || argv[3][1] != '-') { put_err("usage: ptyexec ROWS COLS -- PROGRAM [ARGUMENTS...]\n"); die(2); }
    int rows = num(argv[1]), cols = num(argv[2]);
    if (rows < 1) rows = 24;
    if (cols < 1) cols = 80;
    int master = (int)sc(SYS_openat, AT_FDCWD, (long)"/dev/ptmx", O_RDWR | O_NOCTTY, 0, 0);
    if (master < 0) { put_err("ptyexec: cannot open /dev/ptmx\n"); die(3); }
    int zero = 0, ptn = 0;
    sc(SYS_ioctl, master, TIOCSPTLCK, (long)&zero, 0, 0);
    if (sc(SYS_ioctl, master, TIOCGPTN, (long)&ptn, 0, 0) < 0) { put_err("ptyexec: no terminal number\n"); die(3); }
    char path[32]; const char *pre = "/dev/pts/"; int k = 0; while (pre[k]) { path[k] = pre[k]; k++; }
    char dig[12]; int dn = 0; int t = ptn; if (!t) dig[dn++] = '0'; while (t) { dig[dn++] = (char)('0' + t % 10); t /= 10; }
    while (dn) path[k++] = dig[--dn];
    path[k] = 0;
    int slave = (int)sc(SYS_openat, AT_FDCWD, (long)path, O_RDWR | O_NOCTTY, 0, 0);
    if (slave < 0) { put_err("ptyexec: cannot open the terminal\n"); die(3); }
    setsize(master, rows, cols);
#if defined(SYS_fork)
    long pid = sc(SYS_fork, 0, 0, 0, 0, 0);
#else
    long pid = sc(SYS_clone, SIGCHLD, 0, 0, 0, 0);
#endif
    if (pid < 0) { put_err("ptyexec: cannot start the program\n"); die(3); }
    if (pid == 0) {
        sc(SYS_close, master, 0, 0, 0, 0);
        sc(SYS_setsid, 0, 0, 0, 0, 0);
        sc(SYS_ioctl, slave, TIOCSCTTY, 0, 0, 0);
        sc(SYS_dup3, slave, 0, 0, 0, 0); sc(SYS_dup3, slave, 1, 0, 0, 0); sc(SYS_dup3, slave, 2, 0, 0, 0);
        if (slave > 2) sc(SYS_close, slave, 0, 0, 0, 0);
        sc(SYS_execve, (long)argv[4], (long)(argv + 4), (long)envp, 0, 0);
        put_err("ptyexec: cannot run "); put_err(argv[4]); put_err("\n");
        die(127);
    }
    sc(SYS_close, slave, 0, 0, 0, 0);
    u8 buf[16384];
    int stdin_open = 1, out_open = 1;
    for (;;) {
        struct pollfd fds[2];
        fds[0].fd = stdin_open ? 0 : -1; fds[0].events = POLLIN; fds[0].revents = 0;
        fds[1].fd = master; fds[1].events = out_open ? POLLIN : 0; fds[1].revents = 0;       /* without a reader only the end (POLLHUP) is of interest */
        struct timespec tmo; tmo.sec = 0; tmo.nsec = hupped ? grace_left() : 0;
        if (hupped && !tmo.nsec) break;                      /* the grace period is over: reap kills what is left */
        long r = sc(SYS_ppoll, (long)fds, 2, hupped ? (long)&tmo : 0, 0, 0);
        if (r < 0) { if (r == -EINTR) continue; break; }
        if (fds[1].revents & (POLLIN | POLLHUP | POLLERR)) {
            long n = rd(master, buf, sizeof buf);
            if (n <= 0) break;                               /* the program closed its terminal */
            if (out_open && wr_all(1, buf, n) < 0) { out_open = 0; hangup(pid); }          /* nobody reads the output any more: hang up, drop what comes */
        }
        if (stdin_open && (fds[0].revents & (POLLIN | POLLHUP | POLLERR))) {
            long n = rd(0, fbuf, sizeof fbuf);
            if (n <= 0) { stdin_open = 0; hangup(pid); }       /* the app went away: hang up, as a terminal window closing does */
            else if (feed(master, fbuf, n) < 0) stdin_open = 0;
        }
    }
    /* reap: wait for the program. After a hang-up it gets the rest of the grace period, then it and everything in its process group (it leads the session) is killed */
    int status = 0, flags = hupped ? WNOHANG : 0;
    for (;;) {
        long w = sc(SYS_wait4, pid, (long)&status, flags, 0, 0);
        if (w == -EINTR) continue;
        if (w != 0) break;
        long left = grace_left();
        if (!left) { sc(SYS_kill, -pid, SIGKILL, 0, 0, 0); flags = 0; continue; }
        struct timespec nap; nap.sec = 0; nap.nsec = left < 2000000L ? left : 2000000L;
        sc(SYS_ppoll, 0, 0, (long)&nap, 0, 0);
    }
    int code = (status & 0x7f) == 0 ? ((status >> 8) & 0xff) : 128 + (status & 0x7f);
    die(code);
}
