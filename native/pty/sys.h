/* system calls and the start of a program without a C library, for aarch64, 32-bit ARM and x86_64 (ptyexec.c and its test program use it) */
#ifndef PTY_SYS_H
#define PTY_SYS_H
typedef unsigned long uptr;
typedef unsigned char u8;
typedef unsigned short u16;

#if defined(__x86_64__)
#define SYS_read 0
#define SYS_write 1
#define SYS_close 3
#define SYS_openat 257
#define SYS_ioctl 16
#define SYS_dup3 292
#define SYS_fork 57
#define SYS_execve 59
#define SYS_setsid 112
#define SYS_wait4 61
#define SYS_kill 62
#define SYS_exit_group 231
#define SYS_ppoll 271
#define SYS_clock_gettime 228
#define SYS_rt_sigaction 13
#define SYS_getpid 39
static inline long sc(long n, long a, long b, long c, long d, long e) {
    long r; register long r10 __asm__("r10") = d; register long r8 __asm__("r8") = e;
    __asm__ volatile("syscall" : "=a"(r) : "a"(n), "D"(a), "S"(b), "d"(c), "r"(r10), "r"(r8) : "rcx", "r11", "memory");
    return r;
}
__asm__(".globl _start\n_start:\n xor %rbp,%rbp\n mov %rsp,%rdi\n and $-16,%rsp\n call c_start\n");
#elif defined(__aarch64__)
#define SYS_read 63
#define SYS_write 64
#define SYS_close 57
#define SYS_openat 56
#define SYS_ioctl 29
#define SYS_dup3 24
#define SYS_clone 220
#define SYS_execve 221
#define SYS_setsid 157
#define SYS_wait4 260
#define SYS_kill 129
#define SYS_exit_group 94
#define SYS_ppoll 73
#define SYS_clock_gettime 113
#define SYS_rt_sigaction 134
#define SYS_getpid 172
static inline long sc(long n, long a, long b, long c, long d, long e) {
    register long x8 __asm__("x8") = n; register long x0 __asm__("x0") = a; register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c; register long x3 __asm__("x3") = d; register long x4 __asm__("x4") = e;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4) : "memory", "cc");
    return x0;
}
__asm__(".globl _start\n_start:\n mov x0, sp\n bl c_start\n");
#elif defined(__arm__)
#define SYS_read 3
#define SYS_write 4
#define SYS_close 6
#define SYS_openat 322
#define SYS_ioctl 54
#define SYS_dup3 358
#define SYS_fork 2
#define SYS_execve 11
#define SYS_setsid 66
#define SYS_wait4 114
#define SYS_kill 37
#define SYS_exit_group 248
#define SYS_ppoll 336
#define SYS_clock_gettime 263
#define SYS_rt_sigaction 174
#define SYS_getpid 20
static inline long sc(long n, long a, long b, long c, long d, long e) {
    register long r7 __asm__("r7") = n; register long r0 __asm__("r0") = a; register long r1 __asm__("r1") = b;
    register long r2 __asm__("r2") = c; register long r3 __asm__("r3") = d; register long r4 __asm__("r4") = e;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r7), "r"(r1), "r"(r2), "r"(r3), "r"(r4) : "memory", "cc");
    return r0;
}
__asm__(".globl _start\n_start:\n mov r0, sp\n and sp, sp, #-8\n bl c_start\n");
#else
#error unsupported architecture
#endif

#endif
