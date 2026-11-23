# 13. Observability: traces, metrics, logs and JFR without telemetry in the core

Date: 2026-11-23 · Status: accepted

## Context
A request crosses the edge sidecar, quotes and three upstreams, on virtual threads, structured forks and a
Reactor pipeline. An operator must be able to follow one request end to end, see the sidecar's decisions in
aggregate, read logs that belong to a request, and profile the JVM — without the domain and use cases importing
any telemetry library (masterplan §3.3).

## Decision
- **Traces:** Micronaut tracing (OpenTelemetry) in every service, exporters off unless the observe profile sets
  `OTEL_TRACES_EXPORTER=otlp` (Jaeger v2 receives OTLP directly; no separate collector locally). W3C
  `traceparent` everywhere.
  - **Edge:** the filter runs after Micronaut's TRACING phase, reads the server span's context from Micronaut's
    propagated context, tags it (`keyedge.admission`, `keyedge.key_id`, `keyedge.verify.stale`) and wraps the
    forward to the app in a CLIENT span whose `traceparent` it injects (inbound trace headers are stripped).
    The decision runs on a virtual thread, where no ambient context exists, so all of this is explicit.
  - **Quotes:** the JDK `HttpClient` is not instrumented, so an `UpstreamTelemetry` port starts a CLIENT span per
    upstream call and injects its context. OpenTelemetry's context is `ThreadLocal`-based: **neither executor
    tasks nor structured forks inherit it** (unlike `ScopedValue`s; `OtelContextInForksTest` shows both), so the
    `ContextCarrier` now runs for the structured adapter too. The Reactor twin captures the context on the
    request thread and passes it down explicitly.
- **Metrics:** Micrometer + Prometheus (`/prometheus` on each service, not proxied by the edge). Names under
  `keyedge_`; tags bounded (results, outcomes, upstreams, states) — never key ids, tenants or SKUs
  (`metricsHaveBoundedTagsAndCountAdmissions`). The core reports through ports (`AdmissionObserver`,
  `VerifyObserver`, `UpstreamTelemetry`); gauges and function counters read state components already keep.
  Prometheus runs in the edge task's network namespace (quotes listens on 127.0.0.1 only), like a per-task
  metrics sidecar. Grafana dashboards are generated (`scripts/build_dashboards.py`) and checked for drift.
- **Logs:** one JSON line per event from `libs/observe`'s `JsonLineEncoder` (no JSON library, native-safe):
  `trace_id`/`span_id` from the current span, `request_id`/`tenant` from each service's `ScopedValue`, read **at
  log time** through registered suppliers — no MDC put/remove pairs, nothing to leak to the next request on a
  pooled thread (`scoped-vs-threadlocal`: 7 leaks of 20 via ThreadLocal, 0 via ScopedValue). Messages and stack
  traces pass a `Redactor` (API-key secret parts, bearer/basic credentials).
- **JFR:** custom events `dev.keyedge.RateLimitDecision`, `VerifyCacheMiss`, `FanOut` (`shouldCommit`-guarded,
  key ids allowed — recordings stay with the operator); `deploy/jfr/keyedge.jfc` generated with `jfr configure`
  from `default` (+ our events, pinned VTs ≥ 20 ms); `make jfr S=<svc> D=60s` starts the recording on the
  service's command line (the distroless JRE has no `jcmd`, and `JDK_JAVA_OPTIONS` would also start a recording
  in every health-check JVM).
- ArchUnit: `io.opentelemetry..`, `io.micrometer..`, `jdk.jfr..` count as framework packages: forbidden in
  domain, app and ports.

## Consequences
- The edge's verify calls to keys are not linked to request traces (they are shared single-flight loads); they
  are visible as metrics and JFR events instead.
- Every telemetry library added to a service must be re-checked in the native images (see Evidence).
- Local only: no CloudWatch/X-Ray export (BACKLOG); alarms come with the CDK ObservabilityStack (Phase 012).

## Evidence
Jaeger: one trace `edge GET → forward GET → quotes GET /v1/quotes/{sku} → GET pricing|stock|fx → upstream-sim`;
`TracePropagationTest` (structured, futures, Reactor), `EdgeFilterTest.theForwardedRequestContinuesTheCallersTrace`,
`JfrEventsTest`, `JsonLineEncoderTest`, `results/labs/pinning.json`, `results/labs/scoped-vs-threadlocal.json`,
`docs/runbook.md`.
