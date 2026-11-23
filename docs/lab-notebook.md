# Lab notebook

Each experiment: a hypothesis written before the run, the measurement, a verdict (`confirmed`,
`refuted`, `inconclusive`), and the result file it comes from. Refuted results stay.

## naive-bucket-race (Phase 003)

**Hypothesis.** A token bucket that reads its two fields, computes, then writes them back (each field
`volatile`, the sequence not atomic) over-admits when two threads race for the last token. The
single-`AtomicLong` CAS limiters (GCRA, packed token bucket) never do, and a permit can be released only once.

**Method.** jcstress, `quick` mode, time frozen so no refill can explain a second admit; two (GCRA: three)
actors race for 1 (GCRA: 2) permits; `true, true` is declared FORBIDDEN for the correct limiters and
INTERESTING for the naive one. `make lab E=naive-bucket-race`.

**Result** ([results/labs/naive-bucket-race.json](../results/labs/naive-bucket-race.json)): verdict
**confirmed**. The naive bucket over-admitted in **12.1 %** of ~197 M samples (23.8 M times both threads got
the single token). The correct limiters and the permit: **0** forbidden outcomes in 268 jcstress runs.

**Interview line.** "Unit tests with threads showed the naive bucket working. jcstress ran ~200 million
interleavings and showed both threads taking the last token one time in eight; the CAS version never did."

## limiter-cost (Phase 003)

**Hypothesis.** A GCRA decision costs well under ~100 ns uncontended; with 16 threads on one key it stays
within ~10× of that; it is within 2× of Bucket4j on the same plan (1 M/s, burst 1 000).

**Method.** JMH 1.37, average time, 2 forks × (3 warm-up + 5 measured) × 1 s, `-prof gc`, Java 25,
on a shared WSL2 host (other stacks running; compare only within this run). `make lab E=limiter-cost`.

**Result** ([results/labs/limiter-cost.json](../results/labs/limiter-cost.json), raw
[results/jmh/2026-11-23-82e68d9/jmh.json](../results/jmh/2026-11-23-82e68d9/jmh.json)): verdict **confirmed**.

| benchmark | ns/op |
|---|---|
| GCRA, 1 thread | 23.6 |
| GCRA, 16 threads, one key | 140.0 (5.9×) |
| Bucket4j, 1 thread / 16 threads | 38.3 / 155.5 |
| packed token bucket, 1 thread / 16 threads | 36.1 / 222.7 |
| 100 000 keys, Caffeine-bounded map | 491.3 |
| 100 000 keys, plain `ConcurrentHashMap` (baseline) | 152.3 |
| fixed / AIMD acquire + release | 15.9 / 16.2 |

**What surprised us.** The per-key map, not the limiter, dominates when keys are many: ~150 ns is the cost of
touching 100 000 objects at random (cache misses), Caffeine's bounded-map bookkeeping adds ~340 ns. A first
guess (`compute()` locking) was wrong: a read-first fast path changed nothing measurable; the plain-map
baseline found the real split.

**Interview line.** "One limiter decision is ~25 ns; under heavy contention on one key ~140 ns. With 100 000
keys the map costs more than the limiter — and I only learnt that by adding a baseline benchmark."

## emulator-parity (Phase 004)

**Hypothesis.** Both emulators pass the AWS contract suite (the DynamoDB, Secrets Manager, SSM, KMS and STS
behaviours keyedge relies on) except a short list of documented differences: TTL timing, KMS ciphertext format,
error shapes.

**Method.** One abstract JUnit suite, two subclasses (Testcontainers: Floci 2.5.0 pinned by digest; AWS Emulator
built from its latest package, port 15298, telemetry off). Each case creates its own resources. `make emu-parity`.

