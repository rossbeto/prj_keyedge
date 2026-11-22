package dev.keyedge.sim.app;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Independent counters that experiments read instead of the system under test's own metrics: calls,
 * in-flight and its high-water mark, statuses, tokens, and repeated non-idempotent requests.
 */
public final class Counters {
  private final Map<String, Endpoint> endpoints = new ConcurrentHashMap<>();
  private final LongAdder tokensIssued = new LongAdder();
  private final LongAdder expiredTokens = new LongAdder();
  private final Map<String, LongAdder> postsByRequestId = new ConcurrentHashMap<>();

  private static final class Endpoint {
    final LongAdder calls = new LongAdder();
    final AtomicLong inFlight = new AtomicLong();
    final AtomicLong inFlightMax = new AtomicLong();
    final Map<Integer, LongAdder> byStatus = new ConcurrentHashMap<>();
  }

  private Endpoint e(String name) {
    return endpoints.computeIfAbsent(name, n -> new Endpoint());
  }

  /** Call started; returns a handle that records the status when the call ends. */
  public void start(String endpoint) {
    Endpoint e = e(endpoint);
    e.calls.increment();
    long now = e.inFlight.incrementAndGet();
    e.inFlightMax.accumulateAndGet(now, Math::max);
  }

  public void end(String endpoint, int status) {
    Endpoint e = e(endpoint);
    e.inFlight.decrementAndGet();
    e.byStatus.computeIfAbsent(status, s -> new LongAdder()).increment();
  }

  public void tokenIssued() {
    tokensIssued.increment();
  }

  public void expiredToken() {
    expiredTokens.increment();
  }

  /** A non-idempotent POST arrived with this request id. */
  public void post(String requestId) {
    postsByRequestId.computeIfAbsent(requestId, r -> new LongAdder()).increment();
  }

  public void reset() {
    endpoints.clear();
    tokensIssued.reset();
    expiredTokens.reset();
    postsByRequestId.clear();
  }

  /** A JSON-ready snapshot. */
  public Map<String, Object> snapshot() {
    var out = new TreeMap<String, Object>();
    var eps = new TreeMap<String, Object>();
    endpoints.forEach((name, e) -> {
      var statuses = new TreeMap<String, Long>();
      e.byStatus.forEach((s, n) -> statuses.put(Integer.toString(s), n.sum()));
      eps.put(
          name,
          Map.of(
              "calls",
              e.calls.sum(),
              "inFlight",
              e.inFlight.get(),
              "inFlightMax",
              e.inFlightMax.get(),
              "byStatus",
              statuses));
    });
    out.put("endpoints", eps);
    out.put("tokensIssued", tokensIssued.sum());
    out.put("requestsWithExpiredToken", expiredTokens.sum());
    out.put(
        "repeatedNonIdempotentPosts",
        postsByRequestId.values().stream().filter(n -> n.sum() > 1).count());
    out.put(
        "nonIdempotentPosts", postsByRequestId.values().stream().mapToLong(LongAdder::sum).sum());
    return out;
  }
}
