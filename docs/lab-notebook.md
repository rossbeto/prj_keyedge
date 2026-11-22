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