**Result** ([docs/parity.md](parity.md), raw `results/parity/<run>.json`): verdict **confirmed, with fewer
differences than predicted**. All 9 shared contract cases pass on both, including transaction cancellation
reasons per item and KMS encryption-context enforcement (predicted as a likely difference, it was not). The
measured difference is TTL timing (Floci ~1 min sweep, AWS Emulator seconds). The real gaps are runtime
behaviours (ECS, ALB, a CloudFormation detail), tracked as P-02…P-10.

**Interview line.** "I didn't trust the feature lists: the same contract tests run on two emulators every PR,
and the parity page is generated from those runs — including the rows where an emulator is wrong."

## idempotency-race (Phase 005)

**Hypothesis.** Two identical `POST /v1/keys` with the same `Idempotency-Key`, sent at the same moment, create
exactly one key; the other request gets a replay or an in-flight conflict, never a second key.

**Method.** 50 pairs of concurrent calls to the `IssueKey` use case on virtual threads, with the real DynamoDB
adapter, on each emulator. Independent count: the tenant's keys read back through the list index.

**Result** (`results/labs/idempotency-race-{floci,aws-emulator}.json`): verdict **confirmed** on both — 50 keys
stored for 50 pairs; outcomes 50 `Issued` + 50 `InFlight`. The guarantee comes from one `TransactWriteItems`
(key put + idempotency record `IN_FLIGHT → DONE`), whose cancellation reasons say *which* condition failed.

**Interview line.** "The key and 'request done' are written in one transaction, so a key can't exist without its
idempotency record saying so; the cancellation reason index tells an id collision from a lost claim."

## pepper-rotation (Phase 005)

**Hypothesis.** Keys hashed with an old pepper keep verifying after a new pepper becomes current; new keys use
the new pepper; removing the old pepper invalidates exactly the keys that used it.

**Result** (`results/labs/pepper-rotation-{floci,aws-emulator}.json`): **confirmed** on both — old key pepper 1,
new key pepper 2, both `Valid` after rotation, the old key `Unknown` once pepper 1 is retired.

## single-flight (Phase 007)

**Hypothesis.** 500 concurrent requests with one cold key cause exactly **1** verify call to keys.
**Method.** The sidecar's real verdict cache and HTTP verify client against a fake keys server that answers after
100 ms and counts requests (the independent counter); 500 virtual threads released by one latch.
**Result** ([results/labs/single-flight.json](../results/labs/single-flight.json)): **confirmed**, 1 call.

## refresh-ahead (Phase 007)

**Hypothesis.** With refresh at 70 % of the TTL, p99 does not spike when a hot key's entry expires; with
expire-only, every request arriving during the reload waits for it.
**Method.** 200 req/s for 8 s on one key, TTL hint 2 s, verify latency 300 ms; refresh ratio 0.7 vs 1.0.
**Result** ([results/labs/refresh-ahead.json](../results/labs/refresh-ahead.json)): **confirmed on the second
implementation** — p99 0.32 ms with refresh-ahead vs 285 ms expire-only. The **first** run was **refuted**
(p99 233 vs 284 ms): requests that had read the old entry kept re-triggering Caffeine's `refresh()` after each
reload, refreshes cascaded and one turned into a blocking load. Fixed with an explicit refresh: one in-flight
refresh per key (a flag), the reloaded value swapped in as a completed future.
**Interview line.** "The experiment refuted my first refresh-ahead: in-flight readers re-armed the refresh. The
measured fix — one refresh per key, swap in a completed future — took p99 from 285 ms to 0.3 ms."

## vt-handoff (Phase 007)

**Hypothesis.** Moving the sidecar's decision from the calling thread to a virtual thread and back costs
< 0.1 ms median.
**Result** ([results/labs/vt-handoff.json](../results/labs/vt-handoff.json)): **confirmed**, median +0.088 ms,
p99 +0.157 ms over 20 000 calls (close to the threshold on this shared host; one earlier run measured above it).

## control-plane-outage (Phase 008)

