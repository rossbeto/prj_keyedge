package dev.keyedge.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.keyedge.keyformat.RatePlan;
import java.time.Duration;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The same behaviour contract for both limiters. */
class LimiterBehaviourTest {
  static Stream<Arguments> limiters() {
    return Stream.of(
        Arguments.of("gcra", (BiFunction<RatePlan, Ticker, RateLimiter>) Gcra::new),
        Arguments.of("packed", (BiFunction<RatePlan, Ticker, RateLimiter>) PackedTokenBucket::new));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("limiters")
  void burstThenSustainedRate(String name, BiFunction<RatePlan, Ticker, RateLimiter> make) {
    var ticker = new ManualTicker(1_000_000_000L);
    var limiter = make.apply(new RatePlan(10, 5), ticker);
    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryAcquire().allowed()).as("burst permit %d", i).isTrue();
    }
    var rejected = limiter.tryAcquire();
    assertThat(rejected).isInstanceOf(Decision.Rejected.class);
    assertThat(((Decision.Rejected) rejected).retryAfter())
        .isBetween(Duration.ofMillis(99), Duration.ofMillis(101));
    ticker.advanceMillis(100);
    assertThat(limiter.tryAcquire().allowed()).isTrue();
    assertThat(limiter.tryAcquire().allowed()).isFalse();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("limiters")
  void remainingCountsDown(String name, BiFunction<RatePlan, Ticker, RateLimiter> make) {
    var limiter = make.apply(new RatePlan(1, 3), new ManualTicker(0));
    assertThat(limiter.tryAcquire()).isEqualTo(new Decision.Allowed(2));
    assertThat(limiter.tryAcquire()).isEqualTo(new Decision.Allowed(1));
    assertThat(limiter.tryAcquire()).isEqualTo(new Decision.Allowed(0));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("limiters")
  void idleTimeRefillsOnlyUpToTheBurst(
      String name, BiFunction<RatePlan, Ticker, RateLimiter> make) {
    var ticker = new ManualTicker(0);
    var limiter = make.apply(new RatePlan(100, 3), ticker);
    ticker.advanceMillis(60_000);
    int allowed = 0;
    while (limiter.tryAcquire().allowed()) {
      allowed++;
    }
    assertThat(allowed).isEqualTo(3);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("limiters")
  void timeGoingBackwardsNeverRefills(String name, BiFunction<RatePlan, Ticker, RateLimiter> make) {
    // Same effect as a thread whose clock reading is older than the state another thread just
    // wrote.
    var ticker = new ManualTicker(10_000_000_000L);
    var limiter = make.apply(new RatePlan(1, 1), ticker);
    assertThat(limiter.tryAcquire().allowed()).isTrue();
    ticker.set(9_000_000_000L);
    assertThat(limiter.tryAcquire().allowed()).isFalse();
    ticker.set(10_000_000_000L);
    assertThat(limiter.tryAcquire().allowed()).isFalse();
  }

  @Test
  void packedBucketRejectsPlansOutsideItsRange() {
    assertThatThrownBy(
            () -> new PackedTokenBucket(new RatePlan(1, PackedTokenBucket.MAX_BURST + 1), () -> 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new PackedTokenBucket(new RatePlan(PackedTokenBucket.MAX_RATE + 1, 1), () -> 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            new PackedTokenBucket(
                    new RatePlan(PackedTokenBucket.MAX_RATE, PackedTokenBucket.MAX_BURST), () -> 0)
                .tryAcquire()
                .allowed())
        .isTrue();
  }

  @Test
  void packedTimeFieldWrapsWithoutRebasing() {
    long wrap = (1L << PackedTokenBucket.TIME_BITS) * 1000; // nanos at which the micros field wraps
    var ticker = new ManualTicker(wrap - 1_000_000); // 1 ms before the wrap
    var limiter = new PackedTokenBucket(new RatePlan(1000, 1), ticker);
    assertThat(limiter.tryAcquire().allowed()).isTrue();
    assertThat(limiter.tryAcquire().allowed()).isFalse();
    ticker.advanceMillis(2); // crosses the wrap: elapsed is still +2 ms
    assertThat(limiter.tryAcquire().allowed()).isTrue();
  }

  @Test
  void packedBucketKeepsFractionalRefillTime() {
    // 3 permits/s: one token every 333.33 ms. Refill in many small steps must add up exactly:
    // the unused fraction of elapsed time is kept, not dropped at each call.
    var ticker = new ManualTicker(0);
    var bucket = new PackedTokenBucket(new RatePlan(3, 1), ticker);
    assertThat(bucket.tryAcquire().allowed()).isTrue();
    for (int i = 0; i < 33; i++) {
      ticker.advanceMillis(10);
      assertThat(bucket.tryAcquire().allowed()).as("after %d ms", (i + 1) * 10).isFalse();
    }
    ticker.advanceMillis(4); // 334 ms in total
    assertThat(bucket.tryAcquire().allowed()).isTrue();
  }

  @Test
  void packedRejectionReportsTheWaitForOneToken() {
    var ticker = new ManualTicker(0);
    var bucket = new PackedTokenBucket(new RatePlan(4, 1), ticker);
    bucket.tryAcquire();
    ticker.advanceMillis(100); // 0.4 token
    var rejected = (Decision.Rejected) bucket.tryAcquire();
    assertThat(rejected.retryAfter()).isEqualTo(Duration.ofMillis(150));
  }

  @Test
  void packingRoundTrips() {
    long packed = PackedTokenBucket.pack(1234, 5678);
    assertThat(PackedTokenBucket.tokens(packed)).isEqualTo(1234);
    assertThat(PackedTokenBucket.time(packed)).isEqualTo(5678);
  }

  @Test
  void systemTickerIsMonotonic() {
    var t = Ticker.system();
    long a = t.nanos();
    assertThat(t.nanos()).isGreaterThanOrEqualTo(a);
  }
}
