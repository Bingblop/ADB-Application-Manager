/* Test program for ptyexec (run on the pty): prints its terminal size, says if stdin is a terminal, echoes one line, exits with 5. Not shipped. */
#include "sys.h"
struct winsize { unsigned short row, col, xp, yp; };
static void out(const char *s) { long n = 0; while (s[n]) n++; sc(SYS_write, 1, (long)s, n, 0, 0); }
static void outnum(long v) { char b[16]; int i = 15; b[i] = 0; if (!v) b[--i] = '0'; while (v) { b[--i] = (char)('0' + v % 10); v /= 10; } out(b + i); }
__attribute__((used)) void *memset(void *d, int c, unsigned long n) { unsigned char *a = d; while (n--) *a++ = (unsigned char)c; return d; }
__attribute__((used)) void c_start(long *sp) {
    (void)sp;
    struct winsize w; char termios[64];
    sc(SYS_ioctl, 0, 0x5413, (long)&w, 0, 0);
    out("size "); outnum(w.row); out(" "); outnum(w.col); out("\n");
    out(sc(SYS_ioctl, 0, 0x5401, (long)termios, 0, 0) == 0 ? "tty yes\n" : "tty no\n");
    char buf[64]; long n = sc(SYS_read, 0, (long)buf, 63, 0, 0);
    if (n > 0) { buf[n] = 0; out("got:"); out(buf); }
    sc(SYS_exit_group, 5, 0, 0, 0, 0);
    for (;;) {}
}
