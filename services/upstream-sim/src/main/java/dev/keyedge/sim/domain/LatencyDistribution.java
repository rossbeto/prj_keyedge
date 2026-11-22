package dev.keyedge.sim.domain;

import java.time.Duration;
import java.util.SplittableRandom;

/** How long a call waits before answering. Sampled from a seeded generator. */
public sealed interface LatencyDistribution {
  Duration sample(SplittableRandom random);

  /** Always the same. */
  record Fixed(long ms) implements LatencyDistribution {
    @Override
    public Duration sample(SplittableRandom random) {
      return Duration.ofMillis(ms);
    }
  }

  /** Uniform in [minMs, maxMs]. */
  record Uniform(long minMs, long maxMs) implements LatencyDistribution {
    public Uniform {
      if (minMs < 0 || maxMs < minMs) {
        throw new IllegalArgumentException("need 0 <= minMs <= maxMs");
      }
    }

    @Override
    public Duration sample(SplittableRandom random) {
      return Duration.ofMillis(minMs + random.nextLong(maxMs - minMs + 1));
    }
  }

  /**
   * Log-normal with the given median and 99th percentile: the classic long-tail latency. sigma =
   * ln(p99 / median) / 2.326 (the 99th percentile of a standard normal).
   */
  record LogNormal(long medianMs, long p99Ms) implements LatencyDistribution {
    public LogNormal {
      if (medianMs < 1 || p99Ms < medianMs) {
        throw new IllegalArgumentException("need 1 <= medianMs <= p99Ms");
      }
    }

    @Override
    public Duration sample(SplittableRandom random) {
      double sigma = Math.log((double) p99Ms / medianMs) / 2.326;
      double gaussian = gaussian(random);
      return Duration.ofMillis(Math.round(medianMs * Math.exp(sigma * gaussian)));
    }
  }

  /** Mostly fast, sometimes slow (e.g. a cache miss or a GC pause downstream). */
  record Bimodal(long fastMs, long slowMs, double slowRate) implements LatencyDistribution {
    public Bimodal {
      if (slowRate < 0 || slowRate > 1) {
        throw new IllegalArgumentException("slowRate in [0, 1]");
      }
    }

    @Override
    public Duration sample(SplittableRandom random) {
      return Duration.ofMillis(random.nextDouble() < slowRate ? slowMs : fastMs);
    }
  }

  /** Box–Muller from a seeded generator (no hidden global randomness). */
  private static double gaussian(SplittableRandom random) {
    double u1 = Math.max(Double.MIN_VALUE, random.nextDouble());
    double u2 = random.nextDouble();
    return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
  }
}
