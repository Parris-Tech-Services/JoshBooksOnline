#!/usr/bin/env python3
"""Read-only comparison of a private Drive audio manifest and a RUIZU inventory.

No network calls, file deletion, writes to player, or external packages.
All generated reports go to an explicit directory outside the player tree.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import os
import re
import stat
import sys
import tempfile
import unicodedata
import zipfile
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

AUDIO_EXT = {"mp3", "m4a", "m4b", "wav", "flac", "ogg", "opus", "wma", "aac", "ape", "aif", "aiff", "amr", "alac"}
SOURCE_REQUIRED = ("drive_file_id", "file_name", "relative_path", "size_bytes")
SCHEMA = "ruizu-readonly-comparison/v1"


def norm_name(name: str) -> str:
    stem = Path(str(name)).stem
    ascii_stem = unicodedata.normalize("NFKD", stem).encode("ascii", "ignore").decode().casefold()
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9]+", " ", ascii_stem)).strip()


def size_bytes(value, label: str) -> int | None:
    if value is None or value == "":
        return None
    if isinstance(value, bool):
        raise ValueError(f"{label}: invalid byte count")
    try:
        n = int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{label}: invalid byte count {value!r}") from exc
    if n < 0:
        raise ValueError(f"{label}: negative byte count")
    return n


def read_json_or_csv(path: Path):
    suffix = path.suffix.lower()
    if suffix == ".zip":
        with zipfile.ZipFile(path) as archive:
            names = [n for n in archive.namelist() if n.lower().endswith(".json") and not n.startswith("__MACOSX/")]
            preferred = [n for n in names if "manifest" in n.lower()]
            if not preferred and len(names) != 1:
                raise ValueError("ZIP needs exactly one JSON file or a named *manifest*.json")
            if len(preferred) > 1:
                raise ValueError("ZIP has several possible JSON manifests")
            name = (preferred or names)[0]
            info = archive.getinfo(name)
            if info.file_size > 50_000_000:
                raise ValueError("Manifest JSON is too large")
            return json.loads(archive.read(name).decode("utf-8-sig"))
    if suffix == ".json":
        return json.loads(path.read_text(encoding="utf-8-sig"))
    if suffix == ".csv":
        with path.open(newline="", encoding="utf-8-sig") as stream:
            return list(csv.DictReader(stream))
    raise ValueError("Input must be .json, .csv or a ZIP with a manifest JSON")


def load_drive(path: Path) -> list[dict]:
    raw = read_json_or_csv(path)
    files = raw.get("files") if isinstance(raw, dict) else raw
    if not isinstance(files, list):
        raise ValueError("Drive manifest must contain a files array or CSV rows")
    seen = set()
    records = []
    for n, item in enumerate(files, 1):
        if not isinstance(item, dict) or any(k not in item for k in SOURCE_REQUIRED):
            raise ValueError(f"Drive record {n}: missing required fields {SOURCE_REQUIRED}")
        ident = str(item["drive_file_id"] or "").strip()
        name = str(item["file_name"] or "")
        if not ident or not name or ident in seen:
            raise ValueError(f"Drive record {n}: missing name/ID or repeated Drive ID")
        seen.add(ident)
        record = dict(item)
        record["drive_file_id"] = ident
        record["file_name"] = name
        record["relative_path"] = str(item["relative_path"] or name)
        record["size_bytes"] = size_bytes(item["size_bytes"], f"Drive record {n}")
        record["comparison_name_key"] = norm_name(name)
        records.append(record)
    return records


def load_player(path: Path) -> list[dict]:
    raw = read_json_or_csv(path)
    files = raw.get("files") if isinstance(raw, dict) else raw
    if not isinstance(files, list):
        raise ValueError("Player manifest must contain files array or CSV rows")
    records = []
    for n, entry in enumerate(files, 1):
        if not isinstance(entry, dict):
            raise ValueError(f"Player record {n}: not an object")
        rel = entry.get("relative_path") or entry.get("path") or entry.get("name") or entry.get("file_name")
        if not rel or not isinstance(rel, str):
            raise ValueError(f"Player record {n}: no relative path/name")
        size = size_bytes(entry.get("size_bytes", entry.get("size")), f"Player record {n}")
        name = Path(rel).name
        records.append({"relative_path": rel, "file_name": name, "size_bytes": size,
                        "comparison_name_key": norm_name(name), "sha256": entry.get("sha256") or entry.get("content_sha256")})
    return records


def scan_player(folder: Path) -> tuple[list[dict], list[str]]:
    root = folder.resolve(strict=True)
    if not root.is_dir():
        raise ValueError("Player root must be a directory")
    result, errors = [], []
    def onerror(err):
        errors.append(f"{err.filename}: {err.strerror}")
    for base, dirs, files in os.walk(root, topdown=True, followlinks=False, onerror=onerror):
        good = []
        for d in dirs:
            if (Path(base) / d).is_symlink():
                errors.append(f"Skipped symlink directory: {(Path(base) / d).relative_to(root)}")
            else:
                good.append(d)
        dirs[:] = sorted(good)
        for name in sorted(files):
            file = Path(base) / name
            if file.suffix.lower().lstrip(".") not in AUDIO_EXT:
                continue
            if file.is_symlink():
                errors.append(f"Skipped symlink audio: {file.relative_to(root)}")
                continue
            try:
                metadata = file.stat(follow_symlinks=False)
            except OSError as exc:
                errors.append(f"Could not stat {file.relative_to(root)}: {exc}")
                continue
            if not stat.S_ISREG(metadata.st_mode):
                errors.append(f"Skipped non-regular audio: {file.relative_to(root)}")
                continue
            result.append({"relative_path": file.relative_to(root).as_posix(), "file_name": name,
                           "size_bytes": metadata.st_size, "comparison_name_key": norm_name(name),
                           "sha256": None})
    return result, errors


def safe_output_dir(folder: Path, player_root: Path | None) -> Path:
    output = folder.resolve()
    if player_root is not None:
        player = player_root.resolve(strict=True)
        if os.path.commonpath([str(output), str(player)]) == str(player):
            raise ValueError("Refusing to write reports on the RUIZU player")
    output.mkdir(parents=True, exist_ok=True)
    return output


def save_atomic(folder: Path, name: str, content: str) -> None:
    tmp = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", newline="", dir=folder,
                                         prefix=".ruizu-report-", delete=False) as stream:
            tmp = Path(stream.name)
            stream.write(content)
        os.replace(tmp, folder / name)
    finally:
        if tmp and tmp.exists():
            tmp.unlink()


def csv_text(headers: list[str], rows: list[list]) -> str:
    buffer = io.StringIO(newline="")
    out = csv.writer(buffer)
    out.writerow(headers)
    for row in rows:
        # Spreadsheet applications can evaluate formula-like strings from untrusted filenames.
        out.writerow(["'" + v if isinstance(v, str) and v.lstrip().startswith(("=", "+", "-", "@"))
                      else v if v is not None else "" for v in row])
    return buffer.getvalue()


def audit_source(files: list[dict]) -> tuple[dict, list[dict]]:
    buckets = defaultdict(list)
    by_name = defaultdict(set)
    empty = []
    mismatches = []
    for f in files:
        size = f["size_bytes"]
        if size == 0:
            empty.append(f["drive_file_id"])
        if f["file_name"].lower().endswith(".m4a") and str(f.get("mime_type", "")).lower() == "video/mp4":
            mismatches.append(f["drive_file_id"])
        if size is not None and size > 0 and f["comparison_name_key"]:
            buckets[(f["comparison_name_key"], size)].append(f)
        if size is not None and f["comparison_name_key"]:
            by_name[f["comparison_name_key"]].add(size)
    groups = [items for items in buckets.values() if len(items) > 1]
    groups.sort(key=lambda items: (items[0]["comparison_name_key"], items[0]["size_bytes"]))
    candidates = []
    for i, group in enumerate(groups, 1):
        for f in group:
            candidates.append({"group": f"D-{i:04d}", "files_in_group": len(group),
                               "drive_file_id": f["drive_file_id"], "file_name": f["file_name"],
                               "source_root": f.get("source_root", ""), "size_bytes": f["size_bytes"]})
    result = {"schema": "ruizu-source-audit/v1", "source_records": len(files),
              "source_bytes_known": sum(f["size_bytes"] or 0 for f in files),
              "zero_byte_drive_ids": empty, "m4a_video_mime_drive_ids": mismatches,
              "nonempty_same_name_size_groups": len(groups),
              "nonempty_same_name_size_records": len(candidates),
              "names_with_different_sizes": sum(len(v) > 1 for v in by_name.values()),
              "interpretation": "All duplicate groups are metadata candidates, not verified identical audio."}
    return result, candidates


def compare_files(sources: list[dict], player: list[dict], complete: bool) -> tuple[dict, list[dict]]:
    by_name = defaultdict(list)
    by_hash = defaultdict(list)
    for p in player:
        if p["comparison_name_key"]:
            by_name[p["comparison_name_key"]].append(p)
        digest = str(p.get("sha256") or "").lower()
        if re.fullmatch(r"[a-f0-9]{64}", digest):
            by_hash[digest].append(p)
    results, counts = [], Counter()
    for f in sources:
        s = f["size_bytes"]
        name_key = f["comparison_name_key"]
        direct = by_name.get(name_key, []) if name_key else []
        src_hash = str(f.get("content_sha256") or "").lower()
        by_digest = by_hash.get(src_hash, []) if re.fullmatch(r"[a-f0-9]{64}", src_hash) else []
        # Empty source files are never useful as playable audio or proof of duplication.
        if s == 0:
            status, reason, candidates = "SOURCE_EMPTY", "Zero-byte source; not playable audio", []
        elif by_digest:
            # Identical full-file SHA-256 requires identical byte length. Conflicting known
            # sizes mean at least one inventory value is stale, corrupt or not full-file data.
            # Never assert verified identity across that contradiction.
            compatible = [p for p in by_digest if s is None or p["size_bytes"] is None or p["size_bytes"] == s]
            if compatible:
                status, reason, candidates = "VERIFIED_PRESENT", "Matching full-file SHA-256 supplied by both inventories; known sizes agree", compatible
            else:
                status, reason, candidates = "POSSIBLE_MATCH", "SHA-256 values match but supplied byte sizes contradict; verify both inventories", by_digest
        elif any(x["size_bytes"] == s and s is not None for x in direct):
            same_size = [x for x in direct if x["size_bytes"] == s]
            # A matching name/size is not enough if two independently supplied hashes contradict.
            disagree = [x for x in same_size if re.fullmatch(r"[a-f0-9]{64}", str(x.get("sha256") or "").lower())
                        and re.fullmatch(r"[a-f0-9]{64}", src_hash)
                        and str(x["sha256"]).lower() != src_hash]
            good = [x for x in same_size if x not in disagree]
            if good:
                status, reason, candidates = "LIKELY_PRESENT", "Normalized filename and byte size match; audio bytes unverified", good
            else:
                status, reason, candidates = "POSSIBLE_MATCH", "Filename/size match but SHA-256 conflicts", disagree
        elif direct:
            status, reason, candidates = "POSSIBLE_MATCH", "Same normalized filename, different or unknown size", direct
        elif complete:
            status, reason, candidates = "NOT_FOUND_IN_SCAN", "No normalized filename match in the complete supplied scan; renamed copies remain possible", []
        else:
            status, reason, candidates = "UNCONFIRMED", "No filename match, but player inventory is incomplete/unconfirmed", []
        counts[status] += 1
        results.append({"drive_file_id": f["drive_file_id"], "source_root": f.get("source_root", ""),
                        "source_relative_path": f["relative_path"], "file_name": f["file_name"],
                        "size_bytes": s, "status": status, "reason": reason,
                        "player_paths": [p["relative_path"] for p in candidates],
                        "drive_link": f.get("drive_link", "")})
    report = {"schema": SCHEMA, "generated_utc": datetime.now(timezone.utc).isoformat(),
              "source_count": len(sources), "player_count": len(player),
              "player_inventory_complete": complete, "status_counts": dict(sorted(counts.items())),
              "warning": "NOT_FOUND_IN_SCAN is not proof of absence; files may be renamed, converted, or outside the selected scan. Name/size matches are not cryptographic proof."}
    return report, results


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    aud = commands.add_parser("audit", help="Audit the Drive catalogue without a player")
    aud.add_argument("--drive-manifest", required=True, type=Path)
    aud.add_argument("--output-dir", required=True, type=Path)
    scan = commands.add_parser("scan-player", help="Read-only scan of audio filenames and sizes")
    scan.add_argument("--player-root", required=True, type=Path)
    scan.add_argument("--output-dir", required=True, type=Path)
    comp = commands.add_parser("compare", help="Compare Drive catalogue with a player tree or inventory")
    comp.add_argument("--drive-manifest", required=True, type=Path)
    target = comp.add_mutually_exclusive_group(required=True)
    target.add_argument("--player-root", type=Path)
    target.add_argument("--player-manifest", type=Path)
    comp.add_argument("--confirm-inventory-complete", action="store_true",
                      help="Only for imported player manifests, when independently verified complete")
    comp.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "audit":
            sources = load_drive(args.drive_manifest)
            output = safe_output_dir(args.output_dir, None)
            result, duplicate_rows = audit_source(sources)
            save_atomic(output, "source-audit.json", json.dumps(result, indent=2, ensure_ascii=False) + "\n")
            fields = ["group", "files_in_group", "drive_file_id", "file_name", "source_root", "size_bytes"]
            save_atomic(output, "source-duplicate-candidates.csv", csv_text(fields, [[r[k] for k in fields] for r in duplicate_rows]))
            print(json.dumps({"output": str(output), **result}, ensure_ascii=False))
        elif args.command == "scan-player":
            player, errors = scan_player(args.player_root)
            output = safe_output_dir(args.output_dir, args.player_root)
            report = {"schema": "ruizu-player-inventory/v1", "root_label": args.player_root.name,
                      "complete": not errors and bool(player), "errors": errors,
                      "files": player, "audio_count": len(player)}
            save_atomic(output, "player-inventory.json", json.dumps(report, indent=2, ensure_ascii=False) + "\n")
            print(json.dumps({"output": str(output), "audio_count": len(player), "errors": errors}, ensure_ascii=False))
        else:
            sources = load_drive(args.drive_manifest)
            if args.player_root:
                player, errors = scan_player(args.player_root)
                complete = not errors and bool(player)
            else:
                player = load_player(args.player_manifest)
                errors = []
                complete = bool(args.confirm_inventory_complete) and bool(player)
            output = safe_output_dir(args.output_dir, args.player_root)
            report, rows = compare_files(sources, player, complete)
            report["scan_errors"] = errors
            report["inventory_provenance"] = ("read_only_directory_scan" if args.player_root else "imported_player_manifest")
            save_atomic(output, "comparison-summary.json", json.dumps(report, indent=2, ensure_ascii=False) + "\n")
            fields = ["drive_file_id", "source_root", "source_relative_path", "file_name", "size_bytes", "status", "reason", "player_paths", "drive_link"]
            save_atomic(output, "comparison-results.csv", csv_text(fields, [[("; ".join(r[k]) if isinstance(r[k], list) else r[k]) for k in fields] for r in rows]))
            print(json.dumps({"output": str(output), **report}, ensure_ascii=False))
        return 0
    except (ValueError, OSError, UnicodeError, json.JSONDecodeError, csv.Error, zipfile.BadZipFile) as error:
        print(f"RUIZU comparator: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())