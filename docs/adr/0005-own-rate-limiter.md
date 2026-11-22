# 5. Own lock-free rate limiters (GCRA, packed token bucket), Bucket4j as the baseline

Date: 2026-11-23 · Status: accepted

## Context
The sidecar decides on every request. The reference systems used a hand-written token bucket whose
refill-then-take sequence raced, and fixed semaphores chosen without measurement.

## Options
1. Bucket4j (mature, well tested, many backends).
2. Own GCRA and token bucket, each one `AtomicLong` per key with a CAS loop, proven with jcstress.
3. A shared store (Redis/Valkey + Lua) for cluster-wide limits.

## Decision
Option 2 in this lab, with Bucket4j measured side by side (JMH, same plan). Owning ~150 lines is the
point of the exercise: the correctness proof (jcstress), the packing ranges and the time-wrap handling
are the skills on display. **In a product, choose Bucket4j** unless the per-decision cost or the
dependency matters and the team can own the concurrency proof.

Local limits are per sidecar task (effective limit = tasks × rate); a shared store is BACKLOG B-01.

## Consequences
- jcstress runs in CI (quick mode) and a deliberately wrong bucket proves the harness can see the race.
- Ranges are validated in constructors (burst ≤ 4 194, rate ≤ 1 000 000/s for the packed bucket).
