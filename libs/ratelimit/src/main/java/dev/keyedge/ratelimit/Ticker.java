package dev.keyedge.ratelimit;

/**
 * Monotonic time source in nanoseconds; injected so tests and jcstress can freeze or script time.
 */
@FunctionalInterface
public interface Ticker {
  long nanos();

  /** The JVM's monotonic clock: the only place {@code System.nanoTime} is read. */
  static Ticker system() {
    return System::nanoTime;
  }
}
