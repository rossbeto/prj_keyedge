#!/usr/bin/env bash
# Supply-chain basics for local images (Phase 010, D-51). Every tool runs as a digest-pinned container;
# nothing is installed on the host. Images are handed to scanners as `docker save` archives mounted
# read-only (no Docker socket inside third-party containers).
#
#   supply_chain.sh sbom   <image>...   CycloneDX SBOM of the image (syft) -> build/supply-chain/<name>.cdx.json
#   supply_chain.sh scan   <image>...   Trivy: fail on CRITICAL, report HIGH (ignores: .trivyignore, each with a reason)
#   supply_chain.sh sign   <image>...   push to the local registry 127.0.0.1:15500 and sign by digest (local key)
#   supply_chain.sh verify <image>...   verify the signature with the local public key
#   supply_chain.sh registry-up|registry-down
#
# The cosign key pair lives in ~/.keyedge/cosign (created on first use, password file 0600), never in the repo.
# Keyless signing needs a real CI identity: USER-ACTIONS.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

TRIVY=aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb
SYFT=anchore/syft:v1.57.2@sha256:e97e20b6e6d1699c60296b66ab337941724f9bd06ac279d893eda89601fc4b2a
COSIGN=ghcr.io/sigstore/cosign/cosign:v3.1.4@sha256:0f06154836d2de34f887a5c273506d52ca43edaa1d9e53e6510fa78a48f69c2d
REGISTRY_IMAGE=registry:3@sha256:82f6ff4a7f982960c8aa51617fea3aabc485df6e55cce80d22deb1de36f1bc3c
REGISTRY=localhost:15500 # "localhost" (not 127.0.0.1): Docker treats it as a local plain-HTTP registry
KEYDIR="$HOME/.keyedge/cosign"
OUT=build/supply-chain
mkdir -p "$OUT"

name_of() { echo "$1" | tr '/:' '__'; }

archive() { # image -> path of a docker-save tarball in a private temp dir
  local dir; dir=$(mktemp -d "${TMPDIR:-/tmp}/keyedge-img.XXXXXX")
  docker save -o "$dir/image.tar" "$1"
  echo "$dir"
}

registry_up() {
  if ! docker ps --format '{{.Names}}' | grep -qx keyedge-registry; then
    docker run -d --name keyedge-registry -p "127.0.0.1:15500:5000" --read-only --tmpfs /var/lib/registry \
      --cap-drop ALL --security-opt no-new-privileges:true "$REGISTRY_IMAGE" > /dev/null
  fi
}

keys() {
  if [ ! -f "$KEYDIR/cosign.key" ]; then
    mkdir -p "$KEYDIR" && chmod 700 "$KEYDIR"
    head -c 24 /dev/urandom | base64 > "$KEYDIR/password" && chmod 600 "$KEYDIR/password"
    docker run --rm -u "$(id -u):$(id -g)" -v "$KEYDIR:/keys" -w /keys \
      -e COSIGN_PASSWORD="$(cat "$KEYDIR/password")" "$COSIGN" generate-key-pair > /dev/null
    chmod 600 "$KEYDIR/cosign.key"
  fi
}

cmd=${1:?usage: supply_chain.sh sbom|scan|sign|verify|registry-up|registry-down <image>...}
shift || true
case "$cmd" in
  sbom)
    for img in "$@"; do
      dir=$(archive "$img")
      docker run --rm -v "$dir:/in:ro" "$SYFT" "docker-archive:/in/image.tar" -o cyclonedx-json -q \
        > "$OUT/$(name_of "$img").cdx.json"
      rm -rf -- "${dir:?}"
      echo "sbom  $img -> $OUT/$(name_of "$img").cdx.json ($(python3 -c "import json,sys;print(len(json.load(open(sys.argv[1])).get('components',[])))" "$OUT/$(name_of "$img").cdx.json") components)"
    done ;;
  scan)
    status=0
    for img in "$@"; do
      dir=$(archive "$img")
      cp .trivyignore "$dir/" 2>/dev/null || touch "$dir/.trivyignore"
      docker volume create keyedge-trivy-cache > /dev/null
      docker run --rm -v "$dir:/in:ro" -v keyedge-trivy-cache:/root/.cache/trivy "$TRIVY" image -q --timeout 30m \
        --input /in/image.tar --ignorefile /in/.trivyignore --severity HIGH,CRITICAL --format json \
        > "$OUT/$(name_of "$img").trivy.json" || true
      rm -rf -- "${dir:?}"
      if [ ! -s "$OUT/$(name_of "$img").trivy.json" ]; then echo "scan  $img: SCANNER FAILED (no report)"; status=1; continue; fi
      read -r crit high < <(python3 - "$OUT/$(name_of "$img").trivy.json" <<'PY'
import json, sys
doc = json.load(open(sys.argv[1]))
vulns = [v for r in doc.get("Results", []) for v in (r.get("Vulnerabilities") or [])]
print(sum(v["Severity"] == "CRITICAL" for v in vulns), sum(v["Severity"] == "HIGH" for v in vulns))
PY
)
      echo "scan  $img: CRITICAL=$crit HIGH=$high (report $OUT/$(name_of "$img").trivy.json)"
      [ "$crit" -eq 0 ] || status=1
    done
    exit $status ;;
  sign|verify)
    keys
    registry_up
    for img in "$@"; do
      target="$REGISTRY/$img"
      if [ "$cmd" = sign ]; then
        docker tag "$img" "$target" && docker push -q "$target" > /dev/null
        digest=$(docker image inspect "$target" --format '{{range .RepoDigests}}{{println .}}{{end}}' | grep "^$REGISTRY/" | head -1)
        docker run --rm --network host -u "$(id -u):$(id -g)" -v "$KEYDIR:/keys:ro" \
          -e COSIGN_PASSWORD="$(cat "$KEYDIR/password")" "$COSIGN" sign --yes --key /keys/cosign.key \
          --use-signing-config=false --tlog-upload=false --allow-insecure-registry "$digest" > "$OUT/cosign-sign.log" 2>&1 \
          || { echo "sign  $img: FAILED (see $OUT/cosign-sign.log)"; exit 1; }
        echo "sign  $img -> $digest"
      else
        digest=$(docker image inspect "$target" --format '{{range .RepoDigests}}{{println .}}{{end}}' | grep "^$REGISTRY/" | head -1)
        docker run --rm --network host -u "$(id -u):$(id -g)" -v "$KEYDIR:/keys:ro" "$COSIGN" verify --key /keys/cosign.pub \
          --insecure-ignore-tlog=true --allow-insecure-registry "$digest" > "$OUT/cosign-verify.log" 2>&1 \
          && echo "verify $img: signature OK ($digest)" || { echo "verify $img: FAILED"; exit 1; }
      fi
    done ;;
  registry-up) registry_up ;;
  registry-down) docker rm -f keyedge-registry > /dev/null 2>&1 || true ;;
  *) echo "unknown command $cmd" >&2; exit 2 ;;
esac
