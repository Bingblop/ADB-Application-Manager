/* Test program for ptyexec (run on the pty). Not shipped.
 *   no argument:  prints its terminal size, says if stdin is a terminal, echoes one line, exits with 5.
 *   "wait":       prints its pid and "ready", then waits for a signal (SIGHUP ends it, as it does most programs).
 *   "ignore":     the same, but SIGHUP is ignored, and so it is in a child (same process group) that waits too: prints "child PID". */
#include "sys.h"
struct winsize { unsigned short row, col, xp, yp; };
static void out(const char *s) { long n = 0; while (s[n]) n++; sc(SYS_write, 1, (long)s, n, 0, 0); }
static void outnum(long v) { char b[16]; int i = 15; b[i] = 0; if (!v) b[--i] = '0'; while (v) { b[--i] = (char)('0' + v % 10); v /= 10; } out(b + i); }
__attribute__((used)) void *memset(void *d, int c, unsigned long n) { unsigned char *a = d; while (n--) *a++ = (unsigned char)c; return d; }
static void idle(void) { for (;;) sc(SYS_ppoll, 0, 0, 0, 0, 0); }
__attribute__((used)) void c_start(long *sp) {
    long argc = sp[0]; char **argv = (char **)(sp + 1);
    if (argc > 1 && argv[1][0] == 'w') { out("pid "); outnum(sc(SYS_getpid, 0, 0, 0, 0, 0)); out("\nready\n"); idle(); }
    if (argc > 1 && argv[1][0] == 'i') {
        long act[4] = { 1, 0, 0, 0 };                      /* struct sigaction with SIG_IGN, no flags, empty mask */
        sc(SYS_rt_sigaction, 1, (long)act, 0, 8, 0);       /* SIGHUP */
#if defined(SYS_fork)
        long kid = sc(SYS_fork, 0, 0, 0, 0, 0);
#else
        long kid = sc(SYS_clone, 17, 0, 0, 0, 0);          /* SIGCHLD: a fork */
#endif
        if (kid == 0) idle();
        out("pid "); outnum(sc(SYS_getpid, 0, 0, 0, 0, 0)); out("\nchild "); outnum(kid); out("\nready\n"); idle();
    }
    struct winsize w; char termios[64];
    sc(SYS_ioctl, 0, 0x5413, (long)&w, 0, 0);
    out("size "); outnum(w.row); out(" "); outnum(w.col); out("\n");
    out(sc(SYS_ioctl, 0, 0x5401, (long)termios, 0, 0) == 0 ? "tty yes\n" : "tty no\n");
    char buf[64]; long n = sc(SYS_read, 0, (long)buf, 63, 0, 0);
    if (n > 0) { buf[n] = 0; out("got:"); out(buf); }
    sc(SYS_exit_group, 5, 0, 0, 0, 0);
    for (;;) {}
}
