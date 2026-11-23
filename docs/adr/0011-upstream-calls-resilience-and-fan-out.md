# 11. Upstream calls: fan-out model, resilience order, retries

Date: 2026-11-23 · Status: accepted

## Context
quotes calls three upstreams per request (pricing with an OAuth token, stock, fx) and offers a non-idempotent
reservation. It must answer within a request budget (800 ms), survive a slow or failing upstream, and never
double-book a reservation.

## Decision
- **One port, several concurrency models** (`FanOut`, `libs/fanout`): `StructuredFanOut` (JDK 25 preview
  `StructuredTaskScope` with `Joiner.allUntil(requiredFailed)` and the deadline as the scope timeout) is the
  default; `FutureFanOut` (virtual-thread executor, explicit context re-binding, cancellation through the
  `Future` from `submit`) is the non-preview fallback (`keyedge.quotes.fanout`). Both pass one contract test.
  The Reactor twin is a separate adapter behind its own port.
- **Preview isolation by module + `ServiceLoader`**: only `libs/fanout-structured` is compiled with
  `--enable-preview`; quotes depends on it at runtime only (javac refuses to read preview class files otherwise).
  The quotes JVM runs with `--enable-preview` (`JDK_JAVA_OPTIONS` in its container only).
- **Per-upstream protection, in this order:** bulkhead (no waiting) → circuit breaker → retry. Retry is **inside**
  the breaker so every attempt is recorded; the bulkhead is outermost so a rejected call is not a failure.
  - Retries **only for idempotent calls** (the reservation POST is never retried), at most 3/2/1 attempts for
    pricing/stock/fx, decorrelated jitter (base 50 ms, cap 400 ms), never past the deadline, and within a
    **retry budget** (10 retries + 10 % of calls) so a struggling upstream is not hit three times harder.
  - The breaker ignores client errors (4xx except 429); a longer `Retry-After` wins over the backoff.
  - The retry loop is ours (deadline + budget aware); Resilience4j provides the breaker and bulkhead, and its
    Reactor operators protect the twin with the **same** breaker and bulkhead instances.
- **Required vs optional**: pricing and stock are required (a failure cancels the others and answers 503
  `upstream-unavailable` naming the upstream that failed, not a cancelled sibling); fx is optional (price stays in
  EUR with the warning `FX_UNAVAILABLE`).
- **One token fetch at a time**: `SingleFlightTokenProvider` (CAS'd future, refresh-ahead at 80 % of the token
  lifetime, per-waiter timeout, a 401 invalidates the token for one forced refresh). Proven with jcstress.

## Alternatives
- Retry outside the breaker (one breaker record per logical call): hides how often the upstream actually fails.
- Resilience4j `Retry`: no notion of a request deadline or a shared budget.
- Preview flag on the whole quotes module: simpler build, but every class would need a preview JVM forever.

## Consequences
- A reservation that times out is reported as failed even if the upstream booked it; the request id travels to
  the upstream so duplicates can be reconciled there (no client-side retry hides it).
- The preview API will change; it is confined to one 90-line class behind a port.

## Evidence
`results/labs/cancel-on-failure.json`, `retry-only-idempotent.json`, `token-stampede.json`, `fanout-models.json`;
`QuotesApiTest` (parity, never-retried reservation), `GuardTest`, `FanOutContract`, jcstress `TokenFetchStress`.
