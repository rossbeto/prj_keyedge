# Architecture (4+1 views)

keyedge is a lab: one control plane, one sidecar, one demo app, a simulator for the outside world, and the AWS
footprint as code. This page is the map; the decisions are in [adr/](adr/), the measurements in the
[lab notebook](lab-notebook.md).

## Scenarios (the "+1")

1. **Issue a key.** A tenant admin with an OAuth token (local issuer) calls `POST /v1/keys` with an
   `Idempotency-Key`. keys stores the HMAC of the secret, returns the secret once.
2. **Use a key.** A client calls the app through the edge with `Authorization: Bearer ke_…`. The edge checks the
   format and checksum locally, takes a cached verdict or verifies once (single flight), rate-limits per key,
   signs the principal for the app and forwards.
3. **Revoke or rotate.** `POST /v1/keys/{id}/revoke` takes effect at each sidecar within the verdict TTL (60 s);
   rotation keeps the old secret valid for an overlap window.
4. **Control plane down.** The edge's breaker opens, warm keys are served from the fail-static cache for up to the
   stale window, cold keys are refused.
5. **Deploy.** The CDK app (Java or Python, identical templates) deploys data/config stacks to an emulator, and the
   service stack runs edge + app as one ECS task (Floci).

## Logical view

| Module | Role | Main types |
|---|---|---|
| `libs/keyformat` | key syntax, checksum, hashing, principal header | `KeyFormat`, `KeyId`, `SecretHasher`, `PrincipalHeader` |
| `libs/ratelimit` | GCRA and packed token bucket on one `AtomicLong`, keyed limiter, concurrency limiters | `Gcra`, `PackedTokenBucket`, `KeyedLimiters`, `GradientLimiter` |
| `libs/problem` (+ `-micronaut`) | RFC 9457 problem documents only | `Problem`, `ProblemType` |
| `libs/awstarget` | the one switch between Floci, AWS Emulator and (guarded) AWS; the SSM contract | `AwsTargets`, `ServiceContract` |
| `libs/fanout` (+ `-structured`) | fan-out port; futures and `StructuredTaskScope` adapters | `FanOut` |
| `libs/observe` | JSON log lines with redaction, log fields, JFR events | `JsonLineEncoder`, `Redactor` |
| `services/keys` | control plane: tenants, keys, usage, idempotency | `IssueKey`, `VerifyKey`, `DynamoKeys` |
| `services/edge` | sidecar: admission, verdict cache, breaker, limiter, drain | `EdgeFilter`, `CaffeineVerdicts`, `EdgeDrain` |
| `services/quotes` | demo app: three upstream calls, three concurrency styles | `GetQuote`, `ReactorQuoteFlow` |
| `services/upstream-sim`, `services/issuer` | seeded flaky upstreams; a local OAuth issuer | `Scenario`, `IssueToken` |
| `infra/java`, `infra/python`, `infra/spec` | the AWS footprint, one spec, two languages | `ServiceStack`, `SidecarTaskDefinition` |
| `bench` | predictions, open-model load, results schema, generated docs | `RunScenario`, `Hypotheses` |

Every service is layered `domain → app → adapters → api/wiring`, checked by ArchUnit (`*ArchitectureTest`): the
domain imports no framework, adapters never call each other, wiring is the only place that knows everything.

## Process view

- **keys:** Micronaut on virtual threads; DynamoDB transactions for create + idempotency record; usage is
  aggregated by the edge and flushed in batches.
- **edge:** Netty event loop for I/O; the admission decision hops to a virtual thread; verify calls are shared
  (single flight) and refreshed ahead of expiry; a gradient limiter caps in-flight requests to the app; on shutdown
  readiness goes DOWN, then grace, then drain ([ADR 0009](adr/0009-edge-admission.md)).
- **quotes:** one request = three upstream calls under a deadline; structured scope, futures or Reactor
  ([ADR 0011](adr/0011-upstream-calls-resilience-and-fan-out.md),
  [virtual-threads-vs-reactor.md](virtual-threads-vs-reactor.md)).
- Context (tenant, trace) travels by `ScopedValue` inside a service and by headers between services
  ([ADR 0013](adr/0013-observability.md)).

## Development view

Gradle 9 multi-project with convention plugins in `build-logic/` (quality gates on every compile: Error Prone,
NullAway, Spotless, Checkstyle; JaCoCo gates; PIT, jcstress, JMH where they matter). `make` is the only entry point,
locally and in CI. Results of experiments are files under `results/`, and the docs that quote them are generated or
checked against them (`make report`, `make docs-check`).

## Physical view

| Where | What runs |
|---|---|
| `make up` (compose) | emulator, issuer, keys, upstream-sim, Toxiproxy, edge + quotes sharing one network namespace (like an awsvpc task) |
| `make up FLAVOR=native` | the same with native images (edge, quotes, keys) |
| `make observe` | plus Jaeger, Prometheus (in the edge's namespace), Grafana |
| `make ecs-up && make ecs-deploy` | Floci with the Docker socket: the CDK service stack as a real two-container task behind an emulated ALB ([ADR 0018](adr/0018-sidecar-finds-the-app-through-task-metadata.md)) |
| CDK `prodlike` mode | synthesised and nag-checked only: private subnets, NAT, HTTPS ALB, flow logs ([infra.md](infra.md)) |

All ports bind `127.0.0.1:15xxx`. No real AWS account is used; the `aws` target refuses to run without an explicit
opt-in and an account check ([ADR 0002](adr/0002-two-local-aws-emulators.md),
[ADR 0015](adr/0015-bootstrap-less-deploys-to-emulators.md)).
