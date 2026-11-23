# Infrastructure: two CDK apps, one spec

```
infra/
├── package.json        aws-cdk CLI pinned once (npx), for both apps
├── spec/               infra-spec.yaml (every name, size, delay, weight, SSM key, cdk-nag reason) + JSON schema
├── java/               :infra:java — config / constructs / stacks / InfraApp; tests: assertions, snapshots, nag
├── python/             uv project keyedge_infra — the same layers; tests: synth+nag, assertions, spec, layering
└── out/<lang>/<mode>/  synthesised templates (gitignored)
```

## Everyday commands

| Command | What |
|---|---|
| `make cdk-synth [CDK_LANG=java\|python]` | both modes of one app (cdk-nag errors fail the synth) |
| `make cdk-test [CDK_LANG=]` / `make cdk-test-all` | one app's tests / both apps + the diff tool's tests |
| `make cdk-parity` | versions check, synth both apps × both modes, diff every template |
| `make cdk-versions-check` | catalog == `uv.lock` for aws-cdk-lib, cdk-nag, constructs; `cdk.json` contexts equal |
| `make cdk-deploy [CDK_LANG=] [TARGET=] [STACKS=]` | bootstrap-less deploy to an emulator + per-resource verify |
| `make cdk-deploy-parity [CDK_LANG=] TARGET=` | every stack on one emulator → `results/parity/cdk-<target>-<lang>.json` |
| `make cdk-stub-demo` | the stub trap on Floci (ADR 0015) |
| `make up [CDK_LANG=]` | deploy the data/config stacks, then the services (they read the SSM contract) |

`KEYEDGE_CDK_LANG` in `.env` sets the default language (`CDK_LANG=` overrides one command; not `LANG`, the locale).

## Adding a resource
1. Put every literal in `infra/spec/infra-spec.yaml` (and the schema if it is a new section); a condition is a
   spec value, not code.
2. Java: construct (props record, explicit construct ids) + stack wiring + an assertion in `StacksTest`;
   `./gradlew :infra:java:test -Pcdk -PupdateSnapshots=true`, review the snapshot diff.
3. Python: the same construct with **the same ids and the same call order**; `make cdk-parity` until identical.
4. A new cdk-nag finding: fix it, or add the rule and a reason under `nag.<mode>` in the spec (granular findings
   on the construct, see ADR 0014); the acknowledged-rule snapshot changes visibly in review.

## What the diff ignores, and why
- `aws:cdk:path` / `aws:asset:*` metadata, the `CDKMetadata` resource and condition: build metadata, not
  infrastructure (both apps turn them off anyway).
- Mapping key order, and list order only under `Tags`, `ManagedPolicyArns`, `SecurityGroupIngress`, `DependsOn`.
  Every other list keeps its order (container definitions, policy statements): there, order can matter.

## Running the templates as real tasks (emulated ECS, Phase 014)

```bash
make ecs-up          # Floci with the Docker socket (WARNING printed: root-equivalent, opt-in), data/config stacks,
                     # keys/issuer/upstream-sim/toxiproxy in compose (against floci-ecs)
make ecs-deploy      # KeyedgeCluster, KeyedgeEdgeLb, KeyedgeQuotesService (in that order: the service imports the ALB)
make ecs-status      # running count, containers, addresses on the compose network
make ecs-smoke       # quotes-check through the task's edge container
EDGE_URL=http://127.0.0.1:15180 make quotes-check   # the same through the emulated ALB
make ecs-experiments # task-start-order, drain-on-deploy -> results/ecs/*.json
make ecs-down        # deletes the stacks (Floci removes its task containers), prints how many are left (expect 0)
```

What runs where: each task container is a sibling Docker container named `floci-aws-ecs-<task id>-<container>`,
on the compose network and on a per-VPC network, with **no host port** (reach the edge on its compose address or
through the ALB). The template is the one CDK synthesises for AWS: the sidecar finds the app with
`KEYEDGE_APP_URL=ecs:quotes:15081` (ADR 0018), services bind `0.0.0.0` inside the task, and the AWS SDK follows the
`AWS_ENDPOINT_URL` Floci injects. Logs reach the task's CloudWatch log groups (awslogs). Differences from AWS are
rows P-04/P-05/P-11… in [parity.md](parity.md).
