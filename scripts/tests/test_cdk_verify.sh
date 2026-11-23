#!/usr/bin/env bash
# cdk-verify must fail, naming the type, when CloudFormation lists a resource the service does not know.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
tmp=$(mktemp -d); trap 'rm -rf -- "${tmp:?}"' EXIT
cat > "$tmp/resources.json" <<'JSON'
{"StackResources":[{"ResourceType":"AWS::DynamoDB::Table","PhysicalResourceId":"no-such-table-keyedge-test"}]}
JSON
# no emulator needed: every describe call fails, as it would for a stub
mkdir -p "$tmp/bin"; printf '#!/bin/sh\nexit 255\n' > "$tmp/bin/aws"; chmod +x "$tmp/bin/aws"
if out=$(PATH="$tmp/bin:$PATH" CDK_VERIFY_RESOURCES="$tmp/resources.json" scripts/cdk-verify.sh Fake 2>&1); then
  echo "FAIL: cdk-verify accepted a stubbed resource"; exit 1
fi
echo "$out" | grep -q "stubbed: 1 -> AWS::DynamoDB::Table:no-such-table-keyedge-test" || { echo "FAIL: $out"; exit 1; }
echo "ok: cdk-verify names the stubbed type"
