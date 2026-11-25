"""Convert to, and prove, the one AMV profile both RUIZU players are known to play.

Profile (TODO item 28, confirmed by playback on both units and matching the
vendor's own ffmpeg-based converter): AMV video 160x128 at 15 fps, letterboxed;
adpcm_ima_amv audio, 1470-byte blocks, 22.05 kHz mono. Output is written to a
temporary name and only published after the streams, dimensions and frame
coverage of the whole source are verified, so a truncated encode is never staged.
"""
from __future__ import annotations

import hashlib
import json
import os
import struct
from dataclasses import dataclass
from pathlib import Path

from .process import Runner

FPS = 15
MIN_FRAMES = 150
COVERAGE = 0.95
ENCODE_TIMEOUT = 3600
AMV_VIDEO = ("amv", 160, 128)
AMV_AUDIO = ("adpcm_ima_amv", 22050, 1)
ENCODE_ARGS = [
    "-map", "0:v:0", "-map", "0:a:0",
    "-vf", "scale=160:128:force_original_aspect_ratio=decrease,pad=160:128:(ow-iw)/2:(oh-ih)/2:black",
    "-r", str(FPS), "-c:v", "amv", "-c:a", "adpcm_ima_amv", "-block_size", "1470",
    "-ar", "22050", "-ac", "1", "-threads", "4", "-f", "amv",
]


HEADER_SCAN_BYTES = 4096
TRUNCATION_TOLERANCE_FRAMES = FPS  # the header length is whole seconds


@dataclass(frozen=True)
class AmvCheck:
    status: str  # "ok", "truncated", "unverifiable" (no length in the header), "wrong_profile", "unreadable"
    frames: int
    header_seconds: int | None
    detail: str = ""


def header_seconds(path: Path) -> int | None:
    """Length recorded in the AMV header: the last four bytes of the 'amvh' chunk are seconds,
    minutes and hours, written by ffmpeg's muxer when the file is finished. A file cut short
    keeps its header, so this is the length it was meant to have."""
    with open(path, "rb") as handle:
        head = handle.read(HEADER_SCAN_BYTES)
    at = head.find(b"amvh")
    if at < 0 or at + 8 > len(head):
        return None
    size = struct.unpack("<I", head[at + 4 : at + 8])[0]
    end = at + 8 + size
    if size < 4 or end > len(head):
        return None
    seconds, minutes, hours = head[end - 4], head[end - 3], head[end - 2]
    if seconds > 59 or minutes > 59:
        return None
    total = seconds + 60 * minutes + 3600 * hours
    return total or None


class MediaError(RuntimeError):
    def __init__(self, message: str, permanent: bool = False):
        super().__init__(message)
        self.permanent = permanent


def sha256_file(path: Path, drop_cache: bool = False) -> str:
    """Hash a file; with drop_cache, ask the kernel to forget cached pages first so the
    bytes are read back from the medium rather than satisfied from memory."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        if drop_cache:
            _drop_cache(handle.fileno())
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _drop_cache(fd: int) -> None:
    advise = getattr(os, "posix_fadvise", None)
    if advise is not None:
        try:
            advise(fd, 0, 0, os.POSIX_FADV_DONTNEED)
        except OSError:
            pass  # best effort: fsync before this already pushed the data to the medium


class Media:
    def __init__(self, runner: Runner):
        self.runner = runner

    def _probe_json(self, path: Path, *args: str) -> dict:
        result = self.runner.run(["ffprobe", "-v", "error", *args, "-of", "json", str(path)], 120)
        if not result.ok:
            return {}
        try:
            data = json.loads(result.stdout or "{}")
        except json.JSONDecodeError:
            return {}
        return data if isinstance(data, dict) else {}

    def source_duration(self, src: Path) -> float:
        """Duration of a downloaded source, or MediaError when it is not playable video+audio."""
        data = self._probe_json(src, "-show_entries", "format=duration:stream=codec_type")
        kinds = {s.get("codec_type") for s in data.get("streams", []) if isinstance(s, dict)}
        if "video" not in kinds or "audio" not in kinds:
            raise MediaError("download is not a video with sound", permanent=True)
        try:
            return float(data.get("format", {}).get("duration", 0))
        except (TypeError, ValueError):
            return 0.0

    def encode(self, src: Path, out: Path) -> None:
        out.unlink(missing_ok=True)
        result = self.runner.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", str(src), *ENCODE_ARGS, str(out)],
            ENCODE_TIMEOUT,
        )
        if not result.ok:
            out.unlink(missing_ok=True)
            raise MediaError(f"ffmpeg failed: {result.stderr.strip()[-300:]}")

    def inspect_amv(self, path: Path) -> AmvCheck:
        """Read-only completeness check of an AMV without any reference copy."""
        data = self._probe_json(path, "-count_packets", "-show_entries",
                                "stream=codec_type,codec_name,width,height,sample_rate,channels,nb_read_packets")
        streams = [s for s in data.get("streams", []) if isinstance(s, dict)]
        video = [s for s in streams if s.get("codec_type") == "video"]
        audio = [s for s in streams if s.get("codec_type") == "audio"]
        if len(video) != 1 or len(audio) != 1:
            return AmvCheck("unreadable", 0, None, "not a readable video with sound")
        frames = _as_int(video[0].get("nb_read_packets"))
        try:
            _check_profile(video[0], audio[0])
        except MediaError as exc:
            return AmvCheck("wrong_profile", frames, None, str(exc))
        seconds = header_seconds(path)
        if seconds is None:
            return AmvCheck("unverifiable", frames, None, "no length recorded in the header")
        expected = seconds * FPS
        if frames < expected * COVERAGE - TRUNCATION_TOLERANCE_FRAMES:
            return AmvCheck("truncated", frames, seconds, f"{frames} of about {expected} frames")
        return AmvCheck("ok", frames, seconds)

    def verify_amv(self, path: Path, source_seconds: float = 0.0) -> None:
        """Raise MediaError unless `path` is the verified AMV profile covering the whole source."""
        data = self._probe_json(path, "-count_packets", "-show_entries",
                                "stream=codec_type,codec_name,width,height,sample_rate,channels,nb_read_packets")
        streams = [s for s in data.get("streams", []) if isinstance(s, dict)]
        video = [s for s in streams if s.get("codec_type") == "video"]
        audio = [s for s in streams if s.get("codec_type") == "audio"]
        if len(video) != 1 or len(audio) != 1:
            raise MediaError(f"expected one video and one audio stream, found {len(video)} and {len(audio)}")
        _check_profile(video[0], audio[0])
        _check_coverage(_as_int(video[0].get("nb_read_packets")), source_seconds)


def _as_int(value: object) -> int:
    try:
        return int(str(value))
    except ValueError:
        return 0


def _check_profile(video: dict, audio: dict) -> None:
    got_video = (video.get("codec_name"), _as_int(video.get("width")), _as_int(video.get("height")))
    got_audio = (audio.get("codec_name"), _as_int(audio.get("sample_rate")), _as_int(audio.get("channels")))
    if got_video != AMV_VIDEO or got_audio != AMV_AUDIO:
        raise MediaError(f"wrong profile: video={got_video} audio={got_audio}")


def _check_coverage(frames: int, source_seconds: float) -> None:
    if frames < MIN_FRAMES:
        raise MediaError(f"only {frames} frames; too short to be an episode")
    expected = int(source_seconds * FPS)
    if source_seconds > 0 and frames < expected * COVERAGE:
        raise MediaError(f"encode stopped early: {frames} of about {expected} frames")
