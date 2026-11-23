# keyedge runbook

Every query below runs against the local stack: `make up && make observe` (Grafana 127.0.0.1:15030, Prometheus
127.0.0.1:15090, Jaeger 127.0.0.1:15686). Dashboards: *keyedge / edge sidecar*, *keyedge / quotes fan-out*,
*keyedge / keys control plane*. Logs are JSON lines with `trace_id`, `span_id` (and `request_id`, `tenant` in keys
and quotes): `docker logs keyedge-edge-1 | jq 'select(.level=="ERROR")'`. API-key secrets never appear in logs
(`[redacted]`); key ids do.

---

## 1. Clients get 503 from the edge

**Panel:** edge → *Admissions by result*, *Concurrency gate*.

```promql
sum by (result) (rate(keyedge_edge_admission_total{result=~"upstream_unavailable|overloaded"}[1m]))   # which 503?
rate(keyedge_edge_shed_total[1m])                                    # overloaded: the concurrency gate shed
keyedge_edge_concurrency_limit  and  keyedge_edge_inflight           # limit pinned at its minimum (10)?
max by (state) (keyedge_verify_breaker_state)                        # upstream-unavailable: breaker OPEN?
```

| Result | Meaning | Likely causes | Fix |
|---|---|---|---|
| `upstream_unavailable` | keys could not verify a key the edge has not seen (or not within its stale window) | keys down / slow; breaker open | see §2 and §4; clients retry after `Retry-After` |
| `overloaded` | the adaptive limit shed the request before any work | the app behind the edge slowed down (brown-out); a JVM still warming up after a restart | check quotes p99 (§ quotes dashboard); the limit recovers when latency does. A limit stuck at its minimum with healthy latency means the gradient tolerance is too strict (`keyedge.edge.concurrency.gradient-tolerance`, default 2.0) |

**Trace:** Jaeger → service `keyedge-edge`, tag `keyedge.admission=overloaded` or `upstream-unavailable` (spans use the problem slug, metrics the enum name).

**Experiment that reproduces it:** `make lab E=adaptive-vs-fixed` (brown-out), `make chaos S=keys-down` (control plane).

---

## 2. The edge is serving stale verdicts

**Panel:** edge → *Fail-static: stale answers*, *Verify calls to keys by kind/outcome*.

```promql
rate(keyedge_verify_cache_stale_served_total[1m])                                  # > 0 only while keys is down
sum by (kind, outcome) (rate(keyedge_verify_cache_loads_seconds_count{outcome="unavailable"}[1m]))
sum by (result) (rate(keyedge_edge_admission_total{result="admitted_stale"}[1m]))
```

**What it means:** keys cannot answer; keys that were valid recently keep working up to their TTL + stale window
(5 min); never-seen keys get 503 (ADR 0010). **Revocations made now take effect only when keys is back.**

**Trace:** server spans carry `keyedge.verify.stale=true`; the app sees `X-Keyedge-Stale: 1`.
**Act:** restore keys (§4). To stop serving stale immediately: `KEYEDGE_FAILURE_POLICY=FAIL_CLOSED make up`
(an operator kill switch via SSM is BACKLOG B-16).
**Reproduce:** `make resilience-check`.

---

## 3. Pinned virtual threads (latency spikes with idle CPU)

**Symptom:** request latency climbs while CPU stays low; thread dumps show few carriers busy.

```bash
make jfr S=quotes D=60s            # recording with deploy/jfr/keyedge.jfc (pinning threshold 20 ms)
JFR=$(ls -d ~/.gradle/jdks/eclipse_adoptium-25*/bin/jfr | head -1)
$JFR print --events jdk.VirtualThreadPinned results/jfr/quotes-*.jfr | head -50   # stack of the pinning frame
$JFR summary results/jfr/quotes-*.jfr | grep -E "Pinned|SubmitFailed"
```

