package dev.keyedge.ratelimit.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConcurrencyLimitersTest {
  private static final long MS = 1_000_000;

  @Test
  void fixedLimiterShedsAboveTheLimitAndReleases() {
    var limiter = new FixedLimiter(2);
    var a = limiter.tryAcquire().orElseThrow();
    var b = limiter.tryAcquire().orElseThrow();
    assertThat(limiter.tryAcquire()).isEmpty();
    assertThat(limiter.inFlight()).isEqualTo(2);
    a.onSuccess(5 * MS);
    b.onDropped();
    assertThat(limiter.inFlight()).isZero();
    assertThat(limiter.limit()).isEqualTo(2);
    assertThatThrownBy(() -> new FixedLimiter(0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void doubleReleaseIsANoOpAndCounted() {
    var limiter = new FixedLimiter(1);
    try (var permit = limiter.tryAcquire().orElseThrow()) {
      permit.onSuccess(MS);
    } // close() after onSuccess = second release
    assertThat(limiter.inFlight()).isZero();
    assertThat(limiter.doubleReleases()).isEqualTo(1);
    assertThat(limiter.tryAcquire()).isPresent();
  }

  private static List<Permit> fill(ConcurrencyLimiter limiter) {
    var permits = new ArrayList<Permit>();
    limiter.tryAcquire().ifPresent(permits::add);
    while (permits.size() < limiter.limit()) {
      limiter.tryAcquire().ifPresent(permits::add);
    }
    return permits;
  }

  @Test
  void aimdGrowsOnFastBusySuccessesAndShrinksOnDrops() {
    var limiter = new AimdLimiter(10, 2, 20, 50 * MS, 0.5);
    for (int round = 0; round < 5; round++) {
      fill(limiter).forEach(p -> p.onSuccess(10 * MS));
    }
    assertThat(limiter.limit()).isEqualTo(20); // capped at max
    limiter.tryAcquire().orElseThrow().onDropped();
    assertThat(limiter.limit()).isEqualTo(10);
    limiter.tryAcquire().orElseThrow().onSuccess(80 * MS); // slow success also backs off
    assertThat(limiter.limit()).isEqualTo(5);
    for (int i = 0; i < 10; i++) {
      limiter.tryAcquire().orElseThrow().onDropped();
    }
    assertThat(limiter.limit()).isEqualTo(2); // floored at min
  }

  @Test
  void aimdDoesNotGrowWhenTheLimitIsUnused() {
    var limiter = new AimdLimiter(10, 1, 100, 50 * MS, 0.9);
    for (int i = 0; i < 100; i++) {
      limiter.tryAcquire().orElseThrow().onSuccess(MS); // one at a time: 1 in flight of 10
    }
    assertThat(limiter.limit()).isEqualTo(10);
  }

  @Test
  void gradientFallsWhenLatencyRisesAndRecovers() {
    var limiter = new GradientLimiter(50, 5, 200, 20, 0.2);
    for (int i = 0; i < 50; i++) {
      limiter.tryAcquire().orElseThrow().onSuccess(10 * MS);
    }
    int healthy = limiter.limit();
    for (int i = 0; i < 50; i++) {
      limiter.tryAcquire().orElseThrow().onSuccess(40 * MS); // latency x4: gradient 0.5
    }
    int congested = limiter.limit();
    assertThat(congested).isLessThan(healthy);
    limiter.tryAcquire().orElseThrow().onDropped();
    assertThat(limiter.limit()).isLessThan(congested);
    assertThat(limiter.limit()).isGreaterThanOrEqualTo(5);
  }

  @Test
  void boundaryConfigurationsAreAccepted() {
    assertThat(new AimdLimiter(3, 3, 3, MS, 0.5).limit()).isEqualTo(3);
    assertThat(new GradientLimiter(3, 3, 3, 1, 1.0).limit()).isEqualTo(3);
    assertThat(new FixedLimiter(1).limit()).isEqualTo(1);
  }

  @Test
  void aimdTreatsExactlyTheThresholdAsFast() {
    var limiter = new AimdLimiter(2, 1, 10, 50 * MS, 0.5);
    var a = limiter.tryAcquire().orElseThrow();
    limiter.tryAcquire().orElseThrow().onIgnore();
    a.onSuccess(50 * MS); // == threshold: not slow; one of two in flight at start = in use
    assertThat(limiter.limit()).isEqualTo(3);
  }

  @Test
  void gradientIgnoresIdenticalLatencyAndGrowsSlowly() {
    var limiter = new GradientLimiter(10, 1, 1000, 10, 0.5);
    for (int i = 0; i < 5; i++) {
      limiter.tryAcquire().orElseThrow().onSuccess(10 * MS);
    }
    assertThat(limiter.limit()).isBetween(11, 40); // gradient 1: grows by the queue allowance
  }

  @Test
  void constructorsValidate() {
    assertThatThrownBy(() -> new AimdLimiter(1, 2, 3, 1, 0.5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AimdLimiter(2, 1, 3, 1, 1.0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GradientLimiter(1, 1, 2, 0, 0.5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GradientLimiter(1, 1, 2, 5, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Regression (Phase 010 load run): latency spread of 1x..2x the minimum is normal for a fan-out.
   * Without tolerance the limit collapses to its minimum; with tolerance 2 it holds or grows.
   */
  @Test
  void toleranceKeepsTheLimitUnderNormalLatencySpread() {
    var strict = new GradientLimiter(50, 10, 500, 100, 0.2);
    var tolerant = new GradientLimiter(50, 10, 500, 100, 0.2, 2.0);
    long[] rtts = {10_000_000, 15_000_000, 20_000_000, 12_000_000, 18_000_000};
    for (int i = 0; i < 500; i++) {
      for (var l : List.of(strict, tolerant)) {
        l.tryAcquire().orElseThrow().onSuccess(rtts[i % rtts.length]);
      }
    }
    assertThat(strict.limit()).isLessThan(30);
    assertThat(tolerant.limit()).isGreaterThanOrEqualTo(50);
    // real congestion (latency 3x the minimum) still shrinks the tolerant limiter, within the
    // min-RTT window (100 samples; after that the slower latency becomes the new baseline)
    for (int i = 0; i < 80; i++) {
      tolerant.tryAcquire().orElseThrow().onSuccess(30_000_000 + (i % 2) * 5_000_000);
    }
    assertThat(tolerant.limit()).isLessThan(50);
    assertThatThrownBy(() -> new GradientLimiter(5, 1, 10, 5, 0.5, 0.5))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
