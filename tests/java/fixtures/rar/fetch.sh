#!/bin/sh
# Downloads real RAR archives for the rarreader suite (see README.md). Needs curl and python3 and access to github.
# Names that do not exist upstream are skipped.
cd "$(dirname "$0")" || exit 1
RF=https://raw.githubusercontent.com/markokr/rarfile/master/test/files
LA=https://raw.githubusercontent.com/libarchive/libarchive/master/libarchive/test
get() { curl -fsSL -o "$2" "$1" 2>/dev/null && echo "ok   $2" || { rm -f "$2"; echo "skip $2"; }; }
for n in rar3-comment-plain rar3-comment-psw rar3-comment-hpsw rar3-old rar3-solid-qo rar3-readonly-unix rar3-readonly-win rar3-vols.part1 \
         rar5-blake rar5-crc rar5-dups rar5-hpsw rar5-psw rar5-psw-blake rar5-quick-open rar5-solid-qo rar5-times rar5-times2 \
         seektest unicode unicode-hp ctime0 ctime1 ctime2 ctime3 ctime4 rar15-comment rar202-comment-nopsw rar202-comment-psw; do
  get "$RF/$n.rar" "$n.rar"
  get "$RF/$n.rar.exp" "$n.exp"
done
for n in stored compressed solid blake2 arm_filter delta_filter x86_filter symlink hardlink empty_file unicode extra_field_version_1 \
         multiarchive.part01 encrypted encrypted_filenames; do
  f="test_read_format_rar5_$n.rar.uu"
  if curl -fsSL -o "$f" "$LA/$f" 2>/dev/null; then
    python3 - "$f" "libarchive-rar5-$n.rar" <<'PY' && echo "ok   libarchive-rar5-$n.rar" || echo "skip libarchive-rar5-$n.rar"
import sys, binascii
lines = open(sys.argv[1], 'rb').read().split(b'\n')
out = bytearray(); on = False
for l in lines:
    if l.startswith(b'begin '): on = True; continue
    if l.startswith(b'end'): break
    if on and l: out += binascii.a2b_uu(l)
open(sys.argv[2], 'wb').write(out)
PY
    rm -f "$f"
  else echo "skip libarchive-rar5-$n.rar"; fi
done
rm -f *.part2.rar *.part3.rar
du -sh .
