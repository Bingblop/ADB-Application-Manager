#!/usr/bin/env bash
# Fails when the version about to be released is not the version the app and its changelog carry:
#   * AndroidManifest.xml's android:versionName is <version> or <version>-Pro;
#   * CHANGELOG.md has the heading "## v<version>" or "## v<version>-Pro", and the "(versionCode N)" on it is the manifest's android:versionCode.
#
#   tools/check-release-version.sh <version> [repository root]      (the root defaults to the current folder)
#
# release.yml runs it on the tag's version (tag v7.14.0 -> 7.14.0) before anything is built, so a tag pushed on the wrong commit, or a
# release pull request that missed the manifest or the changelog, is not published under a version the APKs do not carry.
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "usage: $0 <version> [repository root]" >&2
  exit 2
fi
version="$1"
root="${2:-.}"
case "$version" in
  ""|*[!0-9A-Za-z.-]*) echo "the version may hold only letters, digits, dots and dashes: $version" >&2; exit 2 ;;
esac
manifest="$root/AndroidManifest.xml"
changelog="$root/CHANGELOG.md"
[ -f "$manifest" ] || { echo "FAIL $manifest not found"; exit 1; }
[ -f "$changelog" ] || { echo "FAIL $changelog not found"; exit 1; }

# An attribute of the <manifest> element, which comes first in the file (empty when it is not there).
attr() { { grep -o "android:$1=\"[^\"]*\"" "$manifest" || true; } | head -1 | sed 's/^[^"]*"//; s/"$//'; }
name="$(attr versionName)"
code="$(attr versionCode)"

bad=0
if [ -z "$name" ]; then echo "FAIL AndroidManifest.xml has no android:versionName"; bad=1
elif [ "$name" != "$version" ] && [ "$name" != "$version-Pro" ]; then echo "FAIL AndroidManifest.xml has versionName $name, not $version"; bad=1
else echo "ok   AndroidManifest.xml has versionName $name"; fi

heading="$(awk -v v="$version" '{ sub(/\r$/, "") } $1 == "##" && ($2 == "v" v || $2 == "v" v "-Pro") { print; exit }' "$changelog")"
logged="$(printf '%s\n' "$heading" | sed -n 's/.*(versionCode \([0-9][0-9]*\)).*/\1/p')"
if [ -z "$heading" ]; then echo "FAIL CHANGELOG.md has no heading \"## v$version\" (or \"## v$version-Pro\")"; bad=1
elif [ -z "$logged" ]; then echo "FAIL CHANGELOG.md's heading \"$heading\" does not say (versionCode N)"; bad=1
elif [ "$logged" != "$code" ]; then echo "FAIL CHANGELOG.md's heading \"$heading\" names versionCode $logged, AndroidManifest.xml has ${code:-none}"; bad=1
else echo "ok   CHANGELOG.md has \"$heading\""; fi
exit "$bad"
