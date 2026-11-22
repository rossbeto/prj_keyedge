package dev.keyedge.ratelimit.concurrency;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Additive increase, multiplicative decrease: +1 on a fast success while the limit is actually
 * used, ×{@code backoff} on a drop or a slow success. Like TCP congestion control, it probes for
 * capacity and backs off at the first sign of congestion.
 */
public final class AimdLimiter extends AbstractLimiter {
  private final int min;
  private final int max;
  private final long slowNanos;
  private final double backoff;
  private final ReentrantLock lock = new ReentrantLock();
  private volatile int limit;

  public AimdLimiter(int initial, int min, int max, long slowNanos, double backoff) {
    if (min < 1 || initial < min || max < initial || backoff <= 0 || backoff >= 1) {
      throw new IllegalArgumentException("need 1 <= min <= initial <= max and 0 < backoff < 1");
    }
    this.limit = initial;
    this.min = min;
    this.max = max;
    this.slowNanos = slowNanos;
    this.backoff = backoff;
  }

  @Override
  public int limit() {
    return limit;
  }

  @Override
  protected void onSample(long rttNanos, int inFlightAtStart) {
    if (rttNanos > slowNanos) {
      decrease();
    } else if (inFlightAtStart * 2 >= limit) { // only grow when the limit is in use
      lock.lock();
      try {
        limit = Math.min(max, limit + 1);
      } finally {
        lock.unlock();
      }
    }
  }

  @Override
  protected void onDrop() {
    decrease();
  }

  private void decrease() {
    lock.lock();
    try {
      limit = Math.max(min, (int) (limit * backoff));
    } finally {
      lock.unlock();
    }
  }
}
