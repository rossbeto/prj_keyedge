package dev.keyedge.ratelimit;

import java.time.Duration;

/** Outcome of a limiter call: a value the caller switches on, never an exception. */
public sealed interface Decision {
  /** Admitted; {@code remaining} more requests fit in the current burst. */
  record Allowed(long remaining) implements Decision {}

  /** Rejected; the earliest time the same request would be admitted. */
  record Rejected(Duration retryAfter) implements Decision {}

  default boolean allowed() {
    return this instanceof Allowed;
  }
}
