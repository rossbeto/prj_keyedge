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
    # Phase 014: the ECS/ELB types exist as API objects; whether tasks really run is make ecs-smoke's job
    AWS::ECS::Cluster)
      aws ecs describe-clusters --clusters "$physical" --query 'clusters[0].status' --output text 2>/dev/null | grep -qx ACTIVE ;;
    AWS::ECS::Service) # physical id: arn:aws:ecs:<region>:<account>:service/<cluster>/<name>
      svc_cluster=$(echo "$physical" | awk -F/ '{print $(NF-1)}')
      aws ecs describe-services --cluster "$svc_cluster" --services "$physical" --query 'services[0].status' --output text 2>/dev/null | grep -qx ACTIVE ;;
    AWS::ECS::TaskDefinition)
      aws ecs describe-task-definition --task-definition "$physical" --query 'taskDefinition.status' --output text 2>/dev/null | grep -qx ACTIVE ;;
    AWS::ElasticLoadBalancingV2::LoadBalancer)
      aws elbv2 describe-load-balancers --load-balancer-arns "$physical" > /dev/null 2>&1 ;;
    AWS::ElasticLoadBalancingV2::TargetGroup)
      aws elbv2 describe-target-groups --target-group-arns "$physical" > /dev/null 2>&1 ;;
    AWS::ElasticLoadBalancingV2::Listener)
      aws elbv2 describe-listeners --listener-arns "$physical" > /dev/null 2>&1 ;;
    AWS::Logs::LogGroup)
      aws logs describe-log-groups --log-group-name-prefix "$physical" --query "logGroups[?logGroupName=='$physical'] | [0].logGroupName" --output text 2>/dev/null | grep -qx "$physical" ;;
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
