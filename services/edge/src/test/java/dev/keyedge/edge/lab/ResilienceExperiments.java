package dev.keyedge.edge.lab;

import static dev.keyedge.edge.lab.LabKit.breaker;
import static dev.keyedge.edge.lab.LabKit.openLoad;
import static dev.keyedge.edge.lab.LabKit.pct;
import static dev.keyedge.edge.lab.LabKit.rate;
import static dev.keyedge.edge.lab.LabKit.write;
import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.edge.adapters.cache.CaffeineVerdicts;
import dev.keyedge.edge.domain.FailurePolicy;
import dev.keyedge.edge.lab.LabKit.Keys;
import dev.keyedge.edge.ports.Verdicts;
import dev.keyedge.edge.ports.VerifyClient;
import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.KeySecret;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Phase 008 notebook experiments on the control plane, in-process: the real cache, breaker and HTTP
 * verify client against a fake keys server whose own counters are the independent measurement.
 * Shortened versions of the plan's stack-level runs (Phase 008 deviations); the stack-level check
 * is {@code make resilience-check}. Verdicts are ratios, written to results/labs.
 */
@Tag("experiment")
class ResilienceExperiments {
  // ---- control-plane-outage --------------------------------------------------------------------

  /** Per-category outcome counters, counted only after the warm verdicts' TTL has passed. */
  private static final class Tally {
    private final long[][] counts = new long[3][2]; // [category][0 = total, 1 = admitted]
    private final AtomicInteger revokedAdmitted = new AtomicInteger();

    synchronized void add(int category, boolean admitted, boolean afterTtl) {
      if (category == REVOKED && admitted) {
        revokedAdmitted.incrementAndGet();
      }
      if (afterTtl) {
        counts[category][0]++;
        counts[category][1] += admitted ? 1 : 0;
      }
    }

    synchronized double admittedRate(int category) {
      return rate(counts[category][1], counts[category][0]);
    }
  }

  private static final int WARM = 0;
  private static final int COLD = 1;
  private static final int REVOKED = 2;

  /** 80 % warm, 10 % cold (never seen), 10 % revoked. */
  private static KeyId keyFor(int n, int category) {
    return switch (category) {
      case WARM -> new KeyId(1, n % 40);
      case COLD -> new KeyId(2, n);
      default -> new KeyId(1, 900 + n % 5);
    };
  }

  private static boolean admitted(CaffeineVerdicts cache, KeyId id, KeySecret secret) {
    try {
      return cache.answer(id, secret).check() instanceof KeyCheck.Valid;
    } catch (Verdicts.Unavailable e) {
      return false;
    }
  }

  private static Map<String, Object> outage(FailurePolicy policy) throws Exception {
    try (var keys = new Keys(2);
        var vt = Executors.newVirtualThreadPerTaskExecutor()) {
      var cache =
          new CaffeineVerdicts(
              breaker(keys.http(vt, Duration.ofMillis(300))),
              Instant::now,
              vt,
              10_000,
              Duration.ofSeconds(1),
              Duration.ofSeconds(5),
              0.7,
              new CaffeineVerdicts.Staleness(policy, Duration.ofSeconds(30)));
      var secret = KeySecret.ofBytes(new byte[32]);
      for (int i = 0; i < 40; i++) { // warm 40 valid keys and 5 revoked ones
        cache.answer(new KeyId(1, i), secret);
      }
      for (int i = 0; i < 5; i++) {
        cache.answer(new KeyId(1, 900 + i), secret);
      }
      long downAt = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      long afterTtl = downAt + Duration.ofSeconds(3).toNanos(); // TTL 2 s + 1 s margin
      var tally = new Tally();
      openLoad(
          vt,
          300,
          10,
          n -> {
            long now = System.nanoTime();
            keys.down |= now >= downAt;
            int category = n % 10 < 8 ? WARM : n % 10 == 8 ? COLD : REVOKED;
            tally.add(category, admitted(cache, keyFor(n, category), secret), now >= afterTtl);
          });
      var r = new LinkedHashMap<String, Object>();
      r.put("policy", policy.name());
      r.put("warm_admitted_rate_after_ttl", tally.admittedRate(WARM));
      r.put("cold_admitted_rate_after_ttl", tally.admittedRate(COLD));
      r.put("revoked_admitted_total", tally.revokedAdmitted.get());
      r.put("stale_served", cache.staleServed());
      r.put("verify_calls_reaching_keys_while_down", keys.callsWhileDown.get());
      return r;
    }
  }

