package dev.keyedge.ratelimit;

import java.util.concurrent.atomic.AtomicLong;

/** A ticker tests move by hand. */
final class ManualTicker implements Ticker {
  private final AtomicLong now;

  ManualTicker(long start) {
    this.now = new AtomicLong(start);
  }

  @Override
  public long nanos() {
    return now.get();
  }

  void advanceMillis(long ms) {
    now.addAndGet(ms * 1_000_000);
  }

  void set(long nanos) {
    now.set(nanos);
  }
}
