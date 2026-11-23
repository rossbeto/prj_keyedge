"""Combine lab/pinning's per-JVM outputs into results/labs/pinning.json (make lab E=pinning)."""

import json
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
runs = {v: json.loads((ROOT / f"lab/pinning/build/pinning/java{v}.json").read_text()) for v in (21, 25)}
j21, j25 = runs[21], runs[25]
confirmed = j21["pinned_events"] > 0 and j25["pinned_events"] == 0 and j25["throughput_tasks_per_s"] > 10 * j21["throughput_tasks_per_s"]
result = {
    "experiment": "pinning",
    "hypothesis": "synchronized around a blocking call pins carrier threads on Java 21 (JFR jdk.VirtualThreadPinned, "
                  "throughput ~ carriers / sleep) but not on Java 25 (JEP 491)",
    "setup": "1 000 virtual threads, each synchronized on its own lock around a 50 ms sleep; 4 carrier threads; "
             "pinned events counted in-process with a JFR RecordingStream (threshold 0)",
    "java21": j21,
    "java25": j25,
    "throughput_ratio_25_over_21": round(j25["throughput_tasks_per_s"] / j21["throughput_tasks_per_s"], 1),
    "verdict": "confirmed" if confirmed else "refuted",
    "recorded_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
}
(ROOT / "results/labs/pinning.json").write_text(json.dumps(result, indent=2) + "\n")
print(result["verdict"], result["throughput_ratio_25_over_21"])
