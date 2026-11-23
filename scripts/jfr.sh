#!/usr/bin/env bash
# make jfr S=edge|quotes|keys D=60s [TARGET=...]: record one JVM service with deploy/jfr/keyedge.jfc while
# demo traffic runs, then copy the recording to results/jfr/<svc>-<timestamp>.jfr and print a summary.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
svc=${1:?service}; dur=${2:-60s}; target=${3:-floci}
secs=${dur%s}
mkdir -p build/jfr results/jfr && chmod 777 build/jfr   # the container user writes here (local only)
rm -f "build/jfr/$svc.jfr"
main=$(docker compose -f deploy/compose.yaml config --format json | python3 -c "import json,sys;print(json.load(sys.stdin)['services']['$svc']['command'][-1])")
# The flag goes on the service's command line (JVM options may follow -cp), not into JDK_JAVA_OPTIONS:
# the health-check JVM in the same container would read that too and overwrite the recording.
cat > build/jfr/compose.jfr.yaml <<YAML
services:
  $svc:
    command: ["-XX:StartFlightRecording=name=keyedge,settings=/jfr/keyedge.jfc,duration=${dur},filename=/jfr-out/${svc}.jfr", "$main"]
    volumes:
      - $PWD/deploy/jfr:/jfr:ro
      - $PWD/build/jfr:/jfr-out
YAML
services=$svc; [ "$svc" = edge ] && services="edge quotes"   # quotes lives in edge's network namespace
eval "$(make -s --no-print-directory container-env TARGET="$target")"
docker compose -f deploy/compose.yaml -f build/jfr/compose.jfr.yaml up -d --wait --force-recreate $services
echo "recording $svc for $dur ..."
end=$((SECONDS + secs))
while [ $SECONDS -lt $end ]; do   # traffic: the demo and the quotes check, repeatedly
  make -s --no-print-directory demo TARGET="$target" > /dev/null 2>&1 || true
  make -s --no-print-directory quotes-check > /dev/null 2>&1 || true
done
sleep 5   # the recording is written when its duration ends
for _ in $(seq 1 60); do [ -s "build/jfr/$svc.jfr" ] && break; sleep 1; done
out="results/jfr/$svc-$(date +%Y%m%d-%H%M%S).jfr"
cp "build/jfr/$svc.jfr" "$out"
JFR=$(ls -d "$HOME"/.gradle/jdks/eclipse_adoptium-25*/bin/jfr | head -1)
echo "$out"
"$JFR" summary "$out" | grep -E "dev.keyedge|VirtualThreadPinned|Duration" || true
# back to the normal service definition
docker compose -f deploy/compose.yaml up -d --wait --force-recreate $services > /dev/null 2>&1
