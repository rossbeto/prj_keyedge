#!/usr/bin/env bash
# Phase 006 calibration: k6 open-model load on upstream-sim alone (healthy scenario), several rates.
# Writes results/calibration/<date>-<sha>.json. Never run builds or other benchmarks meanwhile.
set -euo pipefail
cd "$(dirname "$0")/.."
K6=grafana/k6@sha256:3d8e5c7f1d53ec59408c2ffbd9943602a0985ffcbd4a2430ec00f3ffca89796e
RATES=${RATES:-500 1000 2000 4000}
RUN="$(date +%Y-%m-%d)-$(git rev-parse --short HEAD)"
OUT=results/calibration
mkdir -p "$OUT"
work=$(mktemp -d)
trap 'rm -r -- "$work"' EXIT
curl -sf -X PUT "http://127.0.0.1:15201/__chaos?name=healthy" > /dev/null
for rate in $RATES; do
  curl -sf -X POST http://127.0.0.1:15201/__reset > /dev/null
  docker run --rm --network keyedge_default --user "$(id -u):$(id -g)" -v "$PWD/deploy/k6:/scripts:ro" -v "$work:/out" \
    -e RATE="$rate" -e DURATION="${DURATION:-20s}" "$K6" run --quiet --summary-export "/out/k6-$rate.json" /scripts/calibrate.js > /dev/null
  curl -sf http://127.0.0.1:15201/__stats > "$work/stats-$rate.json"
  echo "rate $rate done"
done
python3 - "$work" "$OUT/$RUN.json" $RATES <<'PY'
import json, os, sys
work, out, rates = sys.argv[1], sys.argv[2], sys.argv[3:]
rows = []
for r in rates:
    k = json.load(open(f"{work}/k6-{r}.json"))["metrics"]
    s = json.load(open(f"{work}/stats-{r}.json"))
    d = k["http_req_duration"]
    rows.append({
        "offered_rps": int(r),
        "achieved_rps": round(k["http_reqs"]["rate"], 1),
        "p50_ms": round(d["p(50)"], 2), "p99_ms": round(d["p(99)"], 2), "max_ms": round(d["max"], 2),
        "dropped_iterations": int(k.get("dropped_iterations", {}).get("count", 0)),
        "failed_ratio": round(k["http_req_failed"]["value"], 4),
        "sim_in_flight_max": s["endpoints"]["stock"]["inFlightMax"],
    })
doc = {"schema": 1, "what": "upstream-sim alone, healthy scenario (stock 5-20 ms), k6 constant-arrival-rate",
       "cpus": os.cpu_count(), "rows": rows}
json.dump(doc, open(out, "w"), indent=2)
for row in rows:
    print(row)
PY
