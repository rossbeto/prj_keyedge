# 15. Bootstrap-less deploys to the emulators, verified resource by resource

Date: 2026-11-23 · Status: accepted

## Context
`cdk deploy` needs a bootstrapped account (an S3 bucket, ECR repo and roles) and publishes assets. The
emulators can run CloudFormation, but an emulator can also *pretend*: with stubbing on, an unsupported resource
type reports `CREATE_COMPLETE` and creates nothing.

## Decision
- Local mode synthesises with `BootstraplessSynthesizer` and **no assets** (images by URI, the OIDC provider as an
  L1 resource instead of the Lambda-backed L2); `scripts/cdk-deploy.sh` runs plain `aws cloudformation deploy`
  through `scripts/aws-env.sh`, after `emu.sh assert-local` (account 000000000000 at a local endpoint).
- Every deployed stack is checked by **`scripts/cdk-verify.sh`**: `DescribeStackResources`, then the service's own
  API per type (DynamoDB table and TTL, Secrets Manager, SSM, KMS key and alias). A resource CloudFormation
  lists but the service does not know fails the deploy, naming the type. Floci's stubbing stays off.
- `make cdk-deploy-parity` tries every stack on a target and writes `results/parity/cdk-<target>.json`: an
  unsupported stack is a parity row, not a red build.
- Real AWS is refused by the script (`KEYEDGE_ALLOW_REAL_AWS=yes` and a bootstrapped account are the user's job).

## Consequences
- The services read their names from the SSM contract the stacks write (`KEYEDGE_CONTRACT_PATH`), so `make up`
  runs on exactly what the IaC created; `scripts/seed.py` is gone.
- Types without a describe check (ECS, ELB, EC2) are "deployed", not "verified": Phase 014 exercises them.
