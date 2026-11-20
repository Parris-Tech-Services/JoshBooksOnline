#!/usr/bin/env bash
# Static verification of a built APK: signature, alignment, manifest, dex payload.
# Works with single- and multi-dex APKs (minSdk 21+ native multidex).
# Outputs are captured to files before grepping, so `set -o pipefail` cannot turn an early
# `grep -q` exit (SIGPIPE) into a false failure.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT/dist/RUIZU-Sync-1.2.0-debug.apk}"
WORK="$ROOT/.build/verify"
FAILURES=0

report() { echo; echo "== $1 =="; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }
ok() { echo "ok: $1"; }

[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }

rm -rf "$WORK"
mkdir -p "$WORK/dex"

report "file"
ls -l "$APK"
sha256sum "$APK"

# ---------------------------------------------------------------------------------------
report "zip contents"
unzip -l "$APK" > "$WORK/zip.txt" 2>&1
sed -n '1,40p' "$WORK/zip.txt"
grep -q 'classes.dex' "$WORK/zip.txt" && ok "classes.dex present" || fail "classes.dex missing"
grep -q 'resources.arsc' "$WORK/zip.txt" && ok "resources.arsc present" || fail "resources.arsc missing"

# ---------------------------------------------------------------------------------------
report "dex payload"
unzip -o -q "$APK" 'classes*.dex' -d "$WORK/dex" 2>/dev/null || true
DEX_COUNT=$(find "$WORK/dex" -name 'classes*.dex' | wc -l | tr -d ' ')
[ "$DEX_COUNT" -ge 1 ] || fail "no dex files found"
echo "dex files: $DEX_COUNT"
for dex in "$WORK"/dex/classes*.dex; do
  [ -f "$dex" ] || continue
  head -c 4 "$dex" | grep -q 'dex' || fail "bad dex magic in $(basename "$dex")"
done
ok "dex magic valid"
for cls in ui/MainActivity TransferService TransferManager net/RemoteAudio net/FeedScanner \
           core/DuplicateMatcher core/FolderPlanner core/MediaTypes 'core/FolderPlanner$OrganiseMode' \
           audio/Mp3Encoder audio/AudioTranscoder core/Opml core/ConversionPolicy; do
  found=no
  for dex in "$WORK"/dex/classes*.dex; do
    if [ -f "$dex" ] && grep -a -qF "Lau/com/parristech/ruizusync/$cls;" "$dex"; then
      found=yes
      break
    fi
  done
  [ "$found" = yes ] && ok "$cls in dex" || fail "$cls missing from dex"
done
if [ "$FAILURES" -gt 0 ]; then
  echo "-- dex diagnostics --"
  for dex in "$WORK"/dex/classes*.dex; do
    [ -f "$dex" ] || continue
    echo "$(basename "$dex"):"
    grep -a -o 'Lau/com/parristech/ruizusync/[A-Za-z0-9_$/]*;' "$dex" | sort -u | head -5 || true
  done
fi

# ---------------------------------------------------------------------------------------
report "zipalign"
zipalign -c -v 4 "$APK" > "$WORK/zipalign.txt" 2>&1 && ok "4-byte aligned" || fail "not 4-byte aligned"

# ---------------------------------------------------------------------------------------
report "apksigner verify"
apksigner verify --verbose --print-certs "$APK" > "$WORK/apksigner.txt" 2>&1 \
  && ok "signature verifies" || fail "apksigner verification failed"
grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' "$WORK/apksigner.txt" \
  && ok "v2 signature scheme" || fail "v2 signature missing"
# v3 depends on the signing tool/keystore; v2 is sufficient to install on Android 8+.
if grep -q 'Verified using v3 scheme (APK Signature Scheme v3): true' "$WORK/apksigner.txt"; then
  ok "v3 signature scheme"
else
  echo "note: v3 signature absent (fine for debug builds)"
fi

# ---------------------------------------------------------------------------------------
report "badging"
aapt2 dump badging "$APK" > "$WORK/badging.txt" 2>&1
grep -E "^(package:|sdkVersion|minSdkVersion|targetSdkVersion|application-label|launchable-activity|uses-permission)" \
  "$WORK/badging.txt" || fail "badging failed"
grep -qF "package: name='au.com.parristech.ruizusync.fresh'" "$WORK/badging.txt" \
  && ok "package name correct" || fail "package name wrong"

# ---------------------------------------------------------------------------------------
report "manifest checks"
aapt2 dump xmltree --file AndroidManifest.xml "$APK" > "$WORK/manifest.txt" 2>&1 \
  || fail "cannot dump manifest xmltree"
grep -q 'android:exported' "$WORK/manifest.txt" && ok "exported attribute present" || fail "exported attribute missing"
grep -q 'foregroundServiceType' "$WORK/manifest.txt" && ok "foregroundServiceType present" || fail "foregroundServiceType missing"
grep -q 'FOREGROUND_SERVICE_DATA_SYNC' "$WORK/manifest.txt" && ok "FOREGROUND_SERVICE_DATA_SYNC permission" || fail "data-sync FGS permission missing"
grep -q 'POST_NOTIFICATIONS' "$WORK/manifest.txt" && ok "POST_NOTIFICATIONS permission" || fail "POST_NOTIFICATIONS missing"
grep -q 'WAKE_LOCK' "$WORK/manifest.txt" && ok "WAKE_LOCK permission" || fail "WAKE_LOCK missing"
grep -q 'INTERNET' "$WORK/manifest.txt" && ok "INTERNET permission" || fail "INTERNET missing"
grep -q 'TransferService' "$WORK/manifest.txt" && ok "TransferService declared" || fail "TransferService missing"
grep -q 'ui.MainActivity' "$WORK/manifest.txt" && ok "MainActivity declared" || fail "MainActivity missing"

# minSdk: aapt2 35+ labels it minSdkVersion:'26', older tools sdkVersion:'26'; also accept
# the manifest xmltree form (some builds omit it from badging entirely).
if grep -qF "sdkVersion:'26'" "$WORK/badging.txt" \
   || grep -qF "minSdkVersion:'26'" "$WORK/badging.txt" \
   || grep -qE 'android:minSdkVersion\(0x[0-9a-f]+\)=(26|\(type 0x10\)0x1a)' "$WORK/manifest.txt"; then
  ok "minSdk 26"
else
  fail "unexpected minSdk"
  grep -i 'sdkversion' "$WORK/badging.txt" || true
fi
grep -qF "targetSdkVersion:'35'" "$WORK/badging.txt" && ok "targetSdk 35" || fail "unexpected targetSdk"

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "ALL CHECKS PASSED"
else
  echo "$FAILURES CHECK(S) FAILED"
  exit 1
fi