**Hypothesis.** With keys down, fail-static keeps admitting warm valid keys (≥ 99 % after their TTL) while
fail-closed rejects them (≤ 1 %); revoked keys are never admitted under either policy.
**Method.** In-process: the real verdict cache, breaker and HTTP verify client against a fake keys server
(its counters are the independent measurement). 300 req/s for 10 s — 80 % over 40 warm keys, 10 % cold keys,
10 % over 5 revoked keys; TTL 2 s, stale window 30 s; keys answers 503 from t = 2 s. Counted after the TTL.
**Result** ([results/labs/control-plane-outage.json](../results/labs/control-plane-outage.json)): **confirmed** —
warm keys 100 % (fail-static, ~1 600 stale answers) vs 0 % (fail-closed); cold keys 0 % under both; revoked keys
admitted 0 times; only 25–26 verify calls reached keys during the outage (the breaker opened). The first run reported
300 revoked admissions: a bug in the **fake** (it revoked one of the five ids), caught because the experiment
counted revoked keys separately. Stack check: `make resilience-check` on both emulators (ADR 0010).

## breaker-vs-none (Phase 008)

**Hypothesis.** With keys hanging, every cold request waits for the 300 ms timeout without a breaker; with one,
p99 < 50 ms once it is open and keys stops receiving calls.
**Method.** 100 req/s for 5 s, every request a never-seen key, keys answers after 5 s.
**Result** ([results/labs/breaker-vs-none.json](../results/labs/breaker-vs-none.json)): **confirmed** — p99 after
the first second 301 ms without vs 0.6 ms with the breaker; 500 vs 50 calls reached keys (max 500 vs 50 in flight
at keys).
**Interview line.** "The breaker is less about our latency than about the control plane: it went from 500 hanging
requests to 50 and then silence, which is what lets it recover."

## adaptive-vs-fixed (Phase 008)

**Hypothesis.** In a brown-out (capacity 800 → 267 req/s) under 600 req/s offered, an adaptive concurrency limit
sheds the excess early and keeps goodput (2xx within 500 ms) ≥ 1.2× a fixed limit of 200.
**Method.** An app with 16 workers, 20 ms service time, 60 ms from t = 3 s; open-model load 600 req/s for 11 s
through each limiter from `libs/ratelimit`; goodput over the 8 s brown-out.
**Result** ([results/labs/adaptive-vs-fixed.json](../results/labs/adaptive-vs-fixed.json)): **confirmed** —
goodput 27/s fixed (p99 774 ms: 200 in flight queue behind 16 workers, Little's law) vs 239/s gradient (p99
187 ms) and 268/s AIMD (p99 468 ms); ratio gradient/fixed 8.9. AIMD reached more goodput but ran close to the
SLO; gradient kept latency low. The edge defaults to gradient.
**Re-run (Phase 010)** with a fourth row, gradient with RTT tolerance 2.0 (the fix from `native-vs-jvm`); the
JSON holds this run: fixed 0/s (p99 807 ms), gradient 225/s (p99 147 ms), AIMD 266/s (p99 464 ms), tolerant
gradient 250/s (p99 340 ms) — still within the SLO, so the tolerance costs no brown-out protection here.
**Interview line.** "A fixed limit of 200 looked generous until capacity fell: Little's law turned it into a
750 ms queue and almost nothing met the SLO. The adaptive limit shrank to what the app could serve."

## fanout-models (Phase 009)

