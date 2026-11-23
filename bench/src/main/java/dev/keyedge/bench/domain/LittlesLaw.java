package dev.keyedge.bench.domain;

/** L = λ · W: requests in flight from arrival rate and time in the system (D-72). */
public final class LittlesLaw {
  private LittlesLaw() {}

  /** In-flight requests at {@code ratePerSecond} when each spends {@code latencySeconds}. */
  public static double concurrency(double ratePerSecond, double latencySeconds) {
    positive(ratePerSecond, "rate");
    positive(latencySeconds, "latency");
    return ratePerSecond * latencySeconds;
  }

  /** The highest rate {@code concurrencyLimit} slots can carry at {@code latencySeconds} each. */
  public static double maxRate(double concurrencyLimit, double latencySeconds) {
    positive(concurrencyLimit, "concurrency");
    positive(latencySeconds, "latency");
    return concurrencyLimit / latencySeconds;
  }

  private static void positive(double v, String what) {
    if (!(v > 0) || Double.isInfinite(v)) {
      throw new IllegalArgumentException(what + " must be a positive number, got " + v);
    }
  }
}
