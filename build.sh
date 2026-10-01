#!/usr/bin/env bash
# ADB Application Manager Pro - APK build script
#
# Works in Termux and on a regular Linux machine. Every tool can be overridden with an env var.
#
# Termux setup (one time):
#   pkg install aapt2 apksigner d8 ecj zipalign openjdk-17 python
#
# Usage:
#   ./build.sh                    # builds bin/ADB_Application_Manager_Pro.apk
#   KEYSTORE=~/release.keystore ./build.sh
#
# Signing: Android only installs an update over an existing install when both are signed with the
# SAME key. Put the keystore you used before at ./release.keystore (alias "adbmanager", password
# "password", like the original compile.sh) to update in place. If no keystore exists, a new one
# is generated and you must uninstall the old app once before installing.

set -euo pipefail

BOLD="\033[1m"; GREEN="\033[1;32m"; RED="\033[1;31m"; YELLOW="\033[1;33m"; CYAN="\033[1;36m"; RESET="\033[0m"
step() { echo -e "${YELLOW}[*] $*${RESET}"; }
fail() { echo -e "${RED}Error: $*${RESET}" >&2; exit 1; }

WORK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$WORK_DIR"

APP_NAME="ADB_Application_Manager_Pro"
MIN_SDK=26

# ---- Tool discovery ---------------------------------------------------------------------------
PREFIX_DIR="${PREFIX:-/usr}"
AAPT2="${AAPT2:-aapt2}"

# android.jar used for resource linking (aapt2 -I) and as the compile boot classpath
if [ -z "${ANDROID_JAR:-}" ]; then
    for candidate in "$PREFIX_DIR/share/aapt/android.jar" "${ANDROID_HOME:-/nonexistent}/platforms/android-34/android.jar"; do
        if [ -f "$candidate" ]; then ANDROID_JAR="$candidate"; break; fi
    done
fi
[ -n "${ANDROID_JAR:-}" ] && [ -f "$ANDROID_JAR" ] || fail "android.jar not found. Set ANDROID_JAR=/path/to/android.jar"
# Classes for javac/d8. Defaults to ANDROID_JAR; override when the resource jar has no classes.
BOOTCLASSPATH="${BOOTCLASSPATH:-$ANDROID_JAR}"

if [ -n "${D8_JAR:-}" ]; then
    D8=(java -cp "$D8_JAR" com.android.tools.r8.D8)
else
    D8=(${D8:-d8})
fi

KEYSTORE="${KEYSTORE:-$WORK_DIR/release.keystore}"
KS_ALIAS="${KS_ALIAS:-adbmanager}"
KS_PASS="${KS_PASS:-password}"

LIBS_CP="$(ls "$WORK_DIR"/libs/*.jar | tr '\n' ':')"

echo -e "${BOLD}${CYAN}==================================================================${RESET}"
echo -e "${BOLD}${GREEN}        ADB Application Manager Pro - APK Builder                 ${RESET}"
echo -e "${BOLD}${CYAN}==================================================================${RESET}"

# ---- Step 0: Clean ----------------------------------------------------------------------------
step "Preparing build workspace..."
rm -rf "$WORK_DIR/obj" "$WORK_DIR/gen" "$WORK_DIR/bin"
mkdir -p "$WORK_DIR/obj/classes" "$WORK_DIR/gen" "$WORK_DIR/bin"

# ---- Step 1: Compile resources ----------------------------------------------------------------
step "Compiling resources (aapt2 compile)..."
"$AAPT2" compile --dir "$WORK_DIR/res" -o "$WORK_DIR/obj/res.zip" || fail "aapt2 compile failed"

# ---- Step 2: Link resources, manifest and assets ----------------------------------------------
step "Linking resources and assets (aapt2 link)..."
"$AAPT2" link --manifest "$WORK_DIR/AndroidManifest.xml" \
    -I "$ANDROID_JAR" \
    -A "$WORK_DIR/assets" \
    --java "$WORK_DIR/gen" \
    -o "$WORK_DIR/bin/unsigned.apk" \
    "$WORK_DIR/obj/res.zip" || fail "aapt2 link failed"

