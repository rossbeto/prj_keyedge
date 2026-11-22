package dev.keyedge.edge.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.keyedge.edge.adapters.cache.CaffeineVerdicts;
import dev.keyedge.edge.ports.Verdicts;
import dev.keyedge.edge.ports.VerifyClient;
import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.KeySecret;
import dev.keyedge.keyformat.RatePlan;
import dev.keyedge.keyformat.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CaffeineVerdictsTest {
  private static final KeyId ID = new KeyId(1, 1);
  private static final KeySecret GOOD = KeySecret.ofBytes(new byte[32]);
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-11-23T10:00:00Z"));
  private final AtomicInteger calls = new AtomicInteger();

  private VerifyClient slowValid(long delayMs) {
    return (id, s) -> {
      calls.incrementAndGet();
      try {
        Thread.sleep(delayMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return s.equals(GOOD)
          ? new KeyCheck.Valid(
              new TenantId("acme"), id, Set.of(), new RatePlan(1, 1), Duration.ofSeconds(10))
          : new KeyCheck.Unknown(id);
    };
  }

  private CaffeineVerdicts cache(VerifyClient client, Duration wait) {
    return new CaffeineVerdicts(
        client,
        now::get,
        Executors.newVirtualThreadPerTaskExecutor(),
        1000,
        wait,
        Duration.ofSeconds(5),
        0.7);
  }

  @Test
  void concurrentMissesShareOneLoad() throws Exception {
    var cache = cache(slowValid(200), Duration.ofSeconds(2));
    var start = new CountDownLatch(1);
    var valid = new AtomicInteger();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 500; i++) {
        pool.submit(() -> {
          start.await();
          if (cache.verdict(ID, GOOD) instanceof KeyCheck.Valid) {
            valid.incrementAndGet();
          }
          return null;
        });
      }
      start.countDown();
    }
    assertThat(valid.get()).isEqualTo(500);
    assertThat(calls.get()).isEqualTo(1);
    assertThat(cache.loads()).isEqualTo(1);
  }

  @Test
  void theSecretIsPartOfTheKey() {
    var cache = cache(slowValid(0), Duration.ofSeconds(1));
    assertThat(cache.verdict(ID, GOOD)).isInstanceOf(KeyCheck.Valid.class);
    byte[] other = new byte[32];
    other[0] = 1;
    assertThat(cache.verdict(ID, KeySecret.ofBytes(other)))
        .as("cached Valid must not admit another secret")
        .isInstanceOf(KeyCheck.Unknown.class);
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void aSlowLoadTimesOutTheWaiterButIsNotLost() throws Exception {
    var cache = cache(slowValid(400), Duration.ofMillis(100));
    assertThatThrownBy(() -> cache.verdict(ID, GOOD))
        .isInstanceOf(Verdicts.Unavailable.class)
        .hasMessageContaining("timed out");
    Thread.sleep(450);
    assertThat(cache.verdict(ID, GOOD)).isInstanceOf(KeyCheck.Valid.class);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void loadFailuresSurfaceAsUnavailableAndAreRetried() {
    var fail = new AtomicInteger(1);
    VerifyClient flaky =
        (id, s) -> {
          calls.incrementAndGet();
          if (fail.getAndDecrement() > 0) {
            throw new Verdicts.Unavailable("boom", null);
          }
          return new KeyCheck.Unknown(id);
        };
    var cache = cache(flaky, Duration.ofSeconds(1));
    assertThatThrownBy(() -> cache.verdict(ID, GOOD)).isInstanceOf(Verdicts.Unavailable.class);
    assertThat(cache.verdict(ID, GOOD)).isInstanceOf(KeyCheck.Unknown.class);
    VerifyClient bug =
        (id, s) -> {
          throw new IllegalStateException("bug");
        };
    assertThatThrownBy(() -> cache(bug, Duration.ofSeconds(1)).verdict(ID, GOOD))
        .isInstanceOf(Verdicts.Unavailable.class);
  }

  @Test
  void refreshAheadReloadsBeforeExpiryWhileServingTheOldValue() throws Exception {
    var cache = cache(slowValid(0), Duration.ofSeconds(1));
    assertThat(cache.verdict(ID, GOOD)).isInstanceOf(KeyCheck.Valid.class);
    now.set(now.get().plusSeconds(6)); // < 70 % of the 10 s TTL: no refresh
    cache.verdict(ID, GOOD);
    assertThat(cache.refreshes()).isZero();
    now.set(now.get().plusSeconds(2)); // 8 s > 7 s: refresh-ahead
    assertThat(cache.verdict(ID, GOOD)).isInstanceOf(KeyCheck.Valid.class);
    assertThat(cache.refreshes()).isEqualTo(1);
    for (int i = 0; i < 50 && calls.get() < 2; i++) {
      Thread.sleep(10);
    }
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void rejectionsAreNeverRefreshed() {
    var cache = cache(slowValid(0), Duration.ofSeconds(1));
    byte[] other = new byte[32];
    other[1] = 9;
    var wrong = KeySecret.ofBytes(other);
    cache.verdict(ID, wrong);
    now.set(now.get().plusSeconds(4));
    cache.verdict(ID, wrong);
    assertThat(cache.refreshes()).isZero();
  }
}
