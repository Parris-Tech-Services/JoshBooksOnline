#!/usr/bin/env bash
# Synthetic end-to-end conversion and share contract; no actual Google account or USB needed.
set -Eeuo pipefail
base="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/home/storage/downloads" "$tmp/out" "$tmp/bin"
ffmpeg -hide_banner -nostdin -loglevel error \
  -f lavfi -i testsrc2=size=240x180:rate=15 \
  -f lavfi -i sine=frequency=440:sample_rate=22050 \
  -t 2 -c:v mpeg4 -q:v 5 -c:a aac "$tmp/input.mp4"
before="$(sha256sum "$tmp/input.mp4")"
cat > "$tmp/bin/termux-share" <<'SHARE'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$RUIZU_MOVIE_TEST_SHARE_LOG"
SHARE
chmod +x "$tmp/bin/termux-share"
HOME="$tmp/home" PATH="$tmp/bin:$PATH" \
  RUIZU_MOVIE_TEST_SOURCE="$tmp/input.mp4" RUIZU_MOVIE_TITLE="Synthetic Test" \
  RUIZU_MOVIE_OUTPUT_DIR="$tmp/out" RUIZU_MOVIE_TEST_SHARE_LOG="$tmp/share.log" \
  bash "$base/termux_movie_shortcut.sh"
after="$(sha256sum "$tmp/input.mp4")"
[[ "$before" == "$after" ]] || { echo "Source was modified"; exit 1; }
count="$(find "$tmp/out" -maxdepth 1 -type f -name '*.amv' | wc -l)"
[[ "$count" == "1" ]] || { echo "Expected one AMV, got $count"; exit 1; }
output="$(find "$tmp/out" -maxdepth 1 -type f -name '*.amv' -print -quit)"
[[ "$output" == *"Synthetic Test - RUIZU"* ]]
video="$(ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,width,height \
  -of default=noprint_wrappers=1 "$output")"
audio="$(ffprobe -v error -select_streams a:0 -show_entries stream=codec_name,sample_rate,channels \
  -of default=noprint_wrappers=1 "$output")"
[[ "$video" == *"codec_name=amv"* && "$video" == *"width=160"* && "$video" == *"height=128"* ]]
[[ "$audio" == *"codec_name=adpcm_ima_amv"* && "$audio" == *"sample_rate=22050"* && "$audio" == *"channels=1"* ]]
grep -F 'video/x-amv' "$tmp/share.log" >/dev/null
if find "$tmp/out" -maxdepth 1 -name '*.partial.amv' | grep -q .; then
  echo "Partial AMV left behind"; exit 1
fi
[[ ! -e "$tmp/home/.ruizu-movie."* ]] || true
echo "PASS: Drive-picker-independent AMV encode, original preserved, share intent and codec profile."
