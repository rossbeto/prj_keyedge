package dev.keyedge.ratelimit;

/** A per-key rate limiter. Implementations are thread-safe and lock-free. */
public interface RateLimiter {
  /** Takes one permit now. */
  Decision tryAcquire();
}
