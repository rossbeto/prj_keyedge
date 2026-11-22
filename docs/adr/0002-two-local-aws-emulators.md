# 2. Two local AWS emulators behind one switch

Date: 2026-11-23 · Status: accepted

## Context
The lab uses DynamoDB, Secrets Manager, SSM, KMS, CloudFormation and ECS, but must never touch a real
AWS account. Emulator feature lists are claims; the design depends on specific operations *and settings*
(a conditional `TransactWriteItems` with cancellation reasons, secret version stages, KMS HMAC keys,
container dependencies in ECS task definitions).

## Options
1. One emulator (Floci, LocalStack-compatible, MIT).
2. One emulator (AWS Emulator, from AWS, HTTPS with a per-run CA and SigV4 keys).
3. Both, behind one setting, with the same tests on each.

## Decision
Option 3. `KEYEDGE_AWS_TARGET=floci|aws-emulator` selects the endpoint for every client (services,
tests, scripts, CDK, SOPS). Before designing on either, Phase 001 checked every needed operation on both:
real calls (`make emu-smoke`) and, for AWS Emulator, its strict compatibility check.

## Consequences
- Differences become a published parity table instead of surprises.
- Every AWS-touching test runs twice; the cost is CI time.
- `scripts/emu.sh assert-local` refuses to continue unless the caller account is `000000000000`.
