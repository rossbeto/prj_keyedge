# Changelog

All notable changes. Versions are tags `v<keyedge.version>` (gradle.properties); `make changelog TAG=` prints a
section as release notes.

## [1.0.0] - 2026-11-24

The complete lab: sixteen phases, built and measured locally on two AWS emulators.

### Added
- Key format with prefix and CRC32 checksum, peppered HMAC-SHA256 storage, RFC 9457 problem documents.
- Lock-free rate limiters (GCRA, packed token bucket) proven with jcstress and measured with JMH.
- One switch between Floci and AWS Emulator (and a guarded real-AWS target); the AWS contract suite on both.
- keys control plane: tenants, idempotent key creation, rotation with overlap, revocation, usage; a local OAuth
  issuer; a seeded upstream simulator; the compose stack and `make demo`.
- edge sidecar: verdict cache with single flight and refresh-ahead, signed principal to the app, breaker with a
  fail-static policy, adaptive concurrency limit, drain on shutdown.
- quotes demo app: three upstream calls with structured concurrency, futures on virtual threads and a Reactor twin.
- Native images (GraalVM) for edge, quotes and keys; SBOMs, Trivy gate, cosign signatures.
- Observability: OpenTelemetry traces across every hop, Prometheus metrics, dashboards as code, JSON logs with
  redaction, custom JFR events.
- The AWS footprint as CDK in Java and in Python from one spec, cdk-nag gated, byte-identical templates.
- Emulated ECS: the CDK service stack as a real two-container task behind an emulated ALB on Floci.
- The bench CLI: predictions before load, open-model k6 runs, schema-validated results, a generated lab
  notebook index, charts and README block, `docs-check`.
- CI: pull requests, tags and manual dispatch only; actions pinned by SHA; least privilege; zizmor and actionlint;
  `make release-check`.

### Measured
- 22 experiments with a hypothesis written before each run; refuted ones kept with their causes
  ([lab notebook](docs/lab-notebook.md)).
