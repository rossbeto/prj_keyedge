package dev.keyedge.ratelimit;

import dev.keyedge.keyformat.RatePlan;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A token bucket whose whole state fits in one {@code long}, so refill and take happen in one CAS:
 *
 * <pre>
 *   bits 63..42  tokens in milli-tokens (22 bits: up to 4 194 tokens of burst, fractional refill)
 *   bits 41..0   last refill time in microseconds, modulo 2^42 (about 50.9 days)
 * </pre>
 *
 * Time differences are signed 42-bit values, so the field never needs rebasing. A key idle for more
 * than ~25 days may see a smaller refill than it is owed: it under-admits, never over-admits.
 */
public final class PackedTokenBucket implements RateLimiter {
  static final int TIME_BITS = 42;
  static final long TIME_MASK = (1L << TIME_BITS) - 1;
  static final long MAX_MILLI_TOKENS = (1L << (64 - TIME_BITS)) - 1;

  /** Largest burst the packing can hold. */
  public static final long MAX_BURST = MAX_MILLI_TOKENS / 1000;

  /** Largest sustained rate supported (keeps the refill product within 63 bits). */
  public static final long MAX_RATE = 1_000_000;

  private final Ticker ticker;
  private final long rate; // permits per second = milli-tokens per millisecond
  private final long capacity; // milli-tokens
  private final AtomicLong state;

  public PackedTokenBucket(RatePlan plan, Ticker ticker) {
    if (plan.burst() > MAX_BURST || plan.permitsPerSecond() > MAX_RATE) {
      throw new IllegalArgumentException(
          "plan exceeds the packed range (burst <= " + MAX_BURST + ", rate <= " + MAX_RATE + ")");
    }
    this.ticker = ticker;
    this.rate = plan.permitsPerSecond();
    this.capacity = plan.burst() * 1000;
    this.state = new AtomicLong(pack(capacity, micros()));
  }

  static long pack(long milliTokens, long micros) {
    return (milliTokens << TIME_BITS) | (micros & TIME_MASK);
  }

  static long tokens(long packed) {
    return packed >>> TIME_BITS;
  }

  static long time(long packed) {
    return packed & TIME_MASK;
  }

  private long micros() {
    return Math.floorDiv(ticker.nanos(), 1000) & TIME_MASK;
  }

  @Override
  public Decision tryAcquire() {
    long now = micros();
    while (true) {
      long current = state.get();
      // Signed 42-bit difference: modular (no rebasing), and a "now" older than the stored time
      // (another thread with a later reading won the CAS) refills nothing instead of wrapping to
      // ~2^42 µs and filling the bucket.
      long elapsed = Math.max(0, ((now - time(current)) << (64 - TIME_BITS)) >> (64 - TIME_BITS));
      long added = elapsed * rate / 1000; // milli-tokens
      long tokens = tokens(current) + added;
      long last;
      if (tokens >= capacity) {
        tokens = capacity;
        last = elapsed > 0 ? now : time(current); // never move the stored time backwards
      } else {
        last =
            (time(current) + added * 1000 / rate) & TIME_MASK; // keep the unused fraction of time
      }
      if (tokens < 1000) {
        long micros = ((1000 - tokens) * 1000 + rate - 1) / rate;
        return new Decision.Rejected(Duration.ofNanos(micros * 1000));
      }
      if (state.compareAndSet(current, pack(tokens - 1000, last))) {
        return new Decision.Allowed((tokens - 1000) / 1000);
      }
      Thread.onSpinWait();
    }
  }
}
