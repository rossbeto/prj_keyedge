package dev.keyedge.fanout;

import java.time.Duration;
import java.util.function.LongSupplier;

/** A point on a monotonic clock ({@code nanos}), so deadlines survive wall-clock jumps. */
public record Deadline(long atNanos) {
  public static Deadline after(Duration budget, LongSupplier nanos) {
    return new Deadline(nanos.getAsLong() + budget.toNanos());
  }

  /** Time left, never negative. */
  public Duration remaining(LongSupplier nanos) {
    return Duration.ofNanos(Math.max(0, atNanos - nanos.getAsLong()));
  }

  public boolean expired(LongSupplier nanos) {
    return nanos.getAsLong() - atNanos >= 0;
  }

  /** The smaller of this deadline and {@code limit} from now (a per-call timeout under it). */
  public Duration cap(Duration limit, LongSupplier nanos) {
    Duration left = remaining(nanos);
    return left.compareTo(limit) < 0 ? left : limit;
  }
}
