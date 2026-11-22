#!/usr/bin/env bash
# Toxiproxy helpers for the stack (API on 127.0.0.1:15474).
#   toxics.sh add <proxy> <name> <type> '<attributes-json>'   e.g. add keys slow latency '{"latency":2000}'
#   toxics.sh clear                                           remove every toxic, re-enable every proxy
#   toxics.sh list
#   toxics.sh scenario keys-down|keys-slow|keys-flaky         control-plane chaos for the edge (Phase 008)
set -euo pipefail
API=http://127.0.0.1:15474
case "${1:-list}" in
  add)
    curl -sf -X POST "$API/proxies/$2/toxics" -H 'Content-Type: application/json' \
      -d "{\"name\":\"$3\",\"type\":\"$4\",\"attributes\":${5:-{\}}}" > /dev/null ;;
  scenario)
    case "${2:-}" in
      keys-down)  curl -sf -X POST "$API/proxies/keys" -H 'Content-Type: application/json' -d '{"enabled":false}' > /dev/null ;;
      keys-slow)  "$0" add keys slow latency '{"latency":5000}' ;;
      keys-flaky) curl -sf -X POST "$API/proxies/keys/toxics" -H 'Content-Type: application/json' \
                    -d '{"name":"flaky","type":"timeout","toxicity":0.5,"attributes":{"timeout":3000}}' > /dev/null ;;
      *) echo "unknown scenario '${2:-}' (keys-down | keys-slow | keys-flaky)" >&2; exit 2 ;;
    esac
    echo "toxiproxy: ${2}" ;;
  clear)
    for proxy in $(curl -sf "$API/proxies" | jq -r 'keys[]'); do
      curl -sf -X POST "$API/proxies/$proxy" -H 'Content-Type: application/json' -d '{"enabled":true}' > /dev/null
      for toxic in $(curl -sf "$API/proxies/$proxy/toxics" | jq -r '.[].name'); do
        curl -sf -X DELETE "$API/proxies/$proxy/toxics/$toxic" > /dev/null
      done
    done ;;
  list) curl -sf "$API/proxies" | jq -r 'to_entries[] | "\(.key)\t\(.value.listen) -> \(.value.upstream)\ttoxics=\(.value.toxics | length)"' ;;
  *) echo "usage: toxics.sh add|scenario|clear|list" >&2; exit 2 ;;
esac
