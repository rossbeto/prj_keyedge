#!/usr/bin/env bash
# Image size budgets (tenant-lab lesson: budgets that are not checked break silently).
# Usage: image_size.sh [--if-present] <image> <maxMB> [<image> <maxMB> ...]
#   --if-present: skip images that are not built (native images are optional locally)
set -euo pipefail
optional=0
if [ "${1:-}" = "--if-present" ]; then optional=1; shift; fi
fail=0
while [ $# -ge 2 ]; do
  if ! bytes=$(docker image inspect "$1" --format '{{.Size}}' 2>/dev/null); then
    if [ "$optional" -eq 1 ]; then echo "skip  $1: not built"; shift 2; continue; fi
    echo "MISSING $1"; fail=1; shift 2; continue
  fi
  mb=$(( bytes / 1000000 ))
  if [ "$mb" -gt "$2" ]; then echo "OVER  $1: ${mb} MB > $2 MB"; fail=1; else echo "ok    $1: ${mb} MB <= $2 MB"; fi
  shift 2
done
exit $fail
