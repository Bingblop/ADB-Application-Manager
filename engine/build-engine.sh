#!/usr/bin/env bash
# Rebuilds the Morphe engine of the Morphe Patcher tab: engine/dist/morphe-engine-dex.zip (the dex files that build.sh adds to the APK).
#
# What goes in: the UNMODIFIED source of morphe-patcher at the pinned tag (GPL-3.0, https://github.com/MorpheApp/morphe-patcher, cloned into
# engine/.upstream), the small launcher in engine/src/main (this repo), and the libraries morphe-patcher needs (Kotlin, guava, ARSCLib,
# smali, Bouncy Castle, apksig ...; see engine/build.gradle.kts). Gradle + JDK 17+ build them, d8 turns the result into dex files.
#
#   engine/build-engine.sh                       # pinned tag
#   PATCHER_TAG=v1.16.0 engine/build-engine.sh   # another morphe-patcher release (then check that the launcher still compiles)
#   engine/build-engine.sh --smoke bundle.mpp apk   # no rebuild: run the real engine on this computer (JVM) against a bundle and an APK
#
# Needs: git, gradle (8.x; the tool, not a wrapper), a JDK, and d8 (D8_JAR=/path/r8.jar, or d8 on PATH) with ANDROID_JAR / BOOTCLASSPATH.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
ENGINE_DIR="$(pwd)"

PATCHER_TAG="${PATCHER_TAG:-v1.15.1}"
UP="$ENGINE_DIR/.upstream/morphe-patcher"
GRADLE="${GRADLE:-gradle}"
MIN_SDK=26

if [ "${1:-}" = "--smoke" ]; then
    BUNDLE="${2:?bundle.mpp}"; APK="${3:?apk}"
    [ -d build/engine-libs ] || { echo "run engine/build-engine.sh first (no engine-libs)"; exit 1; }
    TMP="$(mktemp -d)"
    cat > "$TMP/list.json" <<JSON
{"out":"$TMP/list-out.json","bundles":["$BUNDLE"]}
JSON
    java -cp "build/engine-libs/*" com.bloatware.bingblop.morphe.EngineMain list "$TMP/list.json"
    python3 -c "import json,sys; d=json.load(open('$TMP/list-out.json')); [print(b['file'], b['ok'], len(b.get('patches',[])), 'patches') for b in d['bundles']]"
    echo "$TMP"
    exit 0
fi

if [ ! -f "$UP/build.gradle.kts" ]; then
    echo "[*] Cloning morphe-patcher $PATCHER_TAG ..."
    rm -rf "$UP"; mkdir -p "$(dirname "$UP")"
    git clone --depth 1 --branch "$PATCHER_TAG" https://github.com/MorpheApp/morphe-patcher "$UP"
fi
echo "[*] Building the engine with Gradle ..."
"$GRADLE" --no-daemon -q engineLibs -PpatcherVersion="${PATCHER_TAG#v}" -PpatcherSrc="$UP"

echo "[*] Converting to dex (d8) ..."
BOOT="${BOOTCLASSPATH:-${ANDROID_JAR:-}}"
[ -n "$BOOT" ] && [ -f "$BOOT" ] || { echo "Error: set ANDROID_JAR (or BOOTCLASSPATH) to an android.jar"; exit 1; }
if [ -n "${D8_JAR:-}" ]; then D8=(java -cp "$D8_JAR" com.android.tools.r8.D8); else D8=(${D8:-d8}); fi
OUT="$ENGINE_DIR/build/dex"
rm -rf "$OUT"; mkdir -p "$OUT" "$ENGINE_DIR/dist"
"${D8[@]}" --release --min-api "$MIN_SDK" --lib "$BOOT" --output "$OUT" build/engine-libs/*.jar 2>&1 | grep -v "^Info\|^Warning\|^Classes with missing\|^Superclass\|^Picked up" || true
ls "$OUT"/classes*.dex >/dev/null

( cd "$OUT" && rm -f "$ENGINE_DIR/dist/morphe-engine-dex.zip" && zip -q -9 "$ENGINE_DIR/dist/morphe-engine-dex.zip" classes*.dex )
{
    echo "morphe-patcher ${PATCHER_TAG}"
    echo "launcher protocol 1"
    ( cd "$ENGINE_DIR/dist" && sha256sum morphe-engine-dex.zip )
    ls build/engine-libs | sed 's/^/  /'
} > "$ENGINE_DIR/dist/ENGINE.txt"
echo "[ok] engine/dist/morphe-engine-dex.zip ($(du -h "$ENGINE_DIR/dist/morphe-engine-dex.zip" | cut -f1))"
