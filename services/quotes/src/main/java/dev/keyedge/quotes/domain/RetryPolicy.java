package dev.keyedge.quotes.domain;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Retries for idempotent upstream calls (D-20): at most {@code maxAttempts}, backoff with jitter
 * capped by {@code cap}, and never past the request deadline (the caller checks). Two jitter
 * strategies (AWS architecture blog): full jitter {@code random(0, base·2^n)} and decorrelated
 * {@code random(base, previous·3)}; a {@code Retry-After} from the upstream wins when longer.
 */
public record RetryPolicy(int maxAttempts, Duration base, Duration cap, Jitter jitter) {
  public RetryPolicy {
    if (maxAttempts < 1 || base.isNegative() || cap.compareTo(base) < 0) {
      throw new IllegalArgumentException("maxAttempts >= 1, 0 <= base <= cap");
    }
  }

  /** Jitter strategy. */
  public enum Jitter {
    FULL,
    DECORRELATED
  }

  public static RetryPolicy none() {
    return new RetryPolicy(1, Duration.ZERO, Duration.ZERO, Jitter.FULL);
  }

  /** The wait before the next attempt: the backoff, or the upstream's Retry-After when longer. */
  public Duration waitFor(
      int attempt,
      Duration previous,
      java.util.Optional<Duration> retryAfter,
      RandomGenerator random) {
    Duration backoff = backoff(attempt, previous, random);
    return retryAfter.filter(ra -> ra.compareTo(backoff) > 0).orElse(backoff);
  }

  /** Wait before attempt {@code attempt + 1} (attempt counts from 1), given the previous wait. */
  public Duration backoff(int attempt, Duration previous, RandomGenerator random) {
    long baseMs = Math.max(1, base.toMillis());
    long capMs = cap.toMillis();
    long ms =
        switch (jitter) {
          case FULL -> random.nextLong(0, Math.min(capMs, baseMs << Math.min(20, attempt - 1)) + 1);
          case DECORRELATED -> {
            long upper = Math.min(capMs, Math.max(baseMs, previous.toMillis()) * 3);
            yield random.nextLong(baseMs, Math.max(baseMs, upper) + 1);
          }
        };
    return Duration.ofMillis(Math.min(capMs, ms));
  }
}
