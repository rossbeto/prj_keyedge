#!/usr/bin/env bash
# Image size budgets (tenant-lab lesson: budgets that are not checked break silently).
# Usage: image_size.sh <image> <maxMB> [<image> <maxMB> ...]
set -euo pipefail
fail=0
while [ $# -ge 2 ]; do
  bytes=$(docker image inspect "$1" --format '{{.Size}}')
  mb=$(( bytes / 1000000 ))
  if [ "$mb" -gt "$2" ]; then echo "OVER  $1: ${mb} MB > $2 MB"; fail=1; else echo "ok    $1: ${mb} MB <= $2 MB"; fi
  shift 2
done
exit $fail
