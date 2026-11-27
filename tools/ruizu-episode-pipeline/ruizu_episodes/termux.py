"""Small Termux:API touch points: is the phone on Wi-Fi, notifications, and the job scheduler.

Each is optional. When a command is missing the caller is told plainly rather
than assuming the answer (for example, "can't confirm Wi-Fi" is not "on Wi-Fi").
"""
from __future__ import annotations

import json
from pathlib import Path

from .process import Runner

JOB_ID = "4127"
NOTIFICATION_ID = "ruizu-episodes"


def wifi_connected(runner: Runner) -> bool | None:
    """True/False from termux-wifi-connectioninfo, or None when it cannot be determined."""
    if not runner.available("termux-wifi-connectioninfo"):
        return None
    result = runner.run(["termux-wifi-connectioninfo"], 20)
    if not result.ok:
        return None
    try:
        info = json.loads(result.stdout)
    except json.JSONDecodeError:
        return None
    if not isinstance(info, dict):
        return None
    return info.get("supplicant_state") == "COMPLETED" and info.get("ip") not in (None, "", "0.0.0.0")


def notify(runner: Runner, title: str, content: str) -> None:
    if runner.available("termux-notification"):
        runner.run(["termux-notification", "--id", NOTIFICATION_ID, "--title", title, "--content", content[:400]], 20)


def schedule(runner: Runner, script: Path, every_minutes: int) -> str:
    if not runner.available("termux-job-scheduler"):
        return "termux-job-scheduler is not installed (pkg install termux-api, plus the Termux:API app)"
    result = runner.run([
        "termux-job-scheduler", "--job-id", JOB_ID, "--script", str(script),
        "--period-ms", str(every_minutes * 60_000), "--network", "unmetered",
        "--battery-not-low", "true", "--persisted", "true",
    ], 30)
    return result.stdout.strip() or result.stderr.strip() or f"exit {result.returncode}"


def unschedule(runner: Runner) -> str:
    if not runner.available("termux-job-scheduler"):
        return "termux-job-scheduler is not installed"
    result = runner.run(["termux-job-scheduler", "--cancel", "--job-id", JOB_ID], 30)
    return result.stdout.strip() or result.stderr.strip() or f"exit {result.returncode}"
