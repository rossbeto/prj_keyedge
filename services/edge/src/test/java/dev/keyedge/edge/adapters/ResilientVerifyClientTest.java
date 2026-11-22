package dev.keyedge.edge.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.keyedge.edge.adapters.resilience.ResilientVerifyClient;
import dev.keyedge.edge.ports.Verdicts;
import dev.keyedge.edge.ports.Verdicts.Unavailable.Reason;
import dev.keyedge.edge.ports.VerifyClient;
import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.KeySecret;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ResilientVerifyClientTest {
  private static final KeyId ID = new KeyId(1, 1);
  private static final KeySecret S = KeySecret.ofBytes(new byte[32]);
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
  private final AtomicLong nanos = new AtomicLong();

  private final VerifyClient delegate =
      (id, s) -> {
        calls.incrementAndGet();
        RuntimeException e = failure.get();
        if (e != null) {
          throw e;
        }
        return new KeyCheck.Unknown(id);
      };

  private ResilientVerifyClient client(Duration openFor, Duration slow, int bulkhead) {
    return new ResilientVerifyClient(
        delegate,
        new ResilientVerifyClient.Settings(10, 5, 50f, slow, 80f, openFor, 2, bulkhead),
        nanos::get);
  }

  @Test
  void opensAfterTheFailureRateAndFailsFastWithRetryAfter() {
    var c = client(Duration.ofSeconds(10), Duration.ofSeconds(5), 8);
    failure.set(new Verdicts.Unavailable("down", null));
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> c.verify(ID, S)).isInstanceOf(Verdicts.Unavailable.class);
    }
    assertThat(c.state()).isEqualTo("OPEN");
    nanos.addAndGet(Duration.ofSeconds(3).toNanos());
    int before = calls.get();
    assertThatThrownBy(() -> c.verify(ID, S))
        .isInstanceOfSatisfying(
            Verdicts.Unavailable.class,
            u -> {
              assertThat(u.reason()).isEqualTo(Reason.CIRCUIT_OPEN);
              assertThat(u.retryAfter()).contains(Duration.ofSeconds(7));
            });
    assertThat(calls.get()).isEqualTo(before); // not called while open
  }

  @Test
  void contractErrorsDoNotOpenTheBreaker() {
    var c = client(Duration.ofSeconds(10), Duration.ofSeconds(5), 8);
    failure.set(new Verdicts.Unavailable("HTTP 401", null, Reason.CONTRACT, null));
    for (int i = 0; i < 20; i++) {
      assertThatThrownBy(() -> c.verify(ID, S)).isInstanceOf(Verdicts.Unavailable.class);
    }
    assertThat(c.state()).isEqualTo("CLOSED");
    assertThat(calls.get()).isEqualTo(20);
  }

  @Test
  void halfOpenClosesAgainOnSuccess() throws Exception {
    var c = client(Duration.ofMillis(50), Duration.ofSeconds(5), 8);
    failure.set(new Verdicts.Unavailable("down", null));
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> c.verify(ID, S)).isInstanceOf(Verdicts.Unavailable.class);
    }
    assertThat(c.state()).isEqualTo("OPEN");
    failure.set(null);
    Thread.sleep(80); // the breaker's own clock: waitDurationInOpenState
    assertThat(c.verify(ID, S)).isInstanceOf(KeyCheck.Unknown.class);
    assertThat(c.verify(ID, S)).isInstanceOf(KeyCheck.Unknown.class);
    assertThat(c.state()).isEqualTo("CLOSED");
  }

  @Test
  void slowCallsOpenItToo() {
    var c = client(Duration.ofSeconds(10), Duration.ofMillis(5), 8);
    VerifyClient slow =
        (id, s) -> {
          try {
            Thread.sleep(10);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new KeyCheck.Unknown(id);
        };
    var s =
        new ResilientVerifyClient(
            slow,
            new ResilientVerifyClient.Settings(
                10, 5, 50f, Duration.ofMillis(5), 80f, Duration.ofSeconds(10), 2, 8),
            nanos::get);
    for (int i = 0; i < 5; i++) {
      s.verify(ID, S);
    }
    assertThat(s.state()).isEqualTo("OPEN");
    assertThat(c.state()).isEqualTo("CLOSED");
  }

  @Test
  void aFullBulkheadRejectsImmediatelyWithoutCountingAsFailure() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    VerifyClient blocking =
        (id, s) -> {
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new KeyCheck.Unknown(id);
        };
    var c =
        new ResilientVerifyClient(
            blocking,
            new ResilientVerifyClient.Settings(
                10, 1, 50f, Duration.ofSeconds(5), 80f, Duration.ofSeconds(10), 2, 1),
            nanos::get);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = pool.submit(() -> c.verify(ID, S));
      entered.await();
      assertThatThrownBy(() -> c.verify(ID, S))
          .isInstanceOfSatisfying(
              Verdicts.Unavailable.class,
              u -> assertThat(u.reason()).isEqualTo(Reason.BULKHEAD_FULL));
      release.countDown();
      assertThat(first.get()).isInstanceOf(KeyCheck.Unknown.class);
    }
    assertThat(c.state()).isEqualTo("CLOSED");
  }
}