**Likely causes on Java 25:** native frames (JNI, some crypto/compression libraries), class initialisation that
blocks, `Object.wait` inside native code. `synchronized` itself no longer pins (JEP 491) — measured: Java 21 pinned
1 005 times and ran 102× slower on the same code (`results/labs/pinning.json`). **Fix:** move the blocking call
out of the native frame / initialiser, or give that work a bounded platform-thread executor.

---

## 4. The verify breaker is flapping

**Panel:** edge → *Verify breaker state*, *Verify calls to keys by kind/outcome*; keys dashboard p99.

```promql
changes(keyedge_verify_breaker_state{state="OPEN"}[10m])          # many transitions = flapping
histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket{job="keys",uri="/internal/v1/verify"}[1m])))
sum by (outcome) (rate(keyedge_verify_cache_loads_seconds_count[1m]))
```

**Meaning:** the breaker opens on ≥ 50 % failures or ≥ 80 % slow calls (> 500 ms) in its last 50 calls, probes
after 10 s with 5 calls. Flapping = keys answers some probes in time and then slows down again under load.
**Likely causes:** keys' DynamoDB latency (check keys p99), Toxiproxy toxics left on (`scripts/toxics.sh list`),
a too-tight `keyedge.edge.verify-timeout`.
**Trace:** Jaeger → `keyedge-keys` operation `POST /internal/v1/verify`, sort by duration.
**JFR:** `make jfr S=edge D=60s` → `dev.keyedge.VerifyCacheMiss` events (key id, kind, outcome, load time).
**Act:** clear toxics (`make chaos-reset`); fix keys' latency; widen the slow-call threshold only with evidence.

---

## Useful commands

```bash
make observe                                   # traces + metrics for the running stack
curl -s 127.0.0.1:15090/api/v1/targets | jq '.data.activeTargets[] | {job: .labels.job, health}'
make jfr S=edge|quotes|keys D=60s               # JFR (JVM images; native: see ADR 0013)
make chaos S=keys-down | keys-slow | keys-flaky; make chaos-reset
docker logs keyedge-quotes-1 | jq -c 'select(.trace_id=="<id from Jaeger>")'   # logs of one trace
```

---

## 5. "CREATE_COMPLETE" but the resource is not there (emulator stub trap)

**Symptom:** a stack deployed fine on an emulator, the service then fails with "not found".
**Check:** `scripts/cdk-verify.sh <stack>` (in `. <(scripts/aws-env.sh <target>)`): every resource is described by
its own service API; a stub shows as `stubbed: <type>`. `make cdk-stub-demo` shows both halves.
**Cause:** an emulator configured to stub unsupported CloudFormation types (Floci
`FLOCI_SERVICES_CLOUDFORMATION_ALLOW_STUB_UNSUPPORTED_RESOURCE_TYPES=true`). keyedge keeps it off.
**Fix:** keep stubbing off; if the type is needed, record it as a parity row and test that part elsewhere.

## 6. SOPS cannot decrypt after an emulator restart

The KMS key in an emulator lives in memory: after a restart the file's KMS recipient points at a key that no
longer exists. The age recipient still works: `make secrets-decrypt-check VIA=age`. Re-add the new KMS key:
`make cdk-deploy && make secrets-rekey && make secrets-decrypt-check VIA=kms`.

## 7. Emulated ECS: the task runs but the edge answers 500 "Connection refused" to the app

Symptom (Phase 014): `make ecs-smoke` gets 500 `urn:keyedge:problem:internal`; the edge log shows
`Connect Error: Connection refused: /10.40.x.y:15081`. The edge used the address from the task metadata, which on
Floci is the *first container's* VPC address, not the app's. Check: `KEYEDGE_APP_URL` must be `ecs:quotes:15081`
(not an IP), and the edge image must contain the Docker-id resolution (`make native S=edge`). Then:
`docker run --rm --network keyedge_default alpine getent hosts <first 12 chars of the quotes DockerId>` resolves.
Task containers left behind after a failed run: `make ecs-down` (never remove containers by name pattern; other
projects share the daemon).
