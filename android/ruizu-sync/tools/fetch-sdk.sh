#!/usr/bin/env bash
# Downloads the Android API 34 framework stubs (classes + resources) and a current official R8/D8
# build. The R8 bundled with Termux (3.3.20) crashes on enum classes, so this project uses the
# official Google Maven build instead. Safe to re-run; skips whatever is already present.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK_DIR="$ROOT/.build/sdk"
SDK="$SDK_DIR/android-34.jar"
SDK_URL="https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar"
R8="$ROOT/.build/r8.jar"
R8_VERSION="${R8_VERSION:-9.5.33}"
R8_URL="https://maven.google.com/com/android/tools/r8/${R8_VERSION}/r8-${R8_VERSION}.jar"

mkdir -p "$SDK_DIR"

if [ -s "$SDK" ] && unzip -l "$SDK" >/dev/null 2>&1; then
  echo "Framework stubs already present: $SDK"
else
  echo "Downloading API 34 framework stubs (~26 MB)..."
  curl -fSL --retry 5 --retry-delay 3 -o "$SDK" "$SDK_URL"
  unzip -l "$SDK" >/dev/null 2>&1 || { echo "Downloaded file is not a valid jar" >&2; exit 1; }
  echo "Saved to $SDK"
fi

if [ -s "$R8" ] && unzip -l "$R8" >/dev/null 2>&1; then
  echo "R8/D8 already present: $R8"
else
  echo "Downloading official R8/D8 ${R8_VERSION} (~21 MB)..."
  curl -fSL --retry 5 --retry-delay 3 -o "$R8" "$R8_URL"
  unzip -l "$R8" >/dev/null 2>&1 || { echo "Downloaded R8 jar is not valid" >&2; exit 1; }
  echo "Saved to $R8"
fi
