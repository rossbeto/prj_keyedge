package dev.keyedge.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.keyformat.RatePlan;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class KeyedLimitersTest {
  private final ManualTicker ticker = new ManualTicker(0);

  @Test
  void keysAreIndependent() {
    var limiters = new KeyedLimiters<String>(100, Duration.ofMinutes(10), p -> new Gcra(p, ticker));
    var plan = new RatePlan(1, 1);
    assertThat(limiters.tryAcquire("a", plan).allowed()).isTrue();
    assertThat(limiters.tryAcquire("a", plan).allowed()).isFalse();
    assertThat(limiters.tryAcquire("b", plan).allowed()).isTrue();
  }

  @Test
  void aChangedPlanReplacesTheLimiter() {
    var limiters = new KeyedLimiters<String>(100, Duration.ofMinutes(10), p -> new Gcra(p, ticker));
    assertThat(limiters.tryAcquire("a", new RatePlan(1, 1)).allowed()).isTrue();
    assertThat(limiters.tryAcquire("a", new RatePlan(1, 1)).allowed()).isFalse();
    assertThat(limiters.tryAcquire("a", new RatePlan(1, 5)).allowed()).isTrue();
    var same = limiters.limiterFor("a", new RatePlan(1, 5));
    assertThat(limiters.limiterFor("a", new RatePlan(1, 5))).isSameAs(same);
  }

  @Test
  void sizeIsBounded() {
    var limiters = new KeyedLimiters<Integer>(50, Duration.ofMinutes(10), p -> new Gcra(p, ticker));
    for (int i = 0; i < 1000; i++) {
      limiters.tryAcquire(i, new RatePlan(1, 1));
    }
    assertThat(limiters.size()).isLessThanOrEqualTo(50);
  }
}
