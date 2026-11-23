package dev.keyedge.fanout.structured;

import dev.keyedge.fanout.Call;
import dev.keyedge.fanout.CallResult;
import dev.keyedge.fanout.Deadline;
import dev.keyedge.fanout.FanOut;
import dev.keyedge.fanout.Tri;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.function.LongSupplier;

/**
 * Fan-out with {@link StructuredTaskScope} (JDK 25 preview API: {@code open(Joiner, config)},
 * {@code Joiner.allUntil}). Each fork maps its own outcome to a {@link CallResult} and never
 * throws, so the joiner sees every completion; it stops the scope (interrupting the others) as soon
 * as a required call did not succeed. The scope's timeout is the deadline. When {@code all}
 * returns, every fork has finished: no task outlives the request, and scoped values are inherited
 * by forks without any re-binding (D-12, D-13).
 */
public final class StructuredFanOut implements FanOut {
  private final LongSupplier nanos;

  public StructuredFanOut(LongSupplier nanos) {
    this.nanos = nanos;
  }

  private record Tagged(boolean required, CallResult<?> result) {}

  @Override
  @SuppressWarnings("unchecked")
  public <A, B, C> Tri<A, B, C> all(Call<A> a, Call<B> b, Call<C> c, Deadline deadline) {
    Joiner<Tagged, ?> stopOnRequiredFailure =
        Joiner.allUntil(s ->
            s.state() == Subtask.State.SUCCESS && s.get().required() && !s.get().result().ok());
    var timeout = deadline.remaining(nanos);
    Subtask<Tagged> sa;
    Subtask<Tagged> sb;
    Subtask<Tagged> sc;
    boolean timedOut = false;
    try (var scope =
        StructuredTaskScope.open(stopOnRequiredFailure, cfg -> cfg.withTimeout(timeout))) {
      sa = scope.fork(() -> new Tagged(a.required(), CallResult.of(a)));
      sb = scope.fork(() -> new Tagged(b.required(), CallResult.of(b)));
      sc = scope.fork(() -> new Tagged(c.required(), CallResult.of(c)));
      try {
        scope.join();
      } catch (StructuredTaskScope.TimeoutException e) {
        timedOut = true;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    } // close(): every fork has finished here
    return new Tri<>(
        (CallResult<A>) result(sa, timedOut),
        (CallResult<B>) result(sb, timedOut),
        (CallResult<C>) result(sc, timedOut));
  }

  private static CallResult<?> result(Subtask<Tagged> s, boolean timedOut) {
    if (s.state() == Subtask.State.SUCCESS) {
      CallResult<?> r = s.get().result();
      // a fork interrupted by the timeout reports itself as cancelled: it was the deadline
      return timedOut && r instanceof CallResult.Skipped<?> ? new CallResult.TimedOut<>() : r;
    }
    return timedOut ? new CallResult.TimedOut<>() : new CallResult.Skipped<>("cancelled");
  }
}
