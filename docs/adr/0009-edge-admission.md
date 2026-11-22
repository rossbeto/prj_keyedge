# 9. The sidecar's admission path and its trade-offs

Date: 2026-11-23 · Status: accepted

## Decision
- Order of checks, cheapest first: route exposed (deny by default) → key format and checksum (no I/O) → verdict from
  a cache keyed by key id **and** secret → scope → per-key GCRA limit. Expected outcomes are values (sealed
  `Admission`).
- Verdict cache: one load per key at a time (single flight), a timeout per waiter, per-entry expiry from the
  control plane's TTL hint, refresh-ahead at 70 % of the TTL with one refresh per key, rejections cached 5 s and never
  refreshed.
- The decision runs on a virtual thread; forwarding streams bytes with `ProxyHttpClient` (Reactor only at that
  boundary). The app receives a signed principal, never the key; inbound `X-Keyedge-*` headers are stripped.
- Graceful shutdown: readiness DOWN → 5 s grace → drain up to 20 s → final usage flush (Micronaut's
  `GracefulShutdownCapable`).

## Consequences
- **Revocation latency is bounded, not zero:** a revoked key keeps working at a sidecar until its cached verdict
  expires (≤ the TTL hint, 60 s by default). Shorter TTLs cost more verify calls; push-invalidation is BACKLOG.
- Rate limits are per sidecar task (effective limit = tasks × plan).
- Usage metering is best-effort and bounded: windows are merged while keys is unreachable and the oldest are dropped
  and counted beyond 60 windows.
