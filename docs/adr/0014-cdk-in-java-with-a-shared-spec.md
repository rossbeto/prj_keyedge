# 14. Infrastructure as a CDK app in Java, driven by a shared spec

Date: 2026-11-23 · Status: accepted

## Context
The lab is a Java portfolio; the infrastructure should read like the services (records, small classes, tests).
Phase 013 adds the same app in Python, and the two must produce the same templates.

## Decision
- **CDK v2 in Java** (`infra/java`, aws-cdk-lib 2.278.0, cdk-nag 3.1.0; CLI 2.1148.0 pinned once in
  `infra/package.json`). Layers: `config` (records, the spec) ← `constructs` (one per concept, props records,
  no context/spec reads) ← `stacks` ← `InfraApp` (the only `main`); ArchUnit enforces it.
- **Every literal in `infra/spec/infra-spec.yaml`** (validated by `infra-spec.schema.json`): names, sizes,
  delays, weights, SSM keys, images, and every cdk-nag acknowledgment **with its reason**. Both CDK apps read it,
  so they can only differ in code.
- **cdk-nag 3 is a CDK policy-validation plugin**: `app.synth()` fails on any unacknowledged finding;
  acknowledgments use CDK's `Validations.of(...).acknowledge` with ids `AwsSolutions::<rule>`; granular findings
  (IAM5 per resource) are acknowledged on the construct with the exact finding (deviation: the base id did not
  cover them). The acknowledged rules per mode are snapshotted, so a new one shows in review.
- **Deterministic templates**: version reporting and path metadata off, explicit AZs and VPC CIDR, ordered maps
  (a `Map.of` in a template made snapshots flaky — found by the snapshot test), no cross-stack exports between
  stacks deployed in different phases (services import the table and secrets by name from the spec).
- Two modes: `local` (deployable to the emulators, cheap, DESTROY) and `prodlike` (synth and tests only: private
  subnets + NAT, flow logs, ALB access logs, HTTPS, RETAIN).

## Consequences
- Synth starts Node (jsii, ~20 s): CDK tests run in `make cdk-test` / a separate CI job, not in `make test`.
- The tests found two real defects before any deploy: an ALB listener open to 0.0.0.0/0 (CDK's default
  `open: true`, previously hidden by a blanket acknowledgment) and the nondeterministic template.
