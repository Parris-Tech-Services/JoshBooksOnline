#!/usr/bin/env bash
# Build LGPL-only upstream FFmpeg as two PIE executables per Android ABI.
# The app runs them from nativeLibraryDir; no external app or runtime download.
set -euo pipefail
NATIVE="$(realpath -m "$1")"
ASSETS="$(realpath -m "$2")"
CACHE="$(realpath -m "$3")"
VERSION=7.1.5
KEY=FCF986EA15E6E293A5644F10B4322F04D67658D8
NDK_VERSION=27.2.12479018
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK" ]]; then echo "Android SDK required"; exit 1; fi
if [[ ! -d "$SDK/ndk/$NDK_VERSION" ]]; then
    SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
    if [[ ! -x "$SDKMANAGER" ]]; then SDKMANAGER="$(command -v sdkmanager)"; fi
    "$SDKMANAGER" --install "ndk;$NDK_VERSION"
fi
TOOLCHAIN="$SDK/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64"
test -x "$TOOLCHAIN/bin/aarch64-linux-android26-clang"
mkdir -p "$CACHE" "$NATIVE" "$ASSETS/ffmpeg"
cd "$CACHE"
ARCHIVE="ffmpeg-$VERSION.tar.xz"
curl --fail --location --retry 3 --proto '=https' -o "$ARCHIVE" "https://ffmpeg.org/releases/$ARCHIVE"
curl --fail --location --retry 3 --proto '=https' -o "$ARCHIVE.asc" "https://ffmpeg.org/releases/$ARCHIVE.asc"
curl --fail --location --retry 3 --proto '=https' -o release-key.asc https://ffmpeg.org/ffmpeg-devel.asc
export GNUPGHOME
GNUPGHOME="$(mktemp -d)"
trap 'rm -rf "$GNUPGHOME"' EXIT
gpg --batch --import release-key.asc
ACTUAL="$(gpg --batch --with-colons --fingerprint "$KEY" | awk -F: '$1=="fpr" {print $10; exit}')"
test "$ACTUAL" = "$KEY"
gpg --batch --status-fd 1 --verify "$ARCHIVE.asc" "$ARCHIVE" > verify.log
grep -E "^\[GNUPG:\] VALIDSIG .*($KEY)( |$)" verify.log
rm -rf "ffmpeg-$VERSION"
tar -xf "$ARCHIVE"
SRC="$CACHE/ffmpeg-$VERSION"
for ABI in arm64-v8a x86_64; do
    case "$ABI" in
        arm64-v8a) ARCH=aarch64; TRIPLE=aarch64-linux-android ;;
        x86_64) ARCH=x86_64; TRIPLE=x86_64-linux-android ;;
    esac
    BUILD="$CACHE/build-$ABI"
    mkdir -p "$BUILD" "$NATIVE/$ABI"
    cd "$BUILD"
    "$SRC/configure" --target-os=android --arch="$ARCH" --enable-cross-compile \
      --cc="$TOOLCHAIN/bin/${TRIPLE}26-clang" --cxx="$TOOLCHAIN/bin/${TRIPLE}26-clang++" \
      --ld="$TOOLCHAIN/bin/${TRIPLE}26-clang" --ar="$TOOLCHAIN/bin/llvm-ar" \
      --nm="$TOOLCHAIN/bin/llvm-nm" --ranlib="$TOOLCHAIN/bin/llvm-ranlib" --strip="$TOOLCHAIN/bin/llvm-strip" \
      --sysroot="$TOOLCHAIN/sysroot" --enable-static --disable-shared --enable-pic \
      --extra-cflags="-fPIE" --extra-ldflags="-pie -Wl,-z,max-page-size=16384" \
      --disable-autodetect --disable-network --disable-gpl --disable-nonfree \
      --disable-doc --disable-debug --disable-ffplay --disable-avdevice --disable-x86asm \
      --disable-encoders --enable-encoder=amv,adpcm_ima_amv,wrapped_avframe,pcm_s16le \
      --disable-muxers --enable-muxer=amv,null \
      --disable-protocols --enable-protocol=file,pipe
    make -j"$(nproc)" ffmpeg ffprobe
    for PROGRAM in ffmpeg ffprobe; do
        install -m 755 "$PROGRAM" "$NATIVE/$ABI/libruizu_${PROGRAM}.so"
        "$TOOLCHAIN/bin/llvm-readelf" -h "$NATIVE/$ABI/libruizu_${PROGRAM}.so" | grep 'DYN'
    done
    cd "$CACHE"
done
# Exact unmodified corresponding source and license are distributed inside the APK.
cp "$ARCHIVE" "$ARCHIVE.asc" "$ASSETS/ffmpeg/"
cp "$SRC/COPYING.LGPLv2.1" "$ASSETS/ffmpeg/"
cp "$SRC/LICENSE.md" "$ASSETS/ffmpeg/"
cp "$(dirname "$(realpath "$0")")/build-episode-codec.sh" "$ASSETS/ffmpeg/build-episode-codec.sh"
