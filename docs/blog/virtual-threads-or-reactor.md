# Virtual threads or Reactor? We built both

*Draft. From the keyedge lab; every number links to its results file.*

One quote = three upstream calls (pricing, stock, fx) under a deadline. keyedge implements it three ways on Java 25:
`StructuredTaskScope` (preview), futures on virtual threads, and a Reactor twin, with the same resilience policies
(timeouts, retries only for idempotent calls, bulkheads).

## At low load, they are the same

At 10 req/s the three models had the same p50 within 10 %, all close to the slowest upstream
([results](../../results/labs/fanout-models.json)). Cancellation is where they differ in code: when pricing fails,
the structured scope and the futures adapter close the slow stock call; a naive `CompletableFuture` version returns
just as fast but leaves every slow call running on the server ([results](../../results/labs/cancel-on-failure.json)).

## Under load, we predicted a tie and measured a gap

Before the run we wrote down: CPU-bound, about 1 600 req/s for all three (Little's Law with the measured CPU per
request). We offered constant arrival rates from 100 to 1 200 req/s straight to the app (the sidecar's own limiter
would otherwise decide the result; a first run through it showed exactly that), three repetitions each.

The verdict rule said **refuted**: at 400 req/s the structured adapter already failed the error SLO in every
repetition, while the futures adapter and the Reactor twin had none; at 600 req/s the Reactor twin stayed at
≤ 0.2 % errors and both virtual-thread adapters failed by an order of magnitude more
([results](../../results/bench/20261123-165813-vt-vs-reactor/bench-result.json),
[notebook](../lab-notebook.md#vt-vs-reactor-phase-015)).

The errors were not CPU. They were our own **bulkheads** (50 in flight for pricing, 20 for fx): on a busy host every
call takes longer, in-flight work grows with it, and the caps reject first. The likely reason the Reactor twin did
better: it releases a permit on the event loop the moment the response arrives, while a virtual thread releases it
only when it is scheduled again.

## What this does and does not say

It says: on this machine, with these bulkheads, the event-loop version degraded last; and the limit you hit first is
the narrowest one you configured, so size bulkheads from latency *under load*. It does not say Reactor is faster in
general: one JVM, one host with other projects running, simulated upstreams. The code that is easiest to read and
debug was the structured version, and at the rates most services see, all three were equal.
