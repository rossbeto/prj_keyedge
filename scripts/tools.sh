#!/usr/bin/env bash
# Download pinned CLI tools into .tools/ (no sudo, D-44). Each download is checked against a pinned SHA-256.
# sops: hash from the release's checksums.txt. age: the release has no checksums file (only a sigsum
# proof), so the hash was recorded on first download (2026-11-23) and is pinned here.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .tools
SOPS_VERSION=v3.13.3
SOPS_SHA256=e5bec3346a873ae91d871550f3e698c1aad962aff462a080e40f25fde17fef6b
AGE_VERSION=v1.3.2
AGE_SHA256=cbe24006683f8eb669266162894b9a522a1af52f2665fbc63a4bb032ed26ac10

fetch() { # url sha256 out
  local tmp; tmp=$(mktemp)
  curl -fsSL -o "$tmp" "$1"
  echo "$2  $tmp" | sha256sum -c --quiet - || { rm -f "$tmp"; echo "checksum mismatch for $1" >&2; exit 1; }
  mv "$tmp" "$3"
}

if [ ! -x .tools/sops ] || ! .tools/sops --version 2>/dev/null | grep -q "${SOPS_VERSION#v}"; then
  fetch "https://github.com/getsops/sops/releases/download/$SOPS_VERSION/sops-$SOPS_VERSION.linux.amd64" "$SOPS_SHA256" .tools/sops
  chmod +x .tools/sops
fi
if [ ! -x .tools/age ]; then
  fetch "https://github.com/FiloSottile/age/releases/download/$AGE_VERSION/age-$AGE_VERSION-linux-amd64.tar.gz" "$AGE_SHA256" .tools/age.tgz
  tar -xzf .tools/age.tgz -C .tools --strip-components=1 age/age age/age-keygen && rm .tools/age.tgz
fi
.tools/sops --version --check-for-updates=false 2>/dev/null | head -1 || .tools/sops --version | head -1
.tools/age --version
