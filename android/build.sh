#!/usr/bin/env bash
#
# Build de l'APK MatrixStegoShare sans Gradle, uniquement avec les outils du
# SDK Android (aapt2, d8, zipalign, apksigner). Genere build/MatrixStegoShare.apk.
#
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
BT_VER="${BUILD_TOOLS_VERSION:-34.0.0}"
BT="$SDK/build-tools/$BT_VER"
PLATFORM="$SDK/platforms/android-34/android.jar"

AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

KS="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="android"
KS_ALIAS="androiddebugkey"
KS_KEYPASS="android"

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

OUT="$HERE/build"
APK_NAME="MatrixStegoShare.apk"

for tool in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER"; do
  if [ ! -x "$tool" ]; then
    echo "ERROR: missing tool: $tool" >&2
    exit 1
  fi
done
if [ ! -f "$PLATFORM" ]; then
  echo "ERROR: missing platform: $PLATFORM" >&2
  exit 1
fi
if [ ! -f "$KS" ]; then
  echo "Keystore not found, creating a debug keystore at $KS"
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -v -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_KEYPASS" \
    -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi

echo ">> Cleaning"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo ">> Compiling resources (aapt2)"
"$AAPT2" compile --dir res -o "$OUT/res.zip"

echo ">> Linking resources + manifest (aapt2)"
"$AAPT2" link \
  -o "$OUT/app-unsigned.apk" \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  --java "$OUT/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code 1 \
  --version-name 1.0 \
  "$OUT/res.zip"

echo ">> Compiling Java (javac)"
javac -source 8 -target 8 -Xlint:-options \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')

echo ">> Dexing (d8)"
"$D8" --lib "$PLATFORM" --min-api 24 --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo ">> Packaging dex into APK"
cp "$OUT/app-unsigned.apk" "$OUT/app.apk"
( cd "$OUT/dex" && zip -q -u "$OUT/app.apk" classes.dex )

echo ">> Aligning (zipalign)"
"$ZIPALIGN" -f -p 4 "$OUT/app.apk" "$OUT/app-aligned.apk"

echo ">> Signing (apksigner)"
"$APKSIGNER" sign \
  --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_KEYPASS" \
  --ks-key-alias "$KS_ALIAS" \
  --out "$OUT/$APK_NAME" \
  "$OUT/app-aligned.apk"

echo ">> Verifying signature"
"$APKSIGNER" verify --print-certs "$OUT/$APK_NAME" | head -4

echo
echo "APK ready: $OUT/$APK_NAME"
