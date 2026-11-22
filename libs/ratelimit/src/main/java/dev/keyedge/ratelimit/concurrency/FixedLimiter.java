package dev.keyedge.ratelimit.concurrency;

/**
 * A fixed limit (a non-blocking semaphore): the baseline the adaptive limiters are compared with.
 */
public final class FixedLimiter extends AbstractLimiter {
  private final int limit;

  public FixedLimiter(int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive");
    }
    this.limit = limit;
  }

  @Override
  public int limit() {
    return limit;
  }

  @Override
  protected void onSample(long rttNanos, int inFlightAtStart) {}

  @Override
  protected void onDrop() {}
}
