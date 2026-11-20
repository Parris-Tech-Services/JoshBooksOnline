#!/usr/bin/env bash
# Static verification of a built APK: signature, alignment, manifest, dex payload.
# Outputs are captured to files before grepping, so `set -o pipefail` cannot turn an early
# `grep -q` exit (SIGPIPE) into a false failure.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT/dist/RUIZU-Sync-1.0.0-debug.apk}"
WORK="$ROOT/.build/verify"
FAILURES=0

report() { echo; echo "== $1 =="; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }
ok() { echo "ok: $1"; }

[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }

rm -rf "$WORK"
mkdir -p "$WORK"

report "file"
ls -l "$APK"
sha256sum "$APK"

unzip -l "$APK" > "$WORK/zip.txt" 2>&1
report "zip contents"
sed -n '1,40p' "$WORK/zip.txt"
grep -q 'classes.dex' "$WORK/zip.txt" && ok "classes.dex present" || fail "classes.dex missing"
grep -q 'resources.arsc' "$WORK/zip.txt" && ok "resources.arsc present" || fail "resources.arsc missing"

report "dex header"
unzip -p "$APK" classes.dex > "$WORK/classes.dex"
head -c 4 "$WORK/classes.dex" | grep -q 'dex' && ok "dex magic valid" || fail "bad dex magic"
strings "$WORK/classes.dex" > "$WORK/dex-strings.txt"
for cls in ui/MainActivity TransferService TransferManager net/RemoteAudio net/FeedScanner \
           core/DuplicateMatcher core/FolderPlanner core/MediaTypes core/FolderPlanner\$OrganiseMode; do
  grep -qF "au/com/parristech/ruizusync/$cls" "$WORK/dex-strings.txt" \
    && ok "$cls in dex" || fail "$cls missing from dex"
done

report "zipalign"
zipalign -c -v 4 "$APK" > "$WORK/zipalign.txt" 2>&1 && ok "4-byte aligned" || fail "not 4-byte aligned"

report "apksigner verify"
apksigner verify --verbose --print-certs "$APK" > "$WORK/apksigner.txt" 2>&1 \
  && ok "signature verifies" || fail "apksigner verification failed"
grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' "$WORK/apksigner.txt" \
  && ok "v2 signature scheme" || fail "v2 signature missing"
# v3 depends on the signing tool/keystore; v2 is sufficient for install on Android 8+.
if grep -q 'Verified using v3 scheme (APK Signature Scheme v3): true' "$WORK/apksigner.txt"; then
  ok "v3 signature scheme"
else
  echo "note: v3 signature absent (fine for debug builds)"
fi

report "badging"
aapt2 dump badging "$APK" > "$WORK/badging.txt" 2>&1
grep -E "^(package:|sdkVersion|targetSdkVersion|application-label|launchable-activity|uses-permission)" "$WORK/badging.txt" || fail "badging failed"
grep -qF "package: name='au.com.parristech.ruizusync'" "$WORK/badging.txt" \
  && ok "package name correct" || fail "package name wrong"
grep -qF "sdkVersion:'26'" "$WORK/badging.txt" && ok "minSdk 26" || fail "unexpected minSdk"
grep -qF "targetSdkVersion:'34'" "$WORK/badging.txt" && ok "targetSdk 34" || fail "unexpected targetSdk"

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

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "ALL CHECKS PASSED"
else
  echo "$FAILURES CHECK(S) FAILED"
  exit 1
fi
