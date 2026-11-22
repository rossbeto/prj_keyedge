package dev.keyedge.ratelimit.concurrency;

/**
 * One in-flight slot. Report exactly one outcome; {@link #close()} without an outcome counts as
 * "ignore" (no latency signal). Releasing twice is a no-op, counted by the limiter to expose
 * misuse.
 */
public interface Permit extends AutoCloseable {
  /** The request finished normally after {@code rttNanos}: a latency sample. */
  void onSuccess(long rttNanos);

  /** The request was dropped or timed out: a congestion signal. */
  void onDropped();

  /** Release without a signal (e.g. a client error unrelated to load). */
  void onIgnore();

  @Override
  default void close() {
    onIgnore();
  }
}
