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
if [ "${SKIP_GRADLE:-}" != "1" ]; then
    echo "[*] Building the engine with Gradle ..."
    "$GRADLE" --no-daemon -q engineLibs -PpatcherVersion="${PATCHER_TAG#v}" -PpatcherSrc="$UP"
fi

# Patch bundles reach some `internal` members of morphe-patcher by reflection under the compiled name morphe-patcher's own build gives them
# (BytecodePatchContext.getPatchClasses$morphe_patcher: the Gboard bundle does). That name holds the Kotlin module name, so a build under another
# module name makes every such patch fail with NoSuchMethodException. engine/build.gradle.kts sets moduleName; this makes sure it held.
python3 -I - "$ENGINE_DIR/build/engine-libs" <<'PY' || { echo "Error: the engine was built under another Kotlin module name (see engine/build.gradle.kts)"; exit 1; }
import sys, glob, zipfile
need = [b'getPatchClasses$morphe_patcher', b'getOpcodes$morphe_patcher', b'addClass$morphe_patcher']
found = set()
for j in glob.glob(sys.argv[1] + '/morphe-engine-*.jar'):
    with zipfile.ZipFile(j) as z:
        for n in z.namelist():
            if n.endswith('.class') and n.startswith('app/morphe/patcher/'):
                d = z.read(n)
                for k in need:
                    if k in d: found.add(k)
                if b'$com_bloatware_bingblop_morphe_engine' in d: sys.exit(1)
sys.exit(0 if len(found) == len(need) else 1)
PY

echo "[*] Converting to dex (d8) ..."
BOOT="${BOOTCLASSPATH:-${ANDROID_JAR:-}}"
[ -n "$BOOT" ] && [ -f "$BOOT" ] || { echo "Error: set ANDROID_JAR (or BOOTCLASSPATH) to an android.jar"; exit 1; }
if [ -n "${D8_JAR:-}" ]; then D8=(java -cp "$D8_JAR" com.android.tools.r8.D8); else D8=(${D8:-d8}); fi
OUT="$ENGINE_DIR/build/dex"
rm -rf "$OUT"; mkdir -p "$OUT" "$ENGINE_DIR/dist"
"${D8[@]}" --release --min-api "$MIN_SDK" --lib "$BOOT" --output "$OUT" build/engine-libs/*.jar 2>&1 | grep -v "^Info\|^Warning\|^Classes with missing\|^Superclass\|^Picked up" || true
ls "$OUT"/classes*.dex >/dev/null

# d8 drops everything that is not a class. Kotlin reflection (the patcher reads every patch of a bundle with it) needs the built-in metadata
# of kotlin-stdlib (kotlin/**/*.kotlin_builtins) and the service files of kotlin-reflect at run time: without them every bundle fails on
# Android with "KotlinReflectionInternalError: Unresolved class: class java.lang.String" (it works on a JVM, where they are on the class path).
# ARSCLib's framework APKs and the small .properties files are read the same way. build.sh puts these files into the APK next to the dex files.
RES="$ENGINE_DIR/build/res"
rm -rf "$RES"; mkdir -p "$RES"
python3 -I - "$ENGINE_DIR/build/engine-libs" "$RES" <<'PY'
import sys, zipfile, glob, os
libs, out = sys.argv[1:3]
def want(jar, n):
    if n.endswith('/') or n.endswith('.class'): return False
    if n.endswith('.kotlin_builtins'): return True
    if n.startswith('META-INF/services/') and any(k in jar for k in ('kotlin-reflect', 'kotlin-stdlib', 'kotlinx-')): return True
    if n.startswith('frameworks/'): return True
    return n in ('arsclib.properties', 'smali.properties', 'app/morphe/patcher/version.properties')
for j in sorted(glob.glob(libs + '/*.jar')):
    with zipfile.ZipFile(j) as z:
        for n in z.namelist():
            if want(os.path.basename(j), n):
                d = os.path.join(out, n); os.makedirs(os.path.dirname(d), exist_ok=True)
                with open(d, 'wb') as f: f.write(z.read(n))
PY
( cd "$OUT" && rm -f "$ENGINE_DIR/dist/morphe-engine-dex.zip" && zip -q -9 "$ENGINE_DIR/dist/morphe-engine-dex.zip" classes*.dex && cd "$RES" && zip -q -9 -r "$ENGINE_DIR/dist/morphe-engine-dex.zip" . )
{
    echo "morphe-patcher ${PATCHER_TAG}"
    echo "launcher protocol 1"
    ( cd "$ENGINE_DIR/dist" && sha256sum morphe-engine-dex.zip )
    ls build/engine-libs | sed 's/^/  /'
} > "$ENGINE_DIR/dist/ENGINE.txt"
echo "[ok] engine/dist/morphe-engine-dex.zip ($(du -h "$ENGINE_DIR/dist/morphe-engine-dex.zip" | cut -f1))"
