package dev.keyedge.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FutureFanOutTest extends FanOutContract {
  private final ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();

  @Override
  protected FanOut fanOut() {
    return new FutureFanOut(vt, System::nanoTime, CARRIER);
  }

  @AfterEach
  void close() {
    vt.shutdownNow();
  }

  /** Pitfall (D-13): without the carrier, tasks on the executor do not see the scoped value. */
  @Test
  void withoutTheCarrierTheContextIsMissing() throws Exception {
    var plain = new FutureFanOut(vt, System::nanoTime, ContextCarrier.none());
    Call.Work<String> read = () -> CONTEXT.isBound() ? CONTEXT.get() : "<unbound>";
    var r =
        ScopedValue.where(CONTEXT, "req-42")
            .call(() ->
                plain.all(
                    Call.required("a", read),
                    Call.required("b", read),
                    Call.optional("c", read),
                    in(Duration.ofSeconds(2))));
    assertThat(r.first()).isEqualTo(new CallResult.Ok<>("<unbound>"));
  }

  /** Pitfall: cancel(true) on a supplyAsync future does not interrupt the running task. */
  @Test
  void completableFutureCancelDoesNotInterrupt() throws Exception {
    var started = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    var cf =
        CompletableFuture.supplyAsync(
            () -> {
              started.countDown();
              try {
                Thread.sleep(300);
              } catch (InterruptedException e) {
                interrupted.countDown();
              }
              finished.countDown();
              return 1;
            },
            vt);
    started.await();
    cf.cancel(true);
    assertThat(cf.isCancelled()).isTrue();
    assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(interrupted.getCount()).as("never interrupted").isEqualTo(1);
  }

  @Test
  void deadlineHelpers() {
    long[] now = {1_000};
    var d = Deadline.after(Duration.ofNanos(500), () -> now[0]);
    assertThat(d.remaining(() -> now[0])).isEqualTo(Duration.ofNanos(500));
    assertThat(d.cap(Duration.ofNanos(100), () -> now[0])).isEqualTo(Duration.ofNanos(100));
    assertThat(d.cap(Duration.ofSeconds(1), () -> now[0])).isEqualTo(Duration.ofNanos(500));
    now[0] = 2_000;
    assertThat(d.expired(() -> now[0])).isTrue();
    assertThat(d.remaining(() -> now[0])).isZero();
  }

  @Test
  void callExceptionKinds() {
    assertThat(CallException.status("x", 404, null).clientError()).isTrue();
    assertThat(CallException.status("x", 429, Duration.ofSeconds(2)).clientError()).isFalse();
    assertThat(CallException.status("x", 429, Duration.ofSeconds(2)).retryAfter())
        .contains(Duration.ofSeconds(2));
    assertThat(CallException.status("x", 503, null).clientError()).isFalse();
    assertThat(CallException.io("x", "reset").status()).isEmpty();
    assertThat(CallException.timeout("x").timedOut()).isTrue();
  }
}
