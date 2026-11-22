#!/data/data/com.termux/files/usr/bin/bash
# One-tap launcher: Android Google Drive picker -> hardware-tested AMV -> RUIZU Sync share.
# Never writes to USB directly or changes any existing player media.
set -Ee -o pipefail
umask 077
say() { printf '%s\n' "$*"; }
fail() { say "RUIZU Movie: $*" >&2; exit 1; }
for tool in ffmpeg ffprobe python stat; do
  command -v "$tool" >/dev/null 2>&1 || fail "Missing $tool (pkg install ffmpeg python coreutils)."
done
outdir="$HOME/storage/downloads"
if [[ -n "$RUIZU_MOVIE_OUTPUT_DIR" ]]; then outdir="$RUIZU_MOVIE_OUTPUT_DIR"; fi
[[ -d "$outdir" && -w "$outdir" ]] || fail "Phone Downloads is not writable. Run termux-setup-storage."
work="$(mktemp -d "$HOME/.ruizu-movie.XXXXXXXX")"
stage="$work/source.movie"
part=""
cleanup() {
  if [[ -n "$part" && -f "$part" ]]; then rm -f -- "$part"; fi
  if [[ -d "$work" ]]; then rm -f -- "$stage"; rmdir "$work" 2>/dev/null || true; fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

if [[ -n "$RUIZU_MOVIE_TEST_SOURCE" ]]; then
  # Test-only source: copy from local fixture without altering its contents.
  cp -- "$RUIZU_MOVIE_TEST_SOURCE" "$stage"
else
  command -v termux-storage-get >/dev/null 2>&1 || \
    fail "Install termux-api and the Termux:API Android app for the Google Drive picker."
  say "Choose one film or episode under Android Files > Google Drive."
  say "It is temporarily copied to private Termux storage."
  # ACTION_OPEN_DOCUMENT returns asynchronously; wait for the copy to stop changing.
  termux-storage-get "$stage" || fail "Could not open the Android file picker."
  ready=0
  for ((elapsed=0; elapsed<1800; elapsed+=3)); do
    if [[ -s "$stage" ]]; then
      now="$(date +%s)"
      modified="$(stat -c %Y "$stage" 2>/dev/null || echo "$now")"
      if ((now-modified>=18)); then ready=1; break; fi
    fi
    sleep 3
  done
  ((ready==1)) || fail "No completed video was received from the Android picker."
fi
codec="$(ffprobe -v error -select_streams v:0 -show_entries stream=codec_type \
  -of csv=p=0 "$stage" 2>/dev/null | head -n 1)" || fail "Source could not be inspected."
[[ "$codec" == "video" ]] || fail "Selected file is not a playable video."

title="$RUIZU_MOVIE_TITLE"
if [[ -z "$title" && -z "$RUIZU_MOVIE_TEST_SOURCE" ]] && command -v termux-dialog >/dev/null 2>&1; then
  say "Name the film or episode (optional)."
  response="$(termux-dialog text -t 'RUIZU movie title' 2>/dev/null || true)"
  title="$(printf '%s' "$response" | python -c 'import json,sys
try: print(json.load(sys.stdin).get("text",""))
except (ValueError,TypeError): print("")' 2>/dev/null || true)"
fi
[[ -n "$title" ]] || title="Movie"
safe="$(printf '%s' "$title" | python -c 'import re,sys
v=sys.stdin.read().strip()
v=re.sub(r"[^A-Za-z0-9 _().-]", "", v).strip(" .")
print((v[:70].strip(" .") or "Movie"))')"
stamp="$(date +%Y%m%d-%H%M%S)-$$"
output="$outdir/$safe - RUIZU $stamp.amv"
part="$outdir/.$safe - RUIZU $stamp.partial.amv"
[[ ! -e "$output" && ! -e "$part" ]] || fail "Destination already exists; refusing overwrite."
say "Converting to tested RUIZU format: 160x128 AMV, 15 fps, 22.05 kHz mono."
say "Original stays untouched in Google Drive."
ffmpeg -hide_banner -nostdin -n -stats -loglevel warning -i "$stage" \
  -map 0:v:0 -map 0:a:0 \
  -vf 'scale=160:128:force_original_aspect_ratio=decrease,pad=160:128:(ow-iw)/2:(oh-ih)/2:black' \
  -r 15 -c:v amv -c:a adpcm_ima_amv -block_size 1470 -ar 22050 -ac 1 \
  -threads 2 -f amv "$part" || fail "Conversion failed; all existing media remains unchanged."

video="$(ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,width,height \
  -of default=noprint_wrappers=1 "$part" 2>/dev/null)" || fail "Cannot verify AMV video."
audio="$(ffprobe -v error -select_streams a:0 -show_entries stream=codec_name,sample_rate,channels \
  -of default=noprint_wrappers=1 "$part" 2>/dev/null)" || fail "Cannot verify AMV audio."
[[ "$video" == *"codec_name=amv"* && "$video" == *"width=160"* && "$video" == *"height=128"* ]] || fail "Wrong video profile."
[[ "$audio" == *"codec_name=adpcm_ima_amv"* && "$audio" == *"sample_rate=22050"* && "$audio" == *"channels=1"* ]] || fail "Wrong audio profile."
[[ -s "$part" && ! -e "$output" ]] || fail "Output empty or already exists."
mv -- "$part" "$output"
part=""
say "AMV verified locally: $output"
say "Choose RUIZU Sync in the share sheet, then confirm USB transfer."
if command -v termux-share >/dev/null 2>&1; then
  termux-share -a send -c video/x-amv -t 'Send to RUIZU Sync' "$output" || \
    say "Sharing failed. AMV is in Downloads; import via RUIZU Sync."
else
  say "termux-share missing. Use RUIZU Sync > Add converted AMV movie."
fi
say "Check the RUIZU Sync SHA-256 transfer log and test physical playback."
