package dev.keyedge.quotes.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.keyedge.fanout.CallException;
import dev.keyedge.fanout.Deadline;
import dev.keyedge.quotes.adapters.auth.SingleFlightTokenProvider;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SingleFlightTokenProviderTest {
  private final AtomicLong now = new AtomicLong();
  private final AtomicInteger fetched = new AtomicInteger();

  private SingleFlightTokenProvider provider(long fetchMs, Duration lifetime) {
    return new SingleFlightTokenProvider(
        () -> {
          int n = fetched.incrementAndGet();
          Thread.sleep(fetchMs);
          return new SingleFlightTokenProvider.Issued("t" + n, lifetime);
        },
        Executors.newVirtualThreadPerTaskExecutor(),
        now::get,
        0.8);
  }

  private Deadline in(Duration d) {
    return Deadline.after(d, now::get);
  }

  @Test
  void concurrentCallersShareOneFetch() throws Exception {
    var p = provider(100, Duration.ofMinutes(5));
    var start = new CountDownLatch(1);
    var tokens = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 200; i++) {
        pool.submit(() -> {
          start.await();
          tokens.add(p.token(in(Duration.ofSeconds(5))));
          return null;
        });
      }
      start.countDown();
    }
    assertThat(fetched.get()).isEqualTo(1);
    assertThat(tokens).containsExactly("t1");
    assertThat(p.fetches()).isEqualTo(1);
  }

  @Test
  void refreshAheadServesTheOldTokenWhileOneRefreshRuns() throws Exception {
    var p = provider(200, Duration.ofSeconds(10));
    assertThat(p.token(in(Duration.ofSeconds(5)))).isEqualTo("t1");
    now.addAndGet(Duration.ofSeconds(9).toNanos()); // past 80 %, before expiry
    for (int i = 0; i < 50; i++) {
      assertThat(p.token(in(Duration.ofSeconds(5)))).isEqualTo("t1"); // nobody waits
    }
    Thread.sleep(400);
    assertThat(fetched.get()).isEqualTo(2);
    assertThat(p.token(in(Duration.ofSeconds(5)))).isEqualTo("t2");
  }

  @Test
  void aWaiterTimesOutAloneAndTheFetchStillCompletes() throws Exception {
    var p =
        new SingleFlightTokenProvider(
            () -> {
              int n = fetched.incrementAndGet();
              Thread.sleep(300);
              return new SingleFlightTokenProvider.Issued("t" + n, Duration.ofMinutes(5));
            },
            Executors.newVirtualThreadPerTaskExecutor(),
            System::nanoTime,
            0.8);
    long start = System.nanoTime();
    assertThatThrownBy(() -> p.token(Deadline.after(Duration.ofMillis(50), System::nanoTime)))
        .isInstanceOf(CallException.class);
    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(250);
    assertThat(p.token(Deadline.after(Duration.ofSeconds(5), System::nanoTime))).isEqualTo("t1");
    assertThat(fetched.get()).isEqualTo(1);
  }

  @Test
  void invalidateForcesOneNewFetch() throws Exception {
    var p = provider(0, Duration.ofMinutes(5));
    String t1 = p.token(in(Duration.ofSeconds(1)));
    p.invalidate("not-current"); // ignored
    assertThat(p.token(in(Duration.ofSeconds(1)))).isEqualTo(t1);
    p.invalidate(t1);
    assertThat(p.token(in(Duration.ofSeconds(1)))).isEqualTo("t2");
    assertThat(p.tokenAsync().get()).isEqualTo("t2");
  }

  @Test
  void aFailedFetchIsRetriedByTheNextCaller() throws Exception {
    var calls = new AtomicInteger();
    var p =
        new SingleFlightTokenProvider(
            () -> {
              if (calls.incrementAndGet() == 1) {
                throw new java.io.IOException("down");
              }
              return new SingleFlightTokenProvider.Issued("ok", Duration.ofMinutes(1));
            },
            Runnable::run,
            now::get,
            0.8);
    assertThatThrownBy(() -> p.token(in(Duration.ofSeconds(1)))).isInstanceOf(CallException.class);
    assertThat(p.token(in(Duration.ofSeconds(1)))).isEqualTo("ok");
  }
}
