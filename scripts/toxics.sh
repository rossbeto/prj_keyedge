#!/usr/bin/env bash
# Toxiproxy helpers for the stack (API on 127.0.0.1:15474).
#   toxics.sh add <proxy> <name> <type> '<attributes-json>'   e.g. add keys slow latency '{"latency":2000}'
#   toxics.sh clear                                           remove every toxic from every proxy
#   toxics.sh list
set -euo pipefail
API=http://127.0.0.1:15474
case "${1:-list}" in
  add)
    curl -sf -X POST "$API/proxies/$2/toxics" -H 'Content-Type: application/json' \
      -d "{\"name\":\"$3\",\"type\":\"$4\",\"attributes\":${5:-{\}}}" > /dev/null ;;
  clear)
    for proxy in $(curl -sf "$API/proxies" | jq -r 'keys[]'); do
      for toxic in $(curl -sf "$API/proxies/$proxy/toxics" | jq -r '.[].name'); do
        curl -sf -X DELETE "$API/proxies/$proxy/toxics/$toxic" > /dev/null
      done
    done ;;
  list) curl -sf "$API/proxies" | jq -r 'to_entries[] | "\(.key)\t\(.value.listen) -> \(.value.upstream)\ttoxics=\(.value.toxics | length)"' ;;
  *) echo "usage: toxics.sh add|clear|list" >&2; exit 2 ;;
esac
