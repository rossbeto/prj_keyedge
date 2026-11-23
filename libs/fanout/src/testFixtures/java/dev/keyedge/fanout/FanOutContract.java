package dev.keyedge.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The {@link FanOut} contract, run against every adapter. A subclass provides the adapter, built
 * with a {@link ContextCarrier} for {@link #CONTEXT} where the adapter needs one.
 */
public abstract class FanOutContract {
  /** The request context the calls must see. */
  public static final ScopedValue<String> CONTEXT = ScopedValue.newInstance();

  /** Re-binds {@link #CONTEXT} in tasks on other threads. */
  public static final ContextCarrier CARRIER =
      () -> {
        String captured = CONTEXT.isBound() ? CONTEXT.get() : null;
        return new ContextCarrier.Binder() {
          @Override
          public <T> java.util.concurrent.Callable<T> wrap(java.util.concurrent.Callable<T> task) {
            return captured == null
                ? task
                : () -> ScopedValue.where(CONTEXT, captured).call(task::call);
          }
        };
      };

  protected abstract FanOut fanOut();

  protected static Deadline in(Duration d) {
    return Deadline.after(d, System::nanoTime);
  }

  private static <T> Call.Work<T> after(long ms, T value) {
    return () -> {
      Thread.sleep(ms);
      return value;
    };
  }

  private static <T> Call.Work<T> failAfter(long ms) {
    return () -> {
      Thread.sleep(ms);
      throw CallException.status("upstream", 500, null);
    };
  }

  @Test
  void oneResultPerCallInOrder() {
    var r =
        fanOut()
            .all(
                Call.required("a", after(10, "A")),
                Call.required("b", after(5, 2)),
                Call.optional("c", after(1, 3.0)),
                in(Duration.ofSeconds(2)));
    assertThat(r.first()).isEqualTo(new CallResult.Ok<>("A"));
    assertThat(r.second()).isEqualTo(new CallResult.Ok<>(2));
    assertThat(r.third()).isEqualTo(new CallResult.Ok<>(3.0));
  }

  @Test
  void returnsByTheDeadline() {
    long start = System.nanoTime();
    var r =
        fanOut()
            .all(
                Call.required("a", after(2_000, "A")),
                Call.required("b", after(2_000, "B")),
                Call.optional("c", after(2_000, "C")),
                in(Duration.ofMillis(300)));
    long tookMs = (System.nanoTime() - start) / 1_000_000;
    assertThat(tookMs).isLessThan(400);
    assertThat(r.first()).isInstanceOf(CallResult.TimedOut.class);
    assertThat(r.second()).isInstanceOf(CallResult.TimedOut.class);
    assertThat(r.third()).isInstanceOf(CallResult.TimedOut.class);
  }

  @Test
  void aRequiredFailureCancelsTheOthers() throws Exception {
    var interrupted = new CountDownLatch(1);
    Call.Work<String> slow =
        () -> {
          try {
            Thread.sleep(2_000);
            return "late";
          } catch (InterruptedException e) {
            interrupted.countDown();
            throw e;
          }
        };
    long start = System.nanoTime();
    var r =
        fanOut()
            .all(
                Call.required("pricing", failAfter(50)),
                Call.required("stock", slow),
                Call.optional("fx", after(1, 1.0)),
                in(Duration.ofSeconds(5)));
    long tookMs = (System.nanoTime() - start) / 1_000_000;
    assertThat(tookMs)
        .isLessThan(1_000); // the slow sibling takes 2 s: returning early is the point
    assertThat(r.first()).isInstanceOf(CallResult.Failed.class);
    assertThat(r.second()).isInstanceOf(CallResult.Skipped.class);
    assertThat(r.third()).isEqualTo(new CallResult.Ok<>(1.0));
    assertThat(interrupted.await(1, TimeUnit.SECONDS)).as("sibling interrupted").isTrue();
  }

  @Test
  void anOptionalFailureCancelsNothing() {
    var r =
        fanOut()
            .all(
                Call.required("a", after(100, "A")),
                Call.required("b", after(100, "B")),
                Call.optional("c", failAfter(1)),
                in(Duration.ofSeconds(2)));
    assertThat(r.first()).isEqualTo(new CallResult.Ok<>("A"));
    assertThat(r.second()).isEqualTo(new CallResult.Ok<>("B"));
    assertThat(r.third()).isInstanceOf(CallResult.Failed.class);
  }

  @Test
  void theCallersScopedContextIsVisibleInTheCalls() throws Exception {
    Call.Work<String> read = () -> CONTEXT.isBound() ? CONTEXT.get() : "<unbound>";
    var r =
        ScopedValue.where(CONTEXT, "req-42")
            .call(() ->
                fanOut()
                    .all(
                        Call.required("a", read),
                        Call.required("b", read),
                        Call.optional("c", read),
                        in(Duration.ofSeconds(2))));
    assertThat(r.first()).isEqualTo(new CallResult.Ok<>("req-42"));
    assertThat(r.second()).isEqualTo(new CallResult.Ok<>("req-42"));
    assertThat(r.third()).isEqualTo(new CallResult.Ok<>("req-42"));
  }

  /** Instant calls, many times: completion signals must never outrun the results (regression). */
  @Test
  void instantCallsAlwaysYieldTheirResults() {
    for (int i = 0; i < 2_000; i++) {
      var r =
          fanOut()
              .all(
                  Call.required("a", () -> "A"),
                  Call.required("b", () -> "B"),
                  Call.optional("c", () -> "C"),
                  in(Duration.ofSeconds(2)));
      assertThat(r.first()).isEqualTo(new CallResult.Ok<>("A"));
      assertThat(r.second()).isEqualTo(new CallResult.Ok<>("B"));
      assertThat(r.third()).isEqualTo(new CallResult.Ok<>("C"));
    }
  }

  @Test
  void exceptionsBecomeValues() {
    var done = new AtomicBoolean();
    var r =
        fanOut()
            .all(
                Call.required(
                    "a",
                    () -> {
                      throw new IllegalStateException("bug");
                    }),
                Call.optional(
                    "b",
                    () -> {
                      throw CallException.timeout("b");
                    }),
                Call.optional(
                    "c",
                    () -> {
                      done.set(true);
                      return 1;
                    }),
                in(Duration.ofSeconds(2)));
    assertThat(r.first())
        .isEqualTo(new CallResult.Failed<>("IllegalStateException", java.util.Optional.empty()));
    assertThat(r.second()).isInstanceOfAny(CallResult.TimedOut.class, CallResult.Skipped.class);
  }
}
