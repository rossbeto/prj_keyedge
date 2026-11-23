package dev.keyedge.quotes.domain;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Caps retries to a fraction of calls (plus a small allowance), so a struggling upstream is not hit
 * {@code maxAttempts} times harder by every client at once (gRPC/Finagle retry budgets). Counts
 * since start; good enough for a lab — a sliding window would forget old traffic.
 */
public final class RetryBudget {
  private final double ratio;
  private final long allowance;
  private final AtomicLong calls = new AtomicLong();
  private final AtomicLong retries = new AtomicLong();

  public RetryBudget(double ratio, long allowance) {
    this.ratio = ratio;
    this.allowance = allowance;
  }

  public void onCall() {
    calls.incrementAndGet();
  }

  /** Takes one retry from the budget if there is room. */
  public boolean tryRetry() {
    while (true) {
      long r = retries.get();
      if (r >= allowance + (long) (calls.get() * ratio)) {
        return false;
      }
      if (retries.compareAndSet(r, r + 1)) {
        return true;
      }
    }
  }

  public long retries() {
    return retries.get();
  }

  public long calls() {
    return calls.get();
  }
}
