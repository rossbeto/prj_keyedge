#!/usr/bin/env python3
"""Summarise JUnit XML results and JaCoCo line coverage per module (stdlib only).

    python3 scripts/test_summary.py [module-dir ...]     # default: every module with results
"""
from __future__ import annotations

import glob
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def summarise(module: Path) -> str:
    tests = failures = skipped = 0
    failed: list[str] = []
    for xml in glob.glob(str(module / "build/test-results/*/*.xml")):
        root = ET.parse(xml).getroot()
        tests += int(root.get("tests", 0))
        skipped += int(root.get("skipped", 0))
        for case in root.iter("testcase"):
            for f in case.findall("failure") + case.findall("error"):
                failures += 1
                failed.append(f"    {case.get('classname')}.{case.get('name')}: {(f.get('message') or '')[:200]}")
    line = ""
    report = module / "build/reports/jacoco/test/jacocoTestReport.xml"
    if report.exists():
        for c in ET.parse(report).getroot().findall("counter"):
            if c.get("type") == "LINE":
                missed, covered = int(c.get("missed")), int(c.get("covered"))
                line = f" line {100 * covered / max(1, missed + covered):.1f}%"
    return "\n".join([f"{module}: {tests} tests, {failures} failed, {skipped} skipped{line}", *failed])


def main(args: list[str]) -> int:
    modules = [Path(a) for a in args] or sorted({Path(p).parents[3] for p in glob.glob("**/build/test-results/*/*.xml", recursive=True)})
    out = [summarise(m) for m in modules]
    print("\n".join(out))
    return 1 if any(" 0 failed" not in o.splitlines()[0] for o in out) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
