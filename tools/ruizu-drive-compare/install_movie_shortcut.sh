#!/data/data/com.termux/files/usr/bin/bash
# Installs a launcher script; user adds the Termux:Widget widget to Android Home.
set -Eeuo pipefail
umask 077
command -v curl >/dev/null || { echo 'Missing curl: pkg install curl'; exit 1; }
mkdir -p "$HOME/.shortcuts"
target="$HOME/.shortcuts/RUIZU Movie"
if [[ -e "$target" ]]; then
  echo "Existing shortcut not replaced: $target"
  exit 1
fi
tmp="$(mktemp "$HOME/.shortcuts/.ruizu-movie-download.XXXXXX")"
trap 'rm -f "$tmp"' EXIT
curl -fLsS https://raw.githubusercontent.com/Parris-Tech-Services/JoshBooksOnline/main/tools/ruizu-drive-compare/termux_movie_shortcut.sh -o "$tmp"
bash -n "$tmp"
chmod 700 "$tmp"
mv -- "$tmp" "$target"
echo "Installed: $target"
echo 'Add a Termux:Widget launcher widget on your Android Home screen, then tap RUIZU Movie.'
echo 'Requires Termux:API + Termux:Widget Android add-ons, and pkg install ffmpeg python termux-api.'
echo 'First run opens Android Files (choose Google Drive). After conversion choose RUIZU Sync in the share sheet.'
echo 'The RUIZU Sync app must already have permission to access the root of the USB player.'
