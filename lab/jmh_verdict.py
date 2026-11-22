#!/usr/bin/env python3
"""Experiment `limiter-cost` (Phase 003): JMH results -> ratios -> verdict, plus an environment fingerprint.

Hypothesis: a GCRA decision costs well under ~100 ns uncontended; with 16 threads on one key it stays
within ~10x of that; GCRA is within 2x of Bucket4j on the same plan.

    python3 lab/jmh_verdict.py <jmh-results.json> <out-dir>
Writes <out-dir>/jmh.json (raw, copied), <out-dir>/limiter-cost.json (verdict) and prints a summary.
Assertions are ratios, never absolute nanoseconds (masterplan §3.3.4).
"""
from __future__ import annotations

import json
import os
import platform
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


def fingerprint(raw: list[dict]) -> dict:
    cpu = "unknown"
    try:
        for line in Path("/proc/cpuinfo").read_text().splitlines():
            if line.startswith("model name"):
                cpu = line.split(":", 1)[1].strip()
                break
    except OSError:
        pass
    first = raw[0] if raw else {}
    return {
        "cpu": cpu,
        "cpus": os.cpu_count(),
        "kernel": platform.release(),
        "jdk": first.get("jdkVersion"),
        "vm": f"{first.get('vmName')} {first.get('vmVersion')}",
        "jmh": first.get("jmhVersion"),
        "forks": first.get("forks"),
        "git": subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip(),
    }


def main(results: str, out_dir: str) -> int:
    raw = json.loads(Path(results).read_text())
    ns = {r["benchmark"].rsplit(".", 1)[-1]: r["primaryMetric"]["score"] for r in raw}
    err = {r["benchmark"].rsplit(".", 1)[-1]: r["primaryMetric"]["scoreError"] for r in raw}
    ratios = {
        "gcra_contended_vs_uncontended": ns["gcraContendedOneKey"] / ns["gcraUncontended"],
        "gcra_vs_bucket4j_uncontended": ns["gcraUncontended"] / ns["bucket4jUncontended"],
        "gcra_vs_bucket4j_contended": ns["gcraContendedOneKey"] / ns["bucket4jContendedOneKey"],
        "packed_vs_gcra_uncontended": ns["packedUncontended"] / ns["gcraUncontended"],
        "keyed_100k_vs_single_key": ns["keyedManyKeys"] / ns["gcraUncontended"],
        "aimd_vs_fixed": ns["aimdAcquireRelease"] / ns["fixedAcquireRelease"],
    }
    checks = {
        "uncontended_under_100ns": ns["gcraUncontended"] < 100,
        "contended_within_10x": ratios["gcra_contended_vs_uncontended"] <= 10,
        "within_2x_of_bucket4j": ratios["gcra_vs_bucket4j_uncontended"] <= 2,
    }
    verdict = "confirmed" if all(checks.values()) else ("refuted" if not checks["within_2x_of_bucket4j"] or not checks["uncontended_under_100ns"] else "inconclusive")
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    shutil.copy(results, out / "jmh.json")
    doc = {
        "schema": 1,
        "experiment": "limiter-cost",
        "hypothesis": "GCRA < ~100 ns uncontended; <= 10x with 16 threads on one key; within 2x of Bucket4j",
        "verdict": verdict,
        "checks": checks,
        "ns_per_op": {k: round(v, 2) for k, v in ns.items()},
        "ns_per_op_error": {k: round(v, 2) for k, v in err.items()},
        "ratios": {k: round(v, 2) for k, v in ratios.items()},
        "environment": fingerprint(raw),
        "recorded_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }
    (out / "limiter-cost.json").write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"verdict": verdict, "checks": checks, "ns_per_op": doc["ns_per_op"], "ratios": doc["ratios"]}, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:3]))
