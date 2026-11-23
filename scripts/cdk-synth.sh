#!/usr/bin/env bash
# Synthesise one CDK app for one mode into infra/out/<lang>/<mode> (Phase 013).
#   scripts/cdk-synth.sh java|python local|prodlike
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
lang=${1:?java|python}; mode=${2:?local|prodlike}
out="$PWD/infra/out/$lang/$mode"
rm -rf -- "${out:?}"
export CDK_OUTDIR="$out" CDK_CONTEXT_JSON="{\"mode\":\"$mode\",\"env\":\"$mode\"}"
case "$lang" in
  java) ./gradlew -q :infra:java:run ;;
  python) uv run --project infra/python --frozen python -m keyedge_infra.app ;;
  *) echo "CDK_LANG must be java or python" >&2; exit 2 ;;
esac 2>&1 | grep -v "addDependency is deprecated\|Use \`addStackDependency\` instead" || true
[ -f "$out/manifest.json" ] || { echo "synth failed for $lang/$mode" >&2; exit 1; }
echo "synth $lang/$mode -> infra/out/$lang/$mode ($(ls "$out"/*.template.json | wc -l) templates)"
