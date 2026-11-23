package dev.keyedge.ratelimit.concurrency;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Gradient limiter (a simplified form of Netflix concurrency-limits' Gradient2): compares the
 * no-load latency (minimum over a window of samples) with the current latency. When latency rises,
 * the gradient falls below 1 and the limit shrinks; a small queue allowance lets it probe upwards.
 * {@code tolerance} (Gradient2's rtt tolerance, e.g. 2.0) is how much slower than the no-load
 * latency still counts as "not congested": without it, the natural spread of a fan-out's latency
 * reads as congestion and the limit collapses to its minimum (found by the Phase 010 load run).
 */
public final class GradientLimiter extends AbstractLimiter {
  private final int min;
  private final int max;
  private final int window;
  private final double smoothing;
  private final double tolerance;
  private final ReentrantLock lock = new ReentrantLock();
  private final long[] samples;
  private int next;
  private int count;
  private double estimate;
  private volatile int limit;

  public GradientLimiter(int initial, int min, int max, int window, double smoothing) {
    this(initial, min, max, window, smoothing, 1.0);
  }

  public GradientLimiter(
      int initial, int min, int max, int window, double smoothing, double tolerance) {
    if (tolerance < 1) {
      throw new IllegalArgumentException("tolerance >= 1");
    }
    this.tolerance = tolerance;
    if (min < 1
        || initial < min
        || max < initial
        || window < 1
        || smoothing <= 0
        || smoothing > 1) {
      throw new IllegalArgumentException(
          "need 1 <= min <= initial <= max, window >= 1, 0 < smoothing <= 1");
    }
    this.limit = initial;
    this.estimate = initial;
    this.min = min;
    this.max = max;
    this.window = window;
    this.smoothing = smoothing;
    this.samples = new long[window];
  }

  @Override
  public int limit() {
    return limit;
  }

  @Override
  protected void onSample(long rttNanos, int inFlightAtStart) {
    lock.lock();
    try {
      samples[next] = rttNanos;
      next = (next + 1) % window;
      count = Math.min(window, count + 1);
      long noLoad = Long.MAX_VALUE;
      for (int i = 0; i < count; i++) {
        noLoad = Math.min(noLoad, samples[i]);
      }
      double gradient = Math.max(0.5, Math.min(1.0, tolerance * noLoad / Math.max(1, rttNanos)));
      double queue = Math.sqrt(estimate);
      double target = estimate * gradient + queue;
      estimate = Math.max(min, Math.min(max, estimate * (1 - smoothing) + target * smoothing));
      limit = (int) estimate;
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected void onDrop() {
    lock.lock();
    try {
      estimate = Math.max(min, estimate * 0.9);
      limit = (int) estimate;
    } finally {
      lock.unlock();
    }
  }
}
