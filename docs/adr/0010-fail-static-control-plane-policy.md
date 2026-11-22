# 10. Fail-static when the control plane cannot answer

Date: 2026-11-23 · Status: accepted

## Context
The edge sidecar sits on every request path. Each request needs a verdict from keys (the control plane), cached for
its TTL hint (ADR 0009). If keys is down or slow, the sidecar has three options for a key whose cached verdict has
expired: let everything through (fail-open), reject everything (fail-closed), or keep using the last known good
verdict for a bounded time (fail-static). With fail-closed, a control-plane outage becomes a full outage of every
product behind a sidecar once the TTL (60 s) has passed.

## Options
| Option | Availability during a keys outage | Security cost |
|---|---|---|
| fail-open | full | any string with a valid checksum is admitted: unacceptable |
| fail-closed | warm keys until their TTL, then nothing | none beyond the TTL |
| **fail-static** | warm keys for TTL + stale window; cold keys 503 | a key revoked during the outage keeps working up to TTL + stale window |

## Decision
- **Fail-static by default**, `keyedge.edge.resilience.failure-policy: FAIL_STATIC | FAIL_CLOSED`.
- Only a **Valid** verdict is ever served stale, and only while `now < expireAt + staleWindow` (default 5 min).
  Revoked, expired, rotated-out, unknown and malformed verdicts are never turned into an admission
  (`StaleDecision`, property-tested over every policy × verdict kind × time).
- A key the sidecar has never seen gets **503 `upstream-unavailable` + `Retry-After`**, not 401: the key may be
  valid and the client should retry. `Retry-After` is the breaker's remaining open time (at least 1 s).
- The app is told: a stale admission carries `X-Keyedge-Stale: 1` (inbound copies are stripped, ADR 0009).
- Verify is wrapped in a **bulkhead** (64 concurrent, no waiting) outside a **circuit breaker** (count window 50,
  20 calls minimum, 50 % failures or 80 % slow calls > 500 ms, open 10 s, 5 half-open probes), D-20. A 4xx from
  keys is a contract bug on our side, not an outage, and does not count.
- **Time limit = the HTTP client's own request timeout** (`keyedge.edge.verify-timeout`, 1 s). Resilience4j's
  `TimeLimiter` needs a future and a second thread to interrupt the first; on virtual threads the blocking call
  with its own timeout is simpler and cancels the request for real. **No retry** on verify: the cache's single
  flight already collapses concurrent misses, and retries multiply load on a struggling control plane.
- Expired entries revalidate with one shared reload per key (single flight); the old Valid stays in the cache for
  the stale window so it is there to fall back on.

## Consequences
- **Revocation latency, worst case = TTL + stale window** (60 s + 5 min by default) during a control-plane outage;
  = TTL otherwise. Threat: a key stolen and revoked during an outage keeps working at sidecars that have it warm
  for up to that bound. Mitigations: a shorter stale window for high-risk scopes, and an operator kill switch that
  forces fail-closed (both BACKLOG B-15, B-16); FAIL_CLOSED is one configuration value away.
- Cold keys fail during an outage under both policies: fail-static only protects traffic that was already flowing.
- With the breaker open, keys receives no verify traffic from the sidecar except half-open probes, so it can
  recover instead of being hammered by retries from every task.

## Evidence
- `results/labs/control-plane-outage.json`: warm keys admitted 100 % after their TTL under fail-static, 0 % under
  fail-closed; revoked keys admitted 0 times under both; 25–26 verify calls reached keys while down (the breaker).
- `results/labs/breaker-vs-none.json`: with keys hanging, p99 301 ms (= timeout) without the breaker, 0.6 ms after
  it opened; 500 vs 50 calls reached keys.
- `make resilience-check` on the compose stack (Toxiproxy `keys` proxy disabled), both `TARGET=floci` and
  `TARGET=aws-emulator`: warm key 200 after its TTL (stale), cold key 503 + Retry-After; with
  `KEYEDGE_FAILURE_POLICY=FAIL_CLOSED` the expired warm key gets 503.
