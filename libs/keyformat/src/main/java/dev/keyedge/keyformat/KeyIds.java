package dev.keyedge.keyformat;

import java.time.InstantSource;
import java.util.concurrent.locks.ReentrantLock;
import java.util.random.RandomGenerator;

/**
 * Generates {@link KeyId}s that sort by creation time, and stay strictly increasing within one
 * millisecond (the random part is incremented, as in the ULID monotonic variant). Thread-safe.
 */
public final class KeyIds {
  private static final long RANDOM_HIGH_MASK = 0xFFFFL; // 16 random bits in the high word
  private static final long MAX_MILLIS = (1L << 48) - 1;

  private final InstantSource clock;
  private final RandomGenerator random;
  private final ReentrantLock lock = new ReentrantLock();
  private long lastMillis = -1;
  private long lastRandomHigh;
  private long lastRandomLow;

  public KeyIds(InstantSource clock, RandomGenerator random) {
    this.clock = clock;
    this.random = random;
  }

  /** The next id. */
  public KeyId next() {
    lock.lock();
    try {
      long millis = Math.max(clock.millis(), lastMillis);
      if (millis > MAX_MILLIS) {
        throw new IllegalStateException("clock beyond the 48-bit id range");
      }
      if (millis == lastMillis) {
        increment(); // same millisecond: keep order by bumping the random part
      } else {
        lastMillis = millis;
        lastRandomHigh = random.nextLong() & RANDOM_HIGH_MASK;
        lastRandomLow = random.nextLong();
      }
      return new KeyId((lastMillis << 16) | lastRandomHigh, lastRandomLow);
    } finally {
      lock.unlock();
    }
  }

  private void increment() {
    lastRandomLow++;
    if (lastRandomLow == 0) {
      lastRandomHigh = (lastRandomHigh + 1) & RANDOM_HIGH_MASK;
      if (lastRandomHigh == 0) { // 80-bit overflow: borrow the next millisecond
        lastMillis++;
      }
    }
  }
}
