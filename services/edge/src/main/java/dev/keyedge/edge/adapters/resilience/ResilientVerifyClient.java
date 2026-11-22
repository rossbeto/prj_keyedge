package dev.keyedge.edge.adapters.resilience;

import dev.keyedge.edge.ports.Verdicts;
import dev.keyedge.edge.ports.Verdicts.Unavailable.Reason;
import dev.keyedge.edge.ports.VerifyClient;
import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.KeySecret;
import dev.keyedge.ratelimit.Ticker;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The control plane behind a bulkhead and a circuit breaker (D-20). Decorator order: bulkhead
 * outside, breaker inside, so a full bulkhead is not counted as a control-plane failure. The time
 * limit is the HTTP client's own request timeout: on virtual threads a TimeLimiter would need a
 * second thread to interrupt the first (ADR 0010). No retry: the cache's single flight already
 * collapses concurrent misses, and retrying would multiply load on a struggling control plane. The
 * breaker records "down" failures and slow calls; a 4xx answer ({@link Reason#CONTRACT}) is a bug
 * on our side, not an outage, and is ignored.
 */
public final class ResilientVerifyClient implements VerifyClient {
  private final VerifyClient delegate;
  private final CircuitBreaker breaker;
  private final Bulkhead bulkhead;
  private final Ticker ticker;
  private final Duration openFor;
  private final AtomicLong openedAtNanos = new AtomicLong();

  public ResilientVerifyClient(VerifyClient delegate, Settings s, Ticker ticker) {
    this.delegate = delegate;
    this.ticker = ticker;
    this.openFor = s.openFor();
    this.breaker =
        CircuitBreaker.of(
            "keys-verify",
            CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(s.window())
                .minimumNumberOfCalls(s.minimumCalls())
                .failureRateThreshold(s.failureRatePercent())
                .slowCallDurationThreshold(s.slowCall())
                .slowCallRateThreshold(s.slowCallRatePercent())
                .waitDurationInOpenState(s.openFor())
                .permittedNumberOfCallsInHalfOpenState(s.halfOpenCalls())
                .recordException(e ->
                    e instanceof Verdicts.Unavailable u
                        ? u.reason() != Reason.CONTRACT
                        : e instanceof RuntimeException)
                .build());
    this.bulkhead =
        Bulkhead.of(
            "keys-verify",
            BulkheadConfig.custom()
                .maxConcurrentCalls(s.maxConcurrent())
                .maxWaitDuration(Duration.ZERO)
                .build());
    breaker
        .getEventPublisher()
        .onStateTransition(e -> {
          if (e.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
            openedAtNanos.set(ticker.nanos());
          }
        });
  }

  /** Breaker and bulkhead settings ({@code keyedge.edge.verify.*}). */
  public record Settings(
      int window,
      int minimumCalls,
      float failureRatePercent,
      Duration slowCall,
      float slowCallRatePercent,
      Duration openFor,
      int halfOpenCalls,
      int maxConcurrent) {}

  @Override
  public KeyCheck verify(KeyId keyId, KeySecret secret) {
    try {
      return Bulkhead.decorateSupplier(
              bulkhead,
              CircuitBreaker.decorateSupplier(breaker, () -> delegate.verify(keyId, secret)))
          .get();
    } catch (CallNotPermittedException e) {
      throw new Verdicts.Unavailable(
          "circuit open: control plane failing", e, Reason.CIRCUIT_OPEN, remainingOpen());
    } catch (BulkheadFullException e) {
      throw new Verdicts.Unavailable(
          "too many concurrent verify calls", e, Reason.BULKHEAD_FULL, null);
    }
  }

  /** Time until the breaker lets a probe through: the clients' Retry-After. */
  private Duration remainingOpen() {
    long elapsed = ticker.nanos() - openedAtNanos.get();
    return Duration.ofNanos(Math.max(0, openFor.toNanos() - elapsed));
  }

  /** Breaker state name (CLOSED, OPEN, HALF_OPEN), for readiness details and tests. */
  public String state() {
    return breaker.getState().name();
  }
}
