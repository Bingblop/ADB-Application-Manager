#!/usr/bin/env bash
# Fails when an APK is not signed by the release certificate (or by more than one).
#
#   tools/check-signing-cert.sh <expected SHA-256 of the certificate> <apk>...
#
# The expected value is the release certificate's SHA-256 (colons and case do not matter); it is published in the README.
# Reads the certificate with apksigner (on PATH, or APKSIGNER=/path/to/apksigner).
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <expected SHA-256> <apk>..." >&2
  exit 2
fi
norm() { printf '%s' "$1" | tr -d ': \t\r' | tr 'A-F' 'a-f'; }
expected="$(norm "$1")"; shift
case "$expected" in
  *[!0-9a-f]*|"") echo "the expected SHA-256 is not hexadecimal: $expected" >&2; exit 2 ;;
esac
[ "${#expected}" -eq 64 ] || { echo "the expected SHA-256 is not 64 hex characters: $expected" >&2; exit 2; }

apksigner="${APKSIGNER:-$(command -v apksigner || true)}"
[ -n "$apksigner" ] || { echo "apksigner not found (put it on PATH or set APKSIGNER)" >&2; exit 2; }

bad=0
for apk in "$@"; do
  out="$("$apksigner" verify --verbose --print-certs "$apk" 2>&1)" || { echo "FAIL $apk does not verify:"; echo "$out" | head -5; bad=1; continue; }
  signers="$(printf '%s\n' "$out" | sed -n 's/^Number of signers: //p' | head -1)"
  got="$(norm "$(printf '%s\n' "$out" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)")"
  if [ "$signers" != "1" ]; then echo "FAIL $apk has $signers signers (expected 1)"; bad=1
  elif [ "$got" != "$expected" ]; then echo "FAIL $apk is signed with certificate SHA-256 $got, expected $expected"; bad=1
  else echo "ok   $apk: certificate SHA-256 $got"; fi
done
exit "$bad"
