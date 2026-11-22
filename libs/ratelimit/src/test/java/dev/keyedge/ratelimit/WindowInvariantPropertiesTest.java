package dev.keyedge.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.keyformat.RatePlan;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * For any arrival pattern, the admits between any two admitted requests t_i ≤ t_j never exceed
 * {@code burst + rate × (t_j − t_i)}: the definition of a rate limit, checked on both limiters.
 */
class WindowInvariantPropertiesTest {
  @Property(tries = 500)
  void gcraNeverExceedsTheEnvelope(
      @ForAll @IntRange(min = 1, max = 50) int rate,
      @ForAll @IntRange(min = 1, max = 20) int burst,
      @ForAll @Size(min = 1, max = 300) List<@IntRange(min = 0, max = 200) Integer> gapsMillis) {
    check(new RatePlan(rate, burst), gapsMillis, true);
  }

  @Property(tries = 500)
  void packedBucketNeverExceedsTheEnvelope(
      @ForAll @IntRange(min = 1, max = 50) int rate,
      @ForAll @IntRange(min = 1, max = 20) int burst,
      @ForAll @Size(min = 1, max = 300) List<@IntRange(min = 0, max = 200) Integer> gapsMillis) {
    check(new RatePlan(rate, burst), gapsMillis, false);
  }

  private static void check(RatePlan plan, List<Integer> gapsMillis, boolean gcra) {
    var ticker = new ManualTicker(5_000_000_000L);
    RateLimiter limiter = gcra ? new Gcra(plan, ticker) : new PackedTokenBucket(plan, ticker);
    List<Long> admitted = new ArrayList<>();
    for (int gap : gapsMillis) {
      ticker.advanceMillis(gap);
      if (limiter.tryAcquire().allowed()) {
        admitted.add(ticker.nanos());
      }
    }
    for (int i = 0; i < admitted.size(); i++) {
      for (int j = i; j < admitted.size(); j++) {
        double seconds = (admitted.get(j) - admitted.get(i)) / 1e9;
        assertThat((double) (j - i + 1))
            .isLessThanOrEqualTo(plan.burst() + plan.permitsPerSecond() * seconds + 1e-9);
      }
    }
  }
}
