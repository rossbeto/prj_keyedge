# A native sidecar: what it costs and saves

*Draft. From the keyedge lab; every number links to its results file.*

The edge sidecar runs next to every task, so its start-up and memory are paid many times. We built it as a JVM
image and as a GraalVM native image and measured both.

## What it saves

| | JVM | Native |
|---|---|---|
| start to ready (median of 5) | 3 855 ms | 322 ms |
| RSS idle | 166 MiB | 88 MiB |
| p99 at 200 req/s | 146 ms | 51 ms |

Source: [results](../../results/labs/native-vs-jvm.json), [chart](../media/native-vs-jvm.svg). The hypothesis
written before the run ("ready in < 100 ms, under a third of the JVM's memory") was **refuted as stated**: 322 ms
and about half. It is still a 12× faster start, and the tail latency under load was better too.

## What it costs

- **Build:** minutes per service and several GB of memory; native builds run on tags, not every PR.
- **Reflection metadata:** recorded with the tracing agent and curated. One miss shipped: the native images
  silently dropped every log line (the custom log encoder was instantiated by reflection), and nobody noticed until
  the ECS task exited with no logs.
- **The hop itself is cheap; the limiter in it is not:** at 50 req/s the native edge added about 1.6 ms p50 and
  1.3 ms p99 over calling the app directly; at 100 req/s on a busy host its adaptive limiter read latency noise as
  overload and shed, adding 200 ms at p99 ([results](../../results/bench/20261123-172451-native-overhead/bench-result.json)).
- **Headroom:** at 0.25 vCPU the native edge did not hold 200 req/s
  ([results](../../results/bench/20261123-173240-fargate-size/bench-result.json)). Native makes the sidecar
  cheaper, not free.
