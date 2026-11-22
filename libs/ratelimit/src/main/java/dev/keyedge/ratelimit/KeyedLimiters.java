package dev.keyedge.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.keyedge.keyformat.RatePlan;
import java.time.Duration;
import java.util.function.Function;

/**
 * One limiter per key, in a bounded map (Caffeine) so a flood of distinct keys cannot grow memory
 * without limit. When a key's plan changes, its limiter is replaced (its state resets: acceptable).
 */
public final class KeyedLimiters<K> {
  private record Entry(RatePlan plan, RateLimiter limiter) {}

  private final Cache<K, Entry> limiters;
  private final Function<RatePlan, RateLimiter> factory;

  public KeyedLimiters(
      long maximumKeys, Duration idleExpiry, Function<RatePlan, RateLimiter> factory) {
    this.factory = factory;
    this.limiters =
        Caffeine.newBuilder().maximumSize(maximumKeys).expireAfterAccess(idleExpiry).build();
  }

  /** Takes one permit for {@code key} under {@code plan}. */
  public Decision tryAcquire(K key, RatePlan plan) {
    return limiterFor(key, plan).tryAcquire();
  }

  RateLimiter limiterFor(K key, RatePlan plan) {
    // Fast path: a plain read. compute() on every call took a per-key lock and write bookkeeping,
    // measured at ~18x the cost of the limiter itself (Phase 003 JMH, keyedManyKeys).
    Entry existing = limiters.getIfPresent(key);
    if (existing != null && existing.plan().equals(plan)) {
      return existing.limiter();
    }
    Entry entry =
        limiters
            .asMap()
            .compute(
                key,
                (k, old) ->
                    old != null && old.plan().equals(plan)
                        ? old
                        : new Entry(plan, factory.apply(plan)));
    return entry.limiter();
  }

  /** Approximate number of keys held. */
  public long size() {
    limiters.cleanUp();
    return limiters.estimatedSize();
  }
}
