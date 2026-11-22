package dev.keyedge.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.keyformat.RatePlan;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 32 threads hammer one key with time frozen: exactly {@code burst} permits may be granted. A smoke
 * test only (it shows the code usually works); jcstress is what proves the interleavings.
 */
class ConcurrencySmokeTest {
  @ParameterizedTest
  @ValueSource(strings = {"gcra", "packed"})
  void frozenTimeAdmitsExactlyTheBurst(String kind) throws Exception {
    Ticker frozen = () -> 42_000_000_000L;
    var plan = new RatePlan(1, 1000);
    Function<RatePlan, RateLimiter> make =
        kind.equals("gcra") ? p -> new Gcra(p, frozen) : p -> new PackedTokenBucket(p, frozen);
    RateLimiter limiter = make.apply(plan);
    var start = new CountDownLatch(1);
    var admitted = new LongAdder();
    try (var pool = Executors.newFixedThreadPool(32)) {
      for (int t = 0; t < 32; t++) {
        pool.submit(() -> {
          start.await();
          for (int i = 0; i < 10_000; i++) {
            if (limiter.tryAcquire().allowed()) {
              admitted.increment();
            }
          }
          return null;
        });
      }
      start.countDown();
    }
    assertThat(admitted.sum()).isEqualTo(1000);
  }
}
