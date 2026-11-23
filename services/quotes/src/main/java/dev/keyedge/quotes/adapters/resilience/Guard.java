package dev.keyedge.quotes.adapters.resilience;

import dev.keyedge.fanout.CallException;
import dev.keyedge.fanout.Deadline;
import dev.keyedge.quotes.domain.RetryBudget;
import dev.keyedge.quotes.domain.RetryPolicy;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * One upstream's protection (D-20, ADR 0011), in this order: bulkhead (reject when too many calls
 * are in flight) → circuit breaker → retry. Retry sits <b>inside</b> the breaker so every attempt
 * is recorded, and only for idempotent calls, within a budget, and never past the deadline. The
 * breaker ignores client errors (4xx except 429). A {@code Retry-After} longer than the backoff
 * wins. Resilience4j provides the breaker and bulkhead; the retry loop is ours because it needs the
 * deadline and the budget.
 */
public final class Guard {
  private final String upstream;
  private final Bulkhead bulkhead;
  private final CircuitBreaker breaker;
  private final RetryPolicy policy;
  private final RetryBudget budget;
  private final LongSupplier nanos;
  private final RandomGenerator random;
  private final Sleeper sleeper;

  /** Waits between attempts (a test can record instead of sleeping). */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration d) throws InterruptedException;
  }

  /** The guarded work. */
  @FunctionalInterface
  public interface Work<T> {
    T run() throws CallException, InterruptedException;
  }

  public Guard(
      String upstream,
      Bulkhead bulkhead,
      CircuitBreaker breaker,
      RetryPolicy policy,
      RetryBudget budget,
      LongSupplier nanos,
      RandomGenerator random,
      Sleeper sleeper) {
    this.upstream = upstream;
    this.bulkhead = bulkhead;
    this.breaker = breaker;
    this.policy = policy;
    this.budget = budget;
    this.nanos = nanos;
    this.random = random;
    this.sleeper = sleeper;
  }

  public <T> T call(boolean idempotent, Deadline deadline, Work<T> work)
      throws CallException, InterruptedException {
    budget.onCall();
    if (!bulkhead.tryAcquirePermission()) {
      throw CallException.io(upstream, "bulkhead full");
    }
    try {
      Duration previous = policy.base();
      for (int attempt = 1; ; attempt++) {
        try {
          return attempt(work);
        } catch (CallException e) {
          if (!idempotent || e.clientError() || attempt >= policy.maxAttempts()) {
            throw e;
          }
          Duration wait = policy.waitFor(attempt, previous, e.retryAfter(), random);
          if (wait.compareTo(deadline.remaining(nanos)) >= 0 || !budget.tryRetry()) {
            throw e;
          }
          sleeper.sleep(wait);
          previous = wait;
        }
      }
    } finally {
      bulkhead.onComplete();
    }
  }

  private <T> T attempt(Work<T> work) throws CallException, InterruptedException {
    if (!breaker.tryAcquirePermission()) {
      throw CallException.io(upstream, "circuit open");
    }
    long start = nanos.getAsLong();
    try {
      T value = work.run();
      breaker.onSuccess(nanos.getAsLong() - start, TimeUnit.NANOSECONDS);
      return value;
    } catch (CallException e) {
      if (e.clientError()) {
        breaker.releasePermission(); // the caller's problem, not the upstream's health
      } else {
        breaker.onError(nanos.getAsLong() - start, TimeUnit.NANOSECONDS, e);
      }
      throw e;
    } catch (InterruptedException | RuntimeException e) {
      breaker.releasePermission();
      throw e;
    }
  }

  public String state() {
    return breaker.getState().name();
  }
}
