package dev.keyedge.quotes.lab.pitfalls;

import dev.keyedge.fanout.Call;
import dev.keyedge.fanout.CallResult;
import dev.keyedge.fanout.Deadline;
import dev.keyedge.fanout.FanOut;
import dev.keyedge.fanout.Tri;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * DELIBERATELY WRONG (lab pitfall): the "obvious" CompletableFuture fan-out. On a required failure
 * it calls {@code cancel(true)} on the others — which marks the futures cancelled but does not
 * interrupt the running {@code supplyAsync} tasks: they keep their upstream connections open.
 */
public final class NaiveCompletableFanOut implements FanOut {
  private final ExecutorService executor;

  public NaiveCompletableFanOut(ExecutorService executor) {
    this.executor = executor;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <A, B, C> Tri<A, B, C> all(Call<A> a, Call<B> b, Call<C> c, Deadline deadline) {
    List<Call<?>> calls = List.of(a, b, c);
    List<CompletableFuture<CallResult<?>>> fs =
        calls.stream()
            .map(call ->
                CompletableFuture.<CallResult<?>>supplyAsync(() -> CallResult.of(call), executor))
            .toList();
    var failedRequired = new CompletableFuture<Void>();
    for (int i = 0; i < 3; i++) {
      Call<?> call = calls.get(i);
      var unused =
          fs.get(i)
              .thenAccept(r -> {
                if (call.required() && !r.ok()) {
                  failedRequired.complete(null);
                }
              });
    }
    try {
      CompletableFuture.anyOf(
              CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new)), failedRequired)
          .get(deadline.remaining(System::nanoTime).toNanos(), TimeUnit.NANOSECONDS);
    } catch (Exception e) {
      // deadline or interruption: fall through
    }
    CallResult<?>[] out = new CallResult<?>[3];
    for (int i = 0; i < 3; i++) {
      var f = fs.get(i);
      if (f.isDone() && !f.isCancelled()) {
        out[i] = f.join();
      } else {
        f.cancel(true); // does NOT interrupt the task
        out[i] = new CallResult.Skipped<>("cancelled");
      }
    }
    return new Tri<>((CallResult<A>) out[0], (CallResult<B>) out[1], (CallResult<C>) out[2]);
  }
}
