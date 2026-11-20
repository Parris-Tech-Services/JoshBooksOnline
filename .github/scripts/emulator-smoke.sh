#!/usr/bin/env bash
# Emulator smoke test for RUIZU Sync. Runs on the emulator started by
# reactivecircus/android-emulator-runner. Asserts install, launch, rendered UI and no crash.
set -euo pipefail

APK="${1:-apk/app-debug.apk}"
PKG="au.com.parristech.ruizusync"
ACTIVITY="$PKG/.ui.MainActivity"

echo "== install =="
adb install -r "$APK"

echo "== grant notification permission (avoids a dialog covering the UI) =="
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true

echo "== launch =="
adb shell am start -W -n "$ACTIVITY"
sleep 6

echo "== process =="
adb shell pidof "$PKG"

echo "== focus (informational) =="
adb shell dumpsys window | grep -m1 -E 'mCurrentFocus|mFocusedWindow' || true

dump_ui() {
    local target="$1"
    adb shell uiautomator dump "/sdcard/$target" >/dev/null 2>&1 || true
    adb pull "/sdcard/$target" "$target" >/dev/null 2>&1 || true
}

echo "== ui dump =="
dump_ui window.xml
if [ ! -f window.xml ]; then
    echo "FAIL: no UI hierarchy dump captured"
    exit 1
fi
for needle in "RUIZU Sync" "No files selected" "Choose player folder"; do
    if grep -qF "text=\"$needle\"" window.xml; then
        echo "ui shows: $needle"
    else
        echo "FAIL: missing UI text: $needle"
        exit 1
    fi
done

echo "== scroll to the transfer controls =="
adb shell input swipe 540 1500 540 500 250 || true
sleep 1
dump_ui window-scrolled.xml
if [ -f window-scrolled.xml ] && grep -qF 'text="Start transfer"' window-scrolled.xml; then
    echo "ui shows: Start transfer"
else
    echo "note: Start transfer not captured after scroll (screen size dependent)"
fi

echo "== screenshot =="
adb exec-out screencap -p > emulator-launch.png
[ -s emulator-launch.png ] || { echo "FAIL: screenshot empty"; exit 1; }

echo "== crash buffer =="
if adb logcat -d -b crash | grep -q "$PKG"; then
    echo "FAIL: crash buffer mentions the app"
    adb logcat -d -b crash
    exit 1
fi
echo "emulator launch OK"