**Hypothesis.** At 10 req/s the structured, futures and Reactor versions of the quote have the same p50 (within
10 %, about the slowest upstream); at 300 req/s all three keep up.
**Method.** The real quotes adapters (JDK `HttpClient`, guards, token provider) against fake upstreams (pricing
20 ms, stock 10 ms, fx 5 ms); open-model load 5 s per row. Functional size only: load curves are Phase 015.
**Result** ([results/labs/fanout-models.json](../results/labs/fanout-models.json)): **confirmed** — p50 at
10 req/s within 5.7 % (23.0–24.3 ms); at 300 req/s no errors and no model ahead (p50 25–28 ms; tails noisy on a
shared host). Discussion in [virtual-threads-vs-reactor.md](virtual-threads-vs-reactor.md#5-evidence).

## cancel-on-failure (Phase 009)

**Hypothesis.** When pricing fails at 50 ms and stock takes 2 s, the structured fan-out returns in < 150 ms and
the stock calls are really cancelled.
**Method.** stock is a raw-socket server that counts clients hanging up before it answers (the independent
measurement); 5 quotes per adapter; plus the deliberately naive `CompletableFuture` version (lab/pitfalls).
**Result** ([results/labs/cancel-on-failure.json](../results/labs/cancel-on-failure.json)): **confirmed** —
structured and futures-with-`submit` return in 64–72 ms (p50) and close all 5 stock connections; the naive
version returns just as fast (58 ms) **but all 5 stock calls run to completion**: "cancelled" on the client, still working on
the server.
**Interview line.** "Both versions looked fast. Only the server's own count showed that the naive one kept every
slow call running."

## retry-only-idempotent (Phase 009)

**Hypothesis.** With stock answering 503, 100 reservations (POST) reach it exactly 100 times; 100 GETs are
retried at most once each and within the retry budget.
**Result** ([results/labs/retry-only-idempotent.json](../results/labs/retry-only-idempotent.json)):
**confirmed** — POST attempts 100; GET attempts 129: the budget (10 + 10 % of 200 calls = 30) granted 29 retries
and then said no, before the 2-attempt limit would have allowed 100.

## token-stampede (Phase 009)

**Hypothesis.** 200 concurrent calls at token expiry cause exactly one token request.
**Method.** Token lifetime 5 s, issue latency 200 ms, refresh-ahead at 80 %; 200 callers at t = 4.9 s (due for
refresh, still valid) and again after the token has expired.
**Result** ([results/labs/token-stampede.json](../results/labs/token-stampede.json)): **confirmed** — 1 and 1
(server-side count). Before that, **jcstress found a double fetch** in the first implementation: a caller that
saw "no token" just before the first fetch completed started a second one. Fixed by reusing a just-completed
fetch whose token is still current.

## native-vs-jvm (Phase 010)

**Hypothesis.** The native edge reaches readiness in < 100 ms with < 1/3 of the JVM edge's RSS at idle and
under 200 req/s.
**Method.** `make lab E=native-vs-jvm` (`lab/native_vs_jvm.py`): the compose stack on Floci with the JVM edge, then
the native one; 5 cold starts each (`docker start` → readiness 200, polled every 10 ms; also Micronaut's own
"Startup completed" time); VmRSS of the edge process; k6 200 req/s for 30 s over 6 keys after a 20 s warm-up.
**Result** ([results/labs/native-vs-jvm.json](../results/labs/native-vs-jvm.json)): **refuted as stated** —
native: 322 ms to ready (median) of which Micronaut itself takes 21–26 ms, the rest is `docker start` and the
readiness probe path; JVM: 3.9 s (Micronaut 1.0–1.6 s). RSS 88 vs 166 MiB idle (53 %), 131 vs 213 MiB after load
(62 %): about half, not a third. Under load the native edge used 12.8 vs 26.5 CPU-seconds, p99 51 vs 146 ms, and
no errors, while the still-warming JVM edge shed 3.6 % with its adaptive limit.
**Found on the way.** The first load runs shed 7–16 % with `503 overloaded` on *both* flavours: the gradient
limiter treated a fan-out's normal latency spread (and the warm-up of a restarted quotes) as congestion and fell
to its minimum. It had no RTT tolerance (Netflix Gradient2 uses 2.0); added (`gradient-tolerance` 2.0, regression
test), and `adaptive-vs-fixed` re-run: tolerant gradient keeps the brown-out benefit (250 goodput/s, p99 340 ms).
**Interview line.** "Native won 10× on start-up and halved memory and CPU — but my '< 100 ms' was measured from
`docker start`, where the container runtime alone costs ~300 ms. Say which clock you mean."
