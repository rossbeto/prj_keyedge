package dev.keyedge.ratelimit.lab.pitfalls;

import dev.keyedge.keyformat.RatePlan;
import dev.keyedge.ratelimit.Ticker;

/**
 * DELIBERATELY WRONG: reproduces lost updates between refill and take (two volatile fields, read,
 * compute, write); the fix is {@link dev.keyedge.ratelimit.PackedTokenBucket}. Each field is
 * volatile, so every single read and write is atomic, but the sequence is not: two threads both
 * read one token and both take it. jcstress observes it ({@code NaiveBucketOverAdmits}).
 */
public final class NaiveTokenBucket {
  private final Ticker ticker;
  private final double rate;
  private final double capacity;
  private volatile double tokens;
  private volatile long lastRefill;

  public NaiveTokenBucket(RatePlan plan, Ticker ticker) {
    this.ticker = ticker;
    this.rate = plan.permitsPerSecond();
    this.capacity = plan.burst();
    this.tokens = capacity;
    this.lastRefill = ticker.nanos();
  }

  public boolean tryAcquire() {
    long now = ticker.nanos();
    double refilled = Math.min(capacity, tokens + (now - lastRefill) / 1e9 * rate);
    lastRefill = now;
    if (refilled >= 1) {
      tokens = refilled - 1;
      return true;
    }
    tokens = refilled;
    return false;
  }
}
