package dev.keyedge.ratelimit.concurrency;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** In-flight accounting shared by every limiter: a CAS on the in-flight count against the limit. */
abstract class AbstractLimiter implements ConcurrencyLimiter {
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicLong doubleReleases = new AtomicLong();

  @Override
  public final Optional<Permit> tryAcquire() {
    while (true) {
      int current = inFlight.get();
      if (current >= limit()) {
        return Optional.empty();
      }
      if (inFlight.compareAndSet(current, current + 1)) {
        return Optional.of(new SlotPermit(current + 1));
      }
      Thread.onSpinWait();
    }
  }

  @Override
  public final int inFlight() {
    return inFlight.get();
  }

  @Override
  public final long doubleReleases() {
    return doubleReleases.get();
  }

  /**
   * Latency sample from a successful request; {@code inFlightAtStart} tells if the limit was used.
   */
  protected abstract void onSample(long rttNanos, int inFlightAtStart);

  /** A drop or timeout. */
  protected abstract void onDrop();

  private final class SlotPermit implements Permit {
    private final AtomicBoolean released = new AtomicBoolean();
    private final int inFlightAtStart;

    SlotPermit(int inFlightAtStart) {
      this.inFlightAtStart = inFlightAtStart;
    }

    private boolean release() {
      if (!released.compareAndSet(false, true)) {
        doubleReleases.incrementAndGet();
        return false;
      }
      inFlight.decrementAndGet();
      return true;
    }

    @Override
    public void onSuccess(long rttNanos) {
      if (release()) {
        onSample(rttNanos, inFlightAtStart);
      }
    }

    @Override
    public void onDropped() {
      if (release()) {
        onDrop();
      }
    }

    @Override
    public void onIgnore() {
      release();
    }
  }
}
