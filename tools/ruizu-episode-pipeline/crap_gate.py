"""CRAP gate for this tool, using the same formula and threshold as
.github/workflows/crap4all-quality-gate.yml: CRAP = cc^2 * (1 - coverage)^3 + cc,
and the worst measured function must score below 30.

Usage: python crap_gate.py coverage.xml ruizu_episodes
"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import lizard

THRESHOLD = 30.0


def line_hits(coverage_xml: str) -> dict[str, dict[int, int]]:
    hits: dict[str, dict[int, int]] = {}
    for cls in ET.parse(coverage_xml).getroot().iter("class"):
        lines = {int(x.attrib["number"]): int(x.attrib["hits"]) for x in cls.iter("line")}
        hits[Path(cls.attrib["filename"]).name] = lines
    return hits


def scores(coverage_xml: str, package: Path) -> list[tuple[float, int, float, str]]:
    hits = line_hits(coverage_xml)
    rows = []
    for source in sorted(package.glob("*.py")):
        file_hits = hits.get(source.name, {})
        for fn in lizard.analyze_file(str(source)).function_list:
            lines = [h for n, h in file_hits.items() if fn.start_line <= n <= fn.end_line]
            covered = sum(1 for h in lines if h > 0) / len(lines) if lines else 0.0
            cc = fn.cyclomatic_complexity
            rows.append((round(cc**2 * (1 - covered) ** 3 + cc, 2), cc, round(covered, 2), f"{source.name}:{fn.name}"))
    return sorted(rows, reverse=True)


def main(argv: list[str]) -> int:
    rows = scores(argv[1], Path(argv[2]))
    for crap, cc, covered, name in rows[:5]:
        print(f"CRAP {crap:6.2f}  cc {cc:2d}  coverage {covered:4.0%}  {name}")
    headline = rows[0][0] if rows else 0.0
    verdict = "PASS" if headline < THRESHOLD else "FAIL"
    print(f"CRAP_GATE={verdict} headline={headline} threshold=<{THRESHOLD} functions={len(rows)}")
    return 0 if verdict == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
