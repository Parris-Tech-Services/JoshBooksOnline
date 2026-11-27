"""Where the pipeline keeps things and which endpoints it talks to.

These are machine facts, not user policy (policy lives in config.py). Paths
default to the directories the earlier hand-run scripts already use, so the
encoded episodes already sitting on the phone are reused rather than rebuilt.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

LIST_URL = "https://drive.google.com/embeddedfolderview?id={id}"
DOWNLOAD_URL = "https://drive.usercontent.google.com/download?id={id}&export=download&confirm=t"
DEFAULT_PLAYER_ROOT = "/mnt/media_rw/0000-006F"
# Drive serves the lightweight embedded folder view to a desktop browser; the
# mobile user agent gets a string-table page the parser cannot read.
DESKTOP_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


@dataclass(frozen=True)
class Settings:
    state_dir: Path
    stage_dir: Path
    investigate_dir: Path
    player_roots: tuple[Path, ...]
    list_url: str = LIST_URL
    download_url: str = DOWNLOAD_URL
    user_agent: str = DESKTOP_UA

    @property
    def db_path(self) -> Path:
        return self.state_dir / "pipeline.db"

    @property
    def config_path(self) -> Path:
        return self.state_dir / "pipeline.json"

    @property
    def lock_path(self) -> Path:
        return self.state_dir / "pipeline.lock"

    @property
    def work_dir(self) -> Path:
        return self.stage_dir / ".work"

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> Settings:
        env = dict(os.environ if env is None else env)
        home = Path(env.get("HOME", str(Path.home())))
        roots = env.get("RUIZU_PLAYER_ROOTS", DEFAULT_PLAYER_ROOT)
        return cls(
            state_dir=Path(env.get("RUIZU_STATE_DIR", home / ".ruizu-player")),
            stage_dir=Path(env.get("RUIZU_STAGE_DIR", home / "ruizu-episodes")),
            investigate_dir=Path(env.get("RUIZU_INVESTIGATE_DIR", home / ".ruizu-investigate")),
            player_roots=tuple(Path(p) for p in roots.split(os.pathsep) if p),
            list_url=env.get("RUIZU_LIST_URL", LIST_URL),
            download_url=env.get("RUIZU_DOWNLOAD_URL", DOWNLOAD_URL),
        )
