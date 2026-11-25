"""Turn Drive file and folder names into episode identity and stable labels.

Labels are the filenames used in the staging folder and on the player
("How I Met Your Mother S02E11" -> "How I Met Your Mother S02E11 - RUIZU.amv"),
matching what the earlier scripts produced so existing files are recognised.
Drive names are untrusted: everything that becomes a filename is sanitised.
"""
from __future__ import annotations

import html
import re
from dataclasses import dataclass

VIDEO_EXT = re.compile(r"\.(avi|mp4|mkv|mov|m4v|webm|mpg|mpeg)$", re.I)
_SXXEYY = re.compile(r"(?:^|[^A-Za-z0-9])[Ss](\d{1,2})[ ._-]?[Ee](\d{1,3})(?:[^0-9]|$)")
_NXNN = re.compile(r"(?:^|[^0-9])(\d{1,2})x(\d{1,3})(?:[^0-9]|$)")
_SEASON_FOLDER = re.compile(r"season\s*(\d{1,2})", re.I)
_SEASON_TOKEN = re.compile(r"(?:^|[^A-Za-z0-9])(?:[Ss](\d{1,2})|(\d{1,2})x[Ee][Xx])(?:[^0-9]|$)")
_NOISE = re.compile(
    r"\b(\d{1,2}x[Ee][Xx]|[Ss]\d{1,2}|x26[45]|h\.?26[45]|hevc|xvid|divx|\d{3,4}p|"
    r"web-?dl|webrip|hdtv|bluray|brrip|dvdrip|aac|ac3|dd5\.1)\b",
    re.I,
)
_UNSAFE = re.compile(r'[\x00-\x1f\x7f/\\:*?"<>|]+')
MAX_LABEL = 120
PLAYER_SUFFIX = " - RUIZU.amv"


@dataclass(frozen=True)
class EpisodeIdentity:
    kind: str  # "episode" when a season and episode number were found, else "extra"
    season: int | None
    episode: int | None
    label: str


def clean_text(raw: str, limit: int = MAX_LABEL) -> str:
    """Make an untrusted name safe to use as part of a FAT filename."""
    text = html.unescape(re.sub(r"<[^>]+>", "", raw))
    text = _UNSAFE.sub(" ", text)
    text = re.sub(r"\s+", " ", text).strip(" .")
    return text[:limit].rstrip(" .")


def episode_numbers(filename: str) -> tuple[int, int] | None:
    match = _SXXEYY.search(filename) or _NXNN.search(filename)
    return (int(match.group(1)), int(match.group(2))) if match else None


def season_hint(path: str) -> int | None:
    """Season for an un-numbered extra, from a 'Season N' folder or an S05/6xEX token."""
    *folders, filename = path.split("/")
    for folder in reversed(folders):
        found = _SEASON_FOLDER.search(folder)
        if found:
            return int(found.group(1))
    token = _SEASON_TOKEN.search(filename)
    if token:
        return int(token.group(1) or token.group(2))
    return None


def extra_title(show: str, filename: str) -> str:
    stem = VIDEO_EXT.sub("", filename)
    words = re.sub(r"[._]+", " ", stem)
    words = re.sub(r"^\s*" + re.escape(show) + r"\b", "", words, flags=re.I)
    words = _NOISE.sub(" ", words)
    return clean_text(words.strip(" -")) or "Extra"


def identify(show: str, path: str) -> EpisodeIdentity:
    """Episode identity for a video at `path` (relative to the show folder)."""
    show_name = clean_text(show)
    filename = path.rsplit("/", 1)[-1]
    numbers = episode_numbers(filename)
    if numbers:
        season, episode = numbers
        return EpisodeIdentity("episode", season, episode, f"{show_name} S{season:02d}E{episode:02d}")
    hint = season_hint(path)
    prefix = f"{show_name} S{hint:02d}" if hint is not None else show_name
    return EpisodeIdentity("extra", hint, None, clean_text(f"{prefix} - {extra_title(show, filename)}"))


def identify_label(label: str) -> tuple[str, EpisodeIdentity]:
    """Recover show and identity from a label the old scripts wrote ("Buffy S05 - Buffy Abroad")."""
    clean = clean_text(label)
    numbered = re.match(r"^(.*?) S(\d{1,2})E(\d{1,3})$", clean)
    if numbered:
        show, season, episode = numbered.group(1), int(numbered.group(2)), int(numbered.group(3))
        return show, EpisodeIdentity("episode", season, episode, clean)
    extra = re.match(r"^(.*?) S(\d{1,2}) - .+$", clean)
    if extra:
        return extra.group(1), EpisodeIdentity("extra", int(extra.group(2)), None, clean)
    show = clean.split(" - ", 1)[0]
    return show, EpisodeIdentity("extra", None, None, clean)


def player_filename(label: str) -> str:
    return label + PLAYER_SUFFIX
