#!/usr/bin/env bash
# Offline-style APK build using the Android build tools in Termux (aapt2, javac, d8/R8,
# zipalign, apksigner). This is the second, independent build path; CI uses Gradle instead.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app/src/main"
SDK="${ANDROID_JAR:-$ROOT/.build/sdk/android-34.jar}"
R8_JAR="${R8_JAR:-$ROOT/.build/r8.jar}"
BUILD="$ROOT/.build/apk"
DIST="$ROOT/dist"
KEYSTORE="$ROOT/.build/keystore/ruizu-debug.jks"
KS_PASS="ruizu123"

MIN_SDK=26
TARGET_SDK=35
VERSION_CODE=9
VERSION_NAME="1.2.5"

[ -f "$SDK" ] || { echo "Missing framework stubs at $SDK (run tools/fetch-sdk.sh)" >&2; exit 1; }
[ -f "$R8_JAR" ] || { echo "Missing R8 at $R8_JAR (run tools/fetch-sdk.sh)" >&2; exit 1; }

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$DIST"

echo "==> 1/7 Compiling resources (aapt2 compile)"
aapt2 compile --dir "$APP/res" -o "$BUILD/res.zip"

echo "==> 1b/7 Preparing manifest (Gradle supplies the namespace; aapt2 needs it inline)"
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="au.com.parristech.ruizusync">|' \
  "$APP/AndroidManifest.xml" > "$BUILD/AndroidManifest.xml"
grep -q 'package="au.com.parristech.ruizusync"' "$BUILD/AndroidManifest.xml" || { echo "Could not inject package into manifest copy" >&2; exit 1; }

echo "==> 2/7 Linking resources and manifest (aapt2 link)"
aapt2 link \
  -o "$BUILD/app-unsigned.apk" \
  -I "$SDK" \
  --manifest "$BUILD/AndroidManifest.xml" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --java "$BUILD/gen" \
  "$BUILD/res.zip"

echo "==> 3/7 Compiling Java (javac)"
find "$APP/java" "$BUILD/gen" -name '*.java' | sort > "$BUILD/sources.txt"
LIB_CP=""
for jar in "$ROOT"/app/libs/*.jar; do
  [ -f "$jar" ] && LIB_CP="$LIB_CP:$jar"
done
javac --release 17 -encoding UTF-8 -classpath "$SDK$LIB_CP" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "==> 4/7 Dexing (official R8/D8)"
find "$BUILD/classes" -name '*.class' | sort > "$BUILD/classes.txt"
DEX_INPUTS=$(cat "$BUILD/classes.txt")
for jar in "$ROOT"/app/libs/*.jar; do
  [ -f "$jar" ] && DEX_INPUTS="$DEX_INPUTS $jar"
done
java -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api "$MIN_SDK" --lib "$SDK" \
  --output "$BUILD/dex" $DEX_INPUTS

echo "==> 5/7 Packaging classes.dex"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-with-dex.apk"
( cd "$BUILD/dex" && zip -q -X "$BUILD/app-with-dex.apk" classes.dex )

echo "==> 6/7 Aligning (zipalign)"
zipalign -f -p 4 "$BUILD/app-with-dex.apk" "$BUILD/app-aligned.apk"

echo "==> 7/7 Signing (apksigner)"
if [ ! -f "$KEYSTORE" ]; then
  echo "    creating debug keystore"
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias ruizu -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=RUIZU Sync Debug, O=RUIZU Sync, C=GB" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias ruizu --out "$DIST/RUIZU-Sync-$VERSION_NAME-debug.apk" "$BUILD/app-aligned.apk"

echo
echo "APK written to: $DIST/RUIZU-Sync-$VERSION_NAME-debug.apk"
ls -l "$DIST"
