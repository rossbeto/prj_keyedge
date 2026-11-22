package dev.keyedge.ratelimit;

import dev.keyedge.keyformat.RatePlan;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generic Cell Rate Algorithm: the whole state is one "theoretical arrival time" (TAT). A request
 * is admitted if, after adding one emission interval, the TAT is not further ahead of now than the
 * burst allows. One CAS per decision; equivalent to a token bucket with less state.
 */
public final class Gcra implements RateLimiter {
  private static final long EMPTY = Long.MIN_VALUE;
  private final Ticker ticker;
  private final long interval; // nanos between permits at the sustained rate
  private final long limit; // tolerance + interval: how far ahead the TAT may run
  private final AtomicLong tat = new AtomicLong(EMPTY);

  public Gcra(RatePlan plan, Ticker ticker) {
    this.ticker = ticker;
    this.interval = Math.max(1, 1_000_000_000L / plan.permitsPerSecond());
    this.limit = Math.multiplyExact(interval, plan.burst());
  }

  @Override
  public Decision tryAcquire() {
    long now = ticker.nanos();
    while (true) {
      long current = tat.get();
      long base = current == EMPTY ? now : Math.max(current, now);
      long next = base + interval;
      long ahead = next - now;
      if (ahead > limit) {
        return new Decision.Rejected(Duration.ofNanos(ahead - limit));
      }
      if (tat.compareAndSet(current, next)) {
        return new Decision.Allowed((limit - ahead) / interval);
      }
      Thread.onSpinWait(); // another thread won: retry with its state
    }
  }
}