# ---- Step 3: Compile Java ---------------------------------------------------------------------
step "Compiling Java sources..."
SOURCES=$(find "$WORK_DIR/src" "$WORK_DIR/gen" -name "*.java")
# Some class jars (e.g. Robolectric android-all) ship without java.* - then use the JDK's Java 8 API
HAS_JAVA_LANG=$(python3 -c "import sys,zipfile; print(int('java/lang/Object.class' in zipfile.ZipFile(sys.argv[1]).namelist()))" "$BOOTCLASSPATH")
if command -v javac >/dev/null 2>&1; then
    if [ "$HAS_JAVA_LANG" = "1" ]; then
        JAVAC_CP=(-source 8 -target 8 -bootclasspath "$BOOTCLASSPATH" -cp "$LIBS_CP")
    else
        JAVAC_CP=(--release 8 -cp "$BOOTCLASSPATH:$LIBS_CP")
    fi
    javac -nowarn -encoding UTF-8 "${JAVAC_CP[@]}" \
        -d "$WORK_DIR/obj/classes" $SOURCES 2>&1 | grep -v "^warning: \[options\]" || true
elif command -v ecj >/dev/null 2>&1; then
    ecj -nowarn -source 1.8 -target 1.8 -encoding UTF-8 \
        -bootclasspath "$BOOTCLASSPATH" -cp "$LIBS_CP" \
        -d "$WORK_DIR/obj/classes" $SOURCES
else
    fail "No Java compiler found (install openjdk-17 or ecj)"
fi
[ -f "$WORK_DIR/obj/classes/com/bloatware/bingblop/MainActivity.class" ] || fail "Java compilation failed"

# ---- Step 4: Dex (app classes + Shizuku API libraries) ----------------------------------------
step "Converting to Dalvik bytecode (d8)..."
CLASS_FILES=$(find "$WORK_DIR/obj/classes" -name "*.class")
"${D8[@]}" --release --min-api "$MIN_SDK" --lib "$BOOTCLASSPATH" \
    --output "$WORK_DIR/bin" $CLASS_FILES "$WORK_DIR"/libs/*.jar || fail "d8 failed"

# ---- Step 5: Package dex + native adb ---------------------------------------------------------
step "Packaging classes.dex and native libraries..."
python3 - "$WORK_DIR" <<'EOF'
import sys, zipfile, os
work = sys.argv[1]
apk = os.path.join(work, 'bin', 'unsigned.apk')
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(work, 'bin', 'classes.dex'), 'classes.dex')
    libadb = os.path.join(work, 'assets', 'libadb.so')
    if os.path.exists(libadb):
        z.write(libadb, 'lib/arm64-v8a/libadb.so')
EOF

# ---- Step 6: Keystore -------------------------------------------------------------------------
if [ ! -f "$KEYSTORE" ]; then
    step "No keystore at $KEYSTORE - generating a new one (uninstall the old app before installing)..."
    keytool -genkeypair -keystore "$KEYSTORE" -keyalg RSA -keysize 2048 -validity 10000 \
        -alias "$KS_ALIAS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=bingblop, OU=adb, O=appmanager, L=local, ST=android, C=US" >/dev/null 2>&1 \
        || fail "Keystore generation failed"
fi

# ---- Step 7: Align + sign ---------------------------------------------------------------------
step "Aligning and signing APK..."
OUT_APK="$WORK_DIR/bin/$APP_NAME.apk"
if [ -n "${UBER_SIGNER_JAR:-}" ]; then
    java -jar "$UBER_SIGNER_JAR" -a "$WORK_DIR/bin/unsigned.apk" -o "$WORK_DIR/bin/signed" \
        --ks "$KEYSTORE" --ksAlias "$KS_ALIAS" --ksPass "$KS_PASS" --ksKeyPass "$KS_PASS" >/dev/null \
        || fail "Signing failed"
    mv "$WORK_DIR"/bin/signed/*.apk "$OUT_APK"
    rm -rf "$WORK_DIR/bin/signed"
else
    zipalign -p -f 4 "$WORK_DIR/bin/unsigned.apk" "$WORK_DIR/bin/aligned.apk" || fail "zipalign failed"
    apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
        --out "$OUT_APK" "$WORK_DIR/bin/aligned.apk" || fail "apksigner failed"
fi

echo
echo -e "${BOLD}${GREEN}✔ Built and signed: $OUT_APK${RESET}"

# Copy to Downloads when running in Termux with storage access
if [ -d /storage/emulated/0/Download ] && [ -w /storage/emulated/0/Download ]; then
    cp "$OUT_APK" "/storage/emulated/0/Download/$APP_NAME.apk"
    echo -e "   📍 Copied to ${BOLD}${CYAN}/storage/emulated/0/Download/$APP_NAME.apk${RESET}"
fi
