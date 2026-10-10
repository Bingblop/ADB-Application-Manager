#!/bin/bash
# Builds the pty helper for the phone (arm64-v8a, armeabi-v7a) and for a computer (x86_64, for the tests).
# Needs clang and lld only: the helper uses no C library. The results are kept in the repository (like libadb.so), so building the app needs none of this.
set -e
cd "$(dirname "$0")"
CF="-O2 -ffreestanding -fno-builtin -fno-stack-protector -fno-asynchronous-unwind-tables -fno-pic -static -nostdlib -fuse-ld=lld -Wl,--build-id=none -Wl,-z,norelro -Wl,--gc-sections"
mkdir -p arm64-v8a armeabi-v7a x86_64
clang -target aarch64-linux-gnu $CF -o arm64-v8a/libptyexec.so ptyexec.c
clang -target arm-linux-gnueabi -march=armv7-a -mfloat-abi=soft $CF -o armeabi-v7a/libptyexec.so ptyexec.c
clang -target x86_64-linux-gnu $CF -o x86_64/ptyexec ptyexec.c
llvm-strip --strip-all arm64-v8a/libptyexec.so armeabi-v7a/libptyexec.so x86_64/ptyexec
# the test program (run by native/pty/test.sh under qemu-user for the phone's architectures)
mkdir -p test
clang -target aarch64-linux-gnu $CF -o test/prog-aarch64 ptytestprog.c
clang -target arm-linux-gnueabi -march=armv7-a -mfloat-abi=soft $CF -o test/prog-arm ptytestprog.c
clang -target x86_64-linux-gnu $CF -o test/prog-x86_64 ptytestprog.c
ls -l arm64-v8a/libptyexec.so armeabi-v7a/libptyexec.so x86_64/ptyexec
