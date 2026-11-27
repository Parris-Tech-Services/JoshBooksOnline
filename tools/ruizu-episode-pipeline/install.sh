#!/data/data/com.termux/files/usr/bin/bash
# Install or update ruizu-episodes on the phone from this repository checkout.
# Safe to re-run: it replaces only its own program files and its own widget
# shortcuts. Your state (~/.ruizu-player) and staged episodes are not touched.
set -Eeuo pipefail
umask 077

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
state="${RUIZU_STATE_DIR:-$HOME/.ruizu-player}"
app="$state/app"
bin_dir="${PREFIX:-/data/data/com.termux/files/usr}/bin"
marker="# managed by ruizu-episodes install.sh"

for tool in python ffmpeg ffprobe curl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing $tool. Run: pkg install python ffmpeg curl" >&2; exit 1; }
done
python -c 'import sys; sys.exit(sys.version_info < (3, 11))' || { echo "Python 3.11 or newer is required" >&2; exit 1; }
command -v termux-wifi-connectioninfo >/dev/null 2>&1 || \
  echo "Note: pkg install termux-api (and the Termux:API app) lets it confirm Wi-Fi and schedule runs."

# Stage the new copy beside the old one, check it compiles, then swap it in.
mkdir -p "$app"
staging="$(mktemp -d "$app/.install.XXXXXX")"
trap 'rm -rf -- "$staging"' EXIT
cp -R -- "$here/ruizu_episodes" "$staging/ruizu_episodes"
python -m compileall -q "$staging/ruizu_episodes"
if [[ -d "$app/ruizu_episodes" ]]; then
  rm -rf -- "$app/ruizu_episodes.previous"
  mv -- "$app/ruizu_episodes" "$app/ruizu_episodes.previous"
fi
mv -- "$staging/ruizu_episodes" "$app/ruizu_episodes"

cat > "$bin_dir/ruizu-episodes" <<EOF
#!/data/data/com.termux/files/usr/bin/sh
$marker
PYTHONPATH="$app" exec python -m ruizu_episodes "\$@"
EOF
chmod 700 "$bin_dir/ruizu-episodes"

# Termux:Widget shortcuts. A file with the same name that we did not write is left alone.
write_shortcut() {
  local path="$HOME/.shortcuts/$1" body="$2"
  if [[ -e "$path" ]] && ! grep -qF "$marker" "$path"; then
    echo "Left your existing shortcut alone: $path"
    return
  fi
  printf '#!/data/data/com.termux/files/usr/bin/bash\n%s\n%s\n' "$marker" "$body" > "$path"
  chmod 700 "$path"
}
mkdir -p "$HOME/.shortcuts"
write_shortcut "RUIZU Episodes - Sync now" \
  'ruizu-episodes run --minutes 8 --notify; echo; ruizu-episodes status; read -r -p "Done. Press Enter to close." _'
write_shortcut "RUIZU Episodes - Status" \
  'ruizu-episodes status; read -r -p "Press Enter to close." _'

echo "Installed ruizu-episodes $(PYTHONPATH="$app" python -c 'import ruizu_episodes as r; print(r.__version__)')"
echo "First time: ruizu-episodes import-legacy, then plug in a player and run: ruizu-episodes claim \"<name>\""
