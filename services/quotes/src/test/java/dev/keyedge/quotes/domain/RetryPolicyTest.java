package dev.keyedge.quotes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {
  private final RandomGenerator random = new SplittableRandom(42);

  @Test
  void backoffStaysWithinBaseAndCap() {
    for (var jitter : RetryPolicy.Jitter.values()) {
      var p = new RetryPolicy(5, Duration.ofMillis(50), Duration.ofMillis(400), jitter);
      Duration prev = p.base();
      for (int attempt = 1; attempt < 50; attempt++) {
        Duration d = p.backoff(attempt, prev, random);
        assertThat(d).isBetween(Duration.ZERO, Duration.ofMillis(400));
        if (jitter == RetryPolicy.Jitter.DECORRELATED) {
          assertThat(d).isGreaterThanOrEqualTo(Duration.ofMillis(50));
        }
        prev = d;
      }
    }
  }

  @Test
  void aLongerRetryAfterWins() {
    var p =
        new RetryPolicy(3, Duration.ofMillis(10), Duration.ofMillis(20), RetryPolicy.Jitter.FULL);
    assertThat(p.waitFor(1, p.base(), Optional.of(Duration.ofSeconds(2)), random))
        .isEqualTo(Duration.ofSeconds(2));
    assertThat(p.waitFor(1, p.base(), Optional.empty(), random))
        .isLessThanOrEqualTo(Duration.ofMillis(20));
  }

  @Test
  void invalidPoliciesAreRejected() {
    assertThatThrownBy(
            () -> new RetryPolicy(0, Duration.ZERO, Duration.ZERO, RetryPolicy.Jitter.FULL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new RetryPolicy(
                2, Duration.ofSeconds(2), Duration.ofSeconds(1), RetryPolicy.Jitter.FULL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(RetryPolicy.none().maxAttempts()).isEqualTo(1);
  }

  @Test
  void theBudgetCapsRetriesToARatioOfCalls() {
    var budget = new RetryBudget(0.1, 2);
    int granted = 0;
    for (int i = 0; i < 100; i++) {
      budget.onCall();
      if (budget.tryRetry()) {
        granted++;
      }
    }
    assertThat(granted).isEqualTo(12); // 2 allowance + 10 % of 100
    assertThat(budget.retries()).isEqualTo(12);
    assertThat(budget.calls()).isEqualTo(100);
  }

  /**
   * Deterministic simulation (orchlab lesson: full jitter is not always best): 100 clients retry
   * against an upstream that recovers at t = 300 ms. Invariants only — attempts bounded, waits
   * capped — and the two strategies' numbers are recorded, not ranked.
   */
  @Test
  void jitterSimulation() {
    Map<RetryPolicy.Jitter, long[]> out = new java.util.EnumMap<>(RetryPolicy.Jitter.class);
    for (var jitter : RetryPolicy.Jitter.values()) {
      var p = new RetryPolicy(6, Duration.ofMillis(20), Duration.ofMillis(400), jitter);
      var rnd = new SplittableRandom(7);
      long attemptsBeforeRecovery = 0;
      long completionSum = 0;
      for (int client = 0; client < 100; client++) {
        long t = rnd.nextLong(0, 50);
        Duration prev = p.base();
        int attempt = 1;
        while (t < 300 && attempt < p.maxAttempts()) {
          attemptsBeforeRecovery++;
          Duration w = p.backoff(attempt, prev, rnd);
          assertThat(w).isLessThanOrEqualTo(p.cap());
          t += w.toMillis();
          prev = w;
          attempt++;
        }
        assertThat(attempt).isLessThanOrEqualTo(p.maxAttempts());
        completionSum += t;
      }
      out.put(jitter, new long[] {attemptsBeforeRecovery, completionSum / 100});
    }
    assertThat(out).hasSize(2);
  }
}
