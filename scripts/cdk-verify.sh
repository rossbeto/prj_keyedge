#!/usr/bin/env bash
# Verify a deployed stack resource by resource with each service's own API (Phase 012, D-30): an
# emulator that "creates" a resource type it does not support reports CREATE_COMPLETE for a stub;
# this catches it. Expects the AWS environment of the target (scripts/aws-env.sh) in the shell.
#   scripts/cdk-verify.sh <stack>      -> "verified: <n> resources, stubbed: 0" or exit 1 naming the types
#   CDK_VERIFY_RESOURCES=<json file>   use a canned DescribeStackResources answer (shell test)
#   CDK_VERIFY_STRICT=1                a type without a describe check fails too (stub-trap demo)
set -euo pipefail
stack=${1:?stack}
if [ -n "${CDK_VERIFY_RESOURCES:-}" ]; then
  resources=$(cat "$CDK_VERIFY_RESOURCES")
else
  resources=$(aws cloudformation describe-stack-resources --stack-name "$stack" --output json)
fi
verified=0; stubbed=()
while IFS=$'\t' read -r type physical; do
  [ -n "$type" ] || continue
  case "$type" in
    AWS::DynamoDB::Table)
      aws dynamodb describe-table --table-name "$physical" > /dev/null 2>&1 \
        && aws dynamodb describe-time-to-live --table-name "$physical" --query 'TimeToLiveDescription.TimeToLiveStatus' --output text 2>/dev/null | grep -q ENABLED ;;
    AWS::SecretsManager::Secret) aws secretsmanager describe-secret --secret-id "$physical" > /dev/null 2>&1 ;;
    AWS::SSM::Parameter) aws ssm get-parameter --name "$physical" > /dev/null 2>&1 ;;
    AWS::KMS::Key) aws kms describe-key --key-id "$physical" > /dev/null 2>&1 ;;
    AWS::KMS::Alias) aws kms list-aliases --query "Aliases[?AliasName=='$physical'] | [0].AliasName" --output text 2>/dev/null | grep -qx "$physical" ;;
    *)
      if [ "${CDK_VERIFY_STRICT:-0}" = 1 ]; then false
      else echo "  (no describe check for $type: counted as unverified)" >&2; continue; fi ;;
  esac && verified=$((verified + 1)) || stubbed+=("$type:$physical")
done < <(echo "$resources" | python3 -c "
import json,sys
for r in json.load(sys.stdin)['StackResources']:
    print(r['ResourceType'] + '\t' + r.get('PhysicalResourceId', ''))")
if [ ${#stubbed[@]} -gt 0 ]; then
  echo "$stack: verified: $verified resources, stubbed: ${#stubbed[@]} -> ${stubbed[*]}" >&2
  exit 1
fi
echo "$stack: verified: $verified resources, stubbed: 0"
