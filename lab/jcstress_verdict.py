#!/usr/bin/env python3
"""Experiment `naive-bucket-race` (Phase 003): parse a jcstress console log into a verdict.

Hypothesis: the naive bucket (read, compute, write across two volatile fields) over-admits under
contention, and jcstress observes it; the CAS limiters (GCRA, packed bucket) and the permit never show a
FORBIDDEN outcome.

    python3 lab/jcstress_verdict.py <jcstress.log> <out.json>
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROW = re.compile(r"^\s*(?P<result>.+?)\s{2,}(?P<samples>[\d,]+)\s+(?P<freq>[\d.]+)%\s+(?P<expect>\S+)\s+(?P<desc>.*)$")
TEST = re.compile(r"^\.+\s+\[(?P<status>[A-Z]+)\]\s+(?P<name>dev\.keyedge\.\S+)")


def parse(log: str) -> dict[str, dict]:
    tests: dict[str, dict] = {}
    current = None
    in_table = False
    for line in log.splitlines():
        m = TEST.match(line)
        if m:
            current = m["name"].rsplit(".", 1)[-1]
            tests[current] = {"status": m["status"], "outcomes": {}}
            in_table = False
            continue
        if current and "RESULT" in line and "SAMPLES" in line:
            in_table = True
            continue
        if in_table:
            r = ROW.match(line)
            if r:
                o = tests[current]["outcomes"].setdefault(r["result"].strip(), {"samples": 0, "expect": r["expect"]})
                o["samples"] += int(r["samples"].replace(",", ""))
            elif line.strip() == "" and tests[current]["outcomes"]:
                in_table = False
    return tests


def main(log_path: str, out_path: str) -> int:
    tests = parse(Path(log_path).read_text(encoding="utf-8"))
    # Only tests jcstress reported as interesting/failed are printed in full; the rest passed with 0 FORBIDDEN.
    naive = tests.get("NaiveBucketOverAdmits", {}).get("outcomes", {})
    over = naive.get("true, true", {}).get("samples", 0)
    total = sum(o["samples"] for o in naive.values()) or 1
    failed = [n for n, t in tests.items() if t["status"] not in ("OK",)]
    forbidden_seen = {
        n: o for n, t in tests.items() if n != "NaiveBucketOverAdmits"
        for o, v in t["outcomes"].items() if v["expect"] == "Forbidden" and v["samples"] > 0
    }
    summary = re.findall(r"Results: (\d+) planned; (\d+) passed, (\d+) failed", Path(log_path).read_text())
    planned, passed, failed_n = map(int, summary[-1]) if summary else (0, 0, 0)
    verdict = "confirmed" if over > 0 and not forbidden_seen and failed_n == 0 and not failed else (
        "refuted" if over == 0 else "inconclusive")
    result = {
        "schema": 1,
        "experiment": "naive-bucket-race",
        "hypothesis": "naive read-compute-write bucket over-admits under contention; CAS limiters never do",
        "verdict": verdict,
        "naive_over_admissions": over,
        "naive_samples": total,
        "naive_over_admission_ratio": round(over / total, 4),
        "jcstress_runs": {"planned": planned, "passed": passed, "failed": failed_n},
        "forbidden_outcomes_in_correct_limiters": forbidden_seen,
        "recorded_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "git": subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip(),
    }
    Path(out_path).parent.mkdir(parents=True, exist_ok=True)
    Path(out_path).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: result[k] for k in ("verdict", "naive_over_admissions", "naive_over_admission_ratio", "jcstress_runs")}))
    return 0 if verdict == "confirmed" else 1


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:3]))
