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
