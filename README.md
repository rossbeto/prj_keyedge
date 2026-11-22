# keyedge

> **Lab project.** A portfolio lab that answers one question with code, tests and measurements:
> *how do you issue, store and rotate API keys safely, and how does a small sidecar next to every
> service authenticate, rate-limit and protect it without becoming the outage itself?*

**Status:** Phase 005 of 16: libraries, the AWS target switch with a two-emulator contract suite ([parity](docs/parity.md)), and the **keys** control plane + a local JWT issuer.

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

## Build

Requirements: Docker, a JDK ≥ 17 to start Gradle (Gradle then provisions **Java 25** for its daemon and
the build through toolchains; nothing is installed system-wide), Node 22 (CDK CLI), uv (Python CDK, scripts).

```bash
make lint test        # Spotless, Checkstyle, Error Prone + NullAway, ArchUnit, forbidden terms, fast tests
make help             # every target
```

## License

MIT, see [LICENSE](LICENSE).
