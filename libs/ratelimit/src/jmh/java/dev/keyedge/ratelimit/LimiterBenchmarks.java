package dev.keyedge.ratelimit;

import dev.keyedge.keyformat.RatePlan;
import dev.keyedge.ratelimit.concurrency.AimdLimiter;
import dev.keyedge.ratelimit.concurrency.FixedLimiter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;

/**
 * Experiment `limiter-cost`: nanoseconds per decision. Same plan for every limiter (1 M/s, burst 1
 * 000), so the measured path mixes admits and rejects the same way for all of them.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class LimiterBenchmarks {
  static final RatePlan PLAN = new RatePlan(1_000_000, 1_000);

  /** One limiter shared by all benchmark threads (one hot key). */
  @State(Scope.Benchmark)
  public static class Shared {
    final Gcra gcra = new Gcra(PLAN, Ticker.system());
    final PackedTokenBucket packed = new PackedTokenBucket(PLAN, Ticker.system());
    final Bucket bucket4j =
        Bucket.builder()
            .addLimit(
                Bandwidth.builder()
                    .capacity(1_000)
                    .refillGreedy(1_000_000, Duration.ofSeconds(1))
                    .build())
            .build();
    final FixedLimiter fixed = new FixedLimiter(1_000);
    final AimdLimiter aimd = new AimdLimiter(1_000, 10, 10_000, 50_000_000L, 0.9);
    final KeyedLimiters<Integer> keyed =
        new KeyedLimiters<>(200_000, Duration.ofMinutes(10), p -> new Gcra(p, Ticker.system()));
    final java.util.concurrent.ConcurrentHashMap<Integer, Gcra> plainMap =
        new java.util.concurrent.ConcurrentHashMap<>();

    public Shared() {
      for (int i = 0; i < 100_000; i++) {
        plainMap.put(i, new Gcra(PLAN, Ticker.system()));
        keyed.tryAcquire(i, PLAN); // pre-populate: measure the steady state, not first-use creation
      }
    }
  }

  /** Baseline for keyedManyKeys: the same random access over 100 000 limiters in a plain map. */
  @Benchmark
  public boolean plainMapManyKeys(Shared s) {
    return s.plainMap.get(ThreadLocalRandom.current().nextInt(100_000)).tryAcquire().allowed();
  }

  @Benchmark
  public boolean gcraUncontended(Shared s) {
    return s.gcra.tryAcquire().allowed();
  }

  @Benchmark
  @Threads(16)
  public boolean gcraContendedOneKey(Shared s) {
    return s.gcra.tryAcquire().allowed();
  }

  @Benchmark
  public boolean packedUncontended(Shared s) {
    return s.packed.tryAcquire().allowed();
  }

  @Benchmark
  @Threads(16)
  public boolean packedContendedOneKey(Shared s) {
    return s.packed.tryAcquire().allowed();
  }

  @Benchmark
  public boolean bucket4jUncontended(Shared s) {
    return s.bucket4j.tryConsume(1);
  }

  @Benchmark
  @Threads(16)
  public boolean bucket4jContendedOneKey(Shared s) {
    return s.bucket4j.tryConsume(1);
  }

  @Benchmark
  public boolean keyedManyKeys(Shared s) {
    return s.keyed.tryAcquire(ThreadLocalRandom.current().nextInt(100_000), PLAN).allowed();
  }

  @Benchmark
  public void fixedAcquireRelease(Shared s) {
    s.fixed.tryAcquire().ifPresent(p -> p.onSuccess(1_000));
  }

  @Benchmark
  public void aimdAcquireRelease(Shared s) {
    s.aimd.tryAcquire().ifPresent(p -> p.onSuccess(1_000));
  }
}
