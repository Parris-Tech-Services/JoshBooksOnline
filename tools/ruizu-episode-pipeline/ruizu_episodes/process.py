"""The single seam through which the pipeline runs external programs.

curl, ffmpeg, ffprobe and the Termux:API commands all go through Runner, so
tests can substitute behaviour and every call has a bounded timeout.
"""
from __future__ import annotations

import shutil
import subprocess
from dataclasses import dataclass

TIMED_OUT = 124
NOT_FOUND = 127


@dataclass(frozen=True)
class Result:
    returncode: int
    stdout: str
    stderr: str

    @property
    def ok(self) -> bool:
        return self.returncode == 0


class Runner:
    def run(self, cmd: list[str], timeout: float) -> Result:
        try:
            done = subprocess.run(
                cmd, capture_output=True, text=True, errors="replace", timeout=timeout,
                stdin=subprocess.DEVNULL, check=False,
            )
        except subprocess.TimeoutExpired:
            return Result(TIMED_OUT, "", f"{cmd[0]} timed out after {int(timeout)}s")
        except FileNotFoundError:
            return Result(NOT_FOUND, "", f"{cmd[0]} is not installed")
        return Result(done.returncode, done.stdout, done.stderr)

    def available(self, program: str) -> bool:
        return shutil.which(program) is not None
