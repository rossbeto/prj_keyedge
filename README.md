# keyedge

> **Lab project.** A portfolio lab that answers one question with code, tests and measurements:
> *how do you issue, store and rotate API keys safely, and how does a small sidecar next to every
> service authenticate, rate-limit and protect it without becoming the outage itself?*

**Status:** Phase 013 of 16: libraries, the two-emulator AWS switch ([parity](docs/parity.md)), the keys control plane, a local issuer, the upstream simulator and the **edge sidecar** in front of a demo app — `make up && make demo` runs the whole path on either emulator. The sidecar survives a control-plane outage (breaker, fail-static cache, adaptive concurrency limit): `make resilience-check`. The quote use case runs three ways — structured concurrency, futures on virtual threads and a Reactor twin — with the reasoning and measurements in [virtual-threads-vs-reactor.md](docs/virtual-threads-vs-reactor.md): `make quotes-check`. Native images (edge, quotes, keys) run the same stack with `make up FLAVOR=native`; SBOMs, a Trivy gate and cosign signatures: `make sbom scan sign verify` ([ADR 0012](docs/adr/0012-native-images-and-supply-chain.md)). `make observe` adds traces (Jaeger), metrics (Prometheus, Grafana dashboards as code) and JSON logs; `make jfr` records custom JFR events; the [runbook](docs/runbook.md) uses them. The AWS footprint is a CDK app in Java (`infra/java`, driven by `infra/spec/infra-spec.yaml`, cdk-nag gated); `make up` deploys its data and config stacks to the emulator and the services read their names from the SSM contract they write; secrets are SOPS-encrypted to age + KMS ([ADR 0014–0016](docs/adr/)). The same infrastructure exists as a CDK app in Python, and `make cdk-parity` proves both synthesise byte-identical templates ([docs/infra.md](docs/infra.md), [ADR 0017](docs/adr/0017-two-cdk-languages-one-spec.md)).

**Revocation latency is bounded, not zero:** a revoked key keeps working at a sidecar until its cached verdict expires
(TTL hint, 60 s), and while the control plane is down up to TTL + stale window (5 min) under the default fail-static
policy ([ADR 0009](docs/adr/0009-edge-admission.md), [ADR 0010](docs/adr/0010-fail-static-control-plane-policy.md)).

## What it will contain

| Part | What it is | Headline technique |
|---|---|---|
| `services/keys` | control plane: tenants, API keys (hashed, rotatable, revocable), usage | Java 25 on virtual threads, sealed domain types, DynamoDB transactions, idempotency, RFC 9457 |
| `services/edge` | sidecar in the same task as each service: checks the key, rate-limits, sheds load | GraalVM native image, single-flight cache, circuit breaker, lock-free limiter |
| `services/quotes` | demo app behind the sidecar, three upstream calls | structured concurrency vs `CompletableFuture` vs a Reactor twin |
| `services/upstream-sim` | seeded, scriptable flaky upstream | reproducible chaos |
| `infra/java`, `infra/python` | the same infrastructure as two CDK apps from one spec | template parity proven by a diff |

Everything runs **locally**. AWS APIs come from a local emulator, chosen by one setting:

```bash
cp .env.example .env              # KEYEDGE_AWS_TARGET=floci | aws-emulator
make emu-up                       # starts the emulator and refuses to continue unless the account is 000000000000
make emu-smoke                    # one observed check per AWS operation the design relies on
make emu-up TARGET=aws-emulator   # the second emulator (built from its latest package, telemetry off)
```

No phase deploys to a real AWS account.

## Measured results

<!-- bench:begin -->
22 experiments with a hypothesis written before the run: 15 confirmed, 6 refuted, 1 inconclusive ([lab notebook](docs/lab-notebook.md)).

| Bench | Verdict | Finding | Results |
|---|---|---|---|
| vt-vs-reactor | **refuted** | saturation within ±15 % of the Reactor twin: no; p99 within ±20 % at rates ≤ 100 rps: yes | [bench-result.json](results/bench/20261123-165813-vt-vs-reactor/bench-result.json) |
| native-overhead | **refuted** | at 100 rps the edge adds 8.13 ms p50 (limit 2) and 199.38 ms p99 (limit 5) | [bench-result.json](results/bench/20261123-172451-native-overhead/bench-result.json) |
| fargate-size | **refuted** | at 200 rps: native 0.25 vCPU misses, JVM 0.25 vCPU misses, JVM 0.5 vCPU misses the SLO | [bench-result.json](results/bench/20261123-173240-fargate-size/bench-result.json) |

![native vs JVM](docs/media/native-vs-jvm.svg)
<!-- bench:end -->

## Build

Requirements: Docker, a JDK ≥ 17 to start Gradle (Gradle then provisions **Java 25** for its daemon and
the build through toolchains; nothing is installed system-wide), Node 22 (CDK CLI), uv (Python CDK, scripts).

```bash
make lint test        # Spotless, Checkstyle, Error Prone + NullAway, ArchUnit, forbidden terms, fast tests
make help             # every target
```

## License

MIT, see [LICENSE](LICENSE).
