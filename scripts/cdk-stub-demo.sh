#!/usr/bin/env bash
# The stub trap (Phase 012, D-30): an emulator that "supports" a resource type by stubbing it reports
# CREATE_COMPLETE while creating nothing. Shows both halves on Floci, then restores the normal emulator.
#   1. stubbing OFF (keyedge default): CloudFormation fails and names the type
#   2. stubbing ON: CloudFormation says CREATE_COMPLETE; cdk-verify (strict) fails naming the type
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
template=deploy/cdk-stub-demo/template.json
run() { # $1 = stubbing on|off
  scripts/emu.sh down floci > /dev/null 2>&1 || true
  KEYEDGE_FLOCI_ALLOW_STUB=$([ "$1" = on ] && echo true || echo false) scripts/emu.sh up floci > /dev/null
  # shellcheck disable=SC1090
  ( . <(scripts/aws-env.sh floci)
    if aws cloudformation deploy --stack-name KeyedgeStubDemo --template-file "$template" > /dev/null 2>&1; then
      echo "stubbing $1: CloudFormation says $(aws cloudformation describe-stacks --stack-name KeyedgeStubDemo --query 'Stacks[0].StackStatus' --output text)"
      if CDK_VERIFY_STRICT=1 scripts/cdk-verify.sh KeyedgeStubDemo; then echo "  verify: passed (unexpected)"; exit 1
      else echo "  verify: caught it (above)"; fi
    else
      echo "stubbing $1: CloudFormation failed: $(aws cloudformation describe-stack-events --stack-name KeyedgeStubDemo \
        --query 'StackEvents[?ResourceStatus==`CREATE_FAILED`] | [0].ResourceStatusReason' --output text)"
    fi )
}
run off
run on
echo "restoring Floci (stubbing off) and the contract stacks ..."
scripts/emu.sh down floci > /dev/null 2>&1 || true
scripts/emu.sh up floci > /dev/null
scripts/cdk-deploy.sh deploy floci
