# 17. Two CDK languages, one spec, proven equal

Date: 2026-11-23 · Status: accepted

## Context
The lab shows infrastructure as code in Java (the services' language) and in Python (what most platform teams
write CDK in). Two implementations of the same infrastructure are only worth having if something proves they
are the same — the pattern you would use to migrate IaC between languages, or to keep a reference
implementation honest.

## Options
| Option | For | Against |
|---|---|---|
| Java only | one codebase, same language as the services | no Python/platform-team story |
| Python only | the common platform choice | infra no longer matches the service code |
| **Both, with a parity proof** | migration pattern, two idioms of one design | double maintenance (accepted for the lab) |
| TypeScript (CDK's native language) | best docs, no jsii hop | a third language in a Java lab; not built |

## Decision
- `infra/java` (Gradle `:infra:java`) and `infra/python` (uv project, Python 3.14, ruff + mypy `--strict` +
  import-linter + pytest) implement the same stacks with **the same construct ids** (logical ids derive from
  them), the same call order, ordered maps, and the same synthesizer per mode.
- Both read **`infra/spec/infra-spec.yaml`** (JSON schema validated); the spec holds data, never logic — a
  branch in a construct is a spec value both languages read.
- **`make cdk-parity`** synthesises both apps in both modes and diffs every template
  (`scripts/cdk_template_diff.py`, stdlib, JSON pointers); `results/parity/cdk-templates.json`.
- **`make cdk-versions-check`**: aws-cdk-lib, cdk-nag, constructs in the Gradle catalog equal `uv.lock`, the two
  `cdk.json` context blocks are equal, one CLI pin (`infra/package.json`).
- `KEYEDGE_CDK_LANG=java|python` (`.env`) / `CDK_LANG=` picks the app for every `cdk-*` target and for `make up`.

## Consequences
- Measured: all 18 templates (9 stacks × 2 modes) are **byte-identical files** — the normaliser is a safety net,
  not a crutch. A changed construct id fails the diff with the JSON pointer of the changed logical id; a one-sided
  `aws-cdk-lib` bump fails the versions check before any template is compared; a spec change moves both apps.
- Java keeps the full assertion + snapshot suite; Python keeps a small suite (synth + nag, two assertion sets,
  spec, layering). Equal templates pass Java's tests by construction.
- Deploys from either app give the same per-stack outcome on both emulators (`results/parity/cdk-<target>-<lang>.json`).
- Every infrastructure change is made twice; the diff makes forgetting one side impossible to merge.