  @Test
  void controlPlaneOutage() throws Exception {
    var failStatic = outage(FailurePolicy.FAIL_STATIC);
    var failClosed = outage(FailurePolicy.FAIL_CLOSED);
    double staticWarm = (double) failStatic.get("warm_admitted_rate_after_ttl");
    double closedWarm = (double) failClosed.get("warm_admitted_rate_after_ttl");
    boolean confirmed =
        staticWarm >= 0.99
            && closedWarm <= 0.01
            && (int) failStatic.get("revoked_admitted_total") == 0
            && (int) failClosed.get("revoked_admitted_total") == 0;
    var result = new LinkedHashMap<String, Object>();
    result.put("experiment", "control-plane-outage");
    result.put(
        "hypothesis",
        "with keys down, fail-static keeps admitting warm valid keys (>= 99 % after their TTL) while"
            + " fail-closed rejects them (<= 1 %); revoked keys are never admitted under either");
    result.put(
        "setup",
        "300 req/s for 10 s: 80 % over 40 warm keys, 10 % cold, 10 % over 5 revoked; TTL 2 s,"
            + " stale window 30 s; keys answers 503 from t=2 s; breaker 20 calls / 50 %");
    result.put("fail_static", failStatic);
    result.put("fail_closed", failClosed);
    result.put("verdict", confirmed ? "confirmed" : "refuted");
    write("control-plane-outage", result);
    assertThat(confirmed).as(result.toString()).isTrue();
  }

  // ---- breaker-vs-none -------------------------------------------------------------------------

  private static Map<String, Object> slowKeys(boolean withBreaker) throws Exception {
    try (var keys = new Keys(60);
        var vt = Executors.newVirtualThreadPerTaskExecutor()) {
      keys.delayMs = 5_000; // far beyond the 300 ms client timeout
      VerifyClient http = keys.http(vt, Duration.ofMillis(300));
      VerifyClient client = withBreaker ? breaker(http) : http;
      var cache =
          new CaffeineVerdicts(
              client, Instant::now, vt, 10_000, Duration.ofSeconds(1), Duration.ofSeconds(5), 0.7);
      var secret = KeySecret.ofBytes(new byte[32]);
      var late = new ConcurrentLinkedQueue<Long>();
      var all = new ConcurrentLinkedQueue<Long>();
      long settleAt = System.nanoTime() + Duration.ofSeconds(1).toNanos();
      openLoad(
          vt,
          100,
          5,
          n -> {
            long s = System.nanoTime();
            try {
              cache.answer(new KeyId(3, n), secret); // cold keys: every request needs keys
            } catch (Verdicts.Unavailable e) {
              // expected: keys is too slow
            }
            long d = System.nanoTime() - s;
            all.add(d);
            if (s >= settleAt) {
              late.add(d);
            }
          });
      long[] a = all.stream().mapToLong(Long::longValue).sorted().toArray();
      long[] l = late.stream().mapToLong(Long::longValue).sorted().toArray();
      var r = new LinkedHashMap<String, Object>();
      r.put("breaker", withBreaker);
      r.put("p50_ms", pct(a, 0.5));
      r.put("p99_ms", pct(a, 0.99));
      r.put("p99_after_first_second_ms", pct(l, 0.99));
      r.put("calls_reaching_keys", keys.calls.get());
      r.put("max_in_flight_at_keys", keys.maxInFlight.get());
      return r;
    }
  }

  @Test
  void breakerVsNone() throws Exception {
    var none = slowKeys(false);
    var with = slowKeys(true);
    boolean confirmed =
        (double) with.get("p99_after_first_second_ms") < 50
            && (double) none.get("p99_after_first_second_ms") >= 250;
    var result = new LinkedHashMap<String, Object>();
    result.put("experiment", "breaker-vs-none");
    result.put(
        "hypothesis",
        "with keys hanging, every cold request waits the 300 ms timeout without a breaker; with it,"
            + " after it opens p99 < 50 ms and keys stops receiving calls");
    result.put("setup", "100 req/s for 5 s, every request a cold key, keys answers after 5 s");
    result.put("no_breaker", none);
    result.put("breaker", with);
    result.put("verdict", confirmed ? "confirmed" : "refuted");
    write("breaker-vs-none", result);
    assertThat(confirmed).as(result.toString()).isTrue();
  }
}
