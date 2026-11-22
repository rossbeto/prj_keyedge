package dev.keyedge.ratelimit.concurrency;

import java.util.Optional;

/**
 * Bounds requests in flight. {@code tryAcquire} never blocks: an empty result means shed the
 * request.
 */
public interface ConcurrencyLimiter {
  Optional<Permit> tryAcquire();

  /** Current limit. */
  int limit();

  /** Requests currently holding a permit. */
  int inFlight();

  /** Permits released more than once (should stay 0; tests assert it). */
  long doubleReleases();
}
