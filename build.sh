#!/usr/bin/env bash
# Build and sign FireStick Fixer with the plain aapt2 + javac + d8 pipeline
# (no Gradle). Linux counterpart of build.ps1.
#
# Env overrides:
#   ANDROID_SDK   SDK root           (default ~/Android/Sdk)
#   BUILD_TOOLS   build-tools version (default 34.0.0)
#   D8            dexer (default: the newer one from cmdline-tools; build-tools 34 d8
#                 crashes on classes from recent javac)
#   KEYSTORE      signing keystore    (default ~/.android-signing/firestick-fixer.keystore)
#   KEYSTORE_PROPS  file with a "storePass=..." line (default next to the keystore)
#
# Usage: ./build.sh            build only
#        ./build.sh --install  build, then adb install -r on the connected device
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/${BUILD_TOOLS:-34.0.0}"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
KEYSTORE="${KEYSTORE:-$HOME/.android-signing/firestick-fixer.keystore}"
KEYSTORE_PROPS="${KEYSTORE_PROPS:-${KEYSTORE%.keystore}.properties}"
BUILD="$ROOT/build"
OUT="$ROOT/firestick-fixer.apk"

[[ -f "$KEYSTORE" ]] || { echo "Keystore not found: $KEYSTORE (create one with keytool -genkeypair)"; exit 1; }
STORE_PASS="$(sed -n 's/^[[:space:]]*storePass[[:space:]]*=[[:space:]]*//p' "$KEYSTORE_PROPS")"
[[ -n "$STORE_PASS" ]] || { echo "No storePass line in $KEYSTORE_PROPS"; exit 1; }

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "aapt2 compile..."
"$BT/aapt2" compile --dir "$ROOT/res" -o "$BUILD/res-compiled.zip"
echo "aapt2 link..."
"$BT/aapt2" link -o "$BUILD/unsigned.apk" -I "$ANDROID_JAR" \
    --manifest "$ROOT/AndroidManifest.xml" -R "$BUILD/res-compiled.zip" \
    --java "$BUILD/gen" --auto-add-overlay \
    --min-sdk-version 21 --target-sdk-version 34

echo "javac..."
find "$ROOT/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac -encoding UTF-8 --release 8 -Xlint:-options -nowarn \
    -classpath "$ANDROID_JAR" -d "$BUILD/classes" "@$BUILD/sources.txt"

echo "d8..."
(cd "$BUILD/classes" && jar cf "$BUILD/classes.jar" .)
"${D8:-$SDK/cmdline-tools/latest/bin/d8}" --lib "$ANDROID_JAR" --min-api 21 --output "$BUILD/dex" "$BUILD/classes.jar"

echo "package..."
cp "$BUILD/unsigned.apk" "$BUILD/unsigned-with-dex.apk"
(cd "$BUILD/dex" && jar uf "$BUILD/unsigned-with-dex.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$BUILD/unsigned-with-dex.apk" "$BUILD/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$STORE_PASS" \
    --key-pass "pass:$STORE_PASS" --out "$OUT" "$BUILD/aligned.apk"
rm -f "$OUT.idsig"
echo "APK built: $OUT"

if [[ "${1:-}" == "--install" ]]; then
    adb install -r "$OUT"
fi
