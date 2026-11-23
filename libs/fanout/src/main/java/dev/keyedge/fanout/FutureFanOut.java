package dev.keyedge.fanout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.LongSupplier;

/**
 * Fan-out on an executor (virtual threads in production). Two things structured concurrency gives
 * for free are explicit here: (1) the caller's context is captured and re-bound in each task
 * ({@link ContextCarrier}); (2) cancellation keeps the {@link Future} from {@code submit}, because
 * {@code CompletableFuture.cancel(true)} does not interrupt a running {@code supplyAsync} task. A
 * cancelled task may still be running when this returns: nothing bounds its lifetime.
 */
public final class FutureFanOut implements FanOut {
  private final ExecutorService executor;
  private final LongSupplier nanos;
  private final ContextCarrier context;

  public FutureFanOut(ExecutorService executor, LongSupplier nanos, ContextCarrier context) {
    this.executor = executor;
    this.nanos = nanos;
    this.context = context;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <A, B, C> Tri<A, B, C> all(Call<A> a, Call<B> b, Call<C> c, Deadline deadline) {
    List<Call<?>> calls = List.of(a, b, c);
    // Each task publishes its own result before signalling: the Future's result is set only after
    // call() returns, so reading it on the signal would race (found by the stack run, see
    // deviations).
    AtomicReferenceArray<CallResult<?>> published = new AtomicReferenceArray<>(3);
    BlockingQueue<Integer> done = new LinkedBlockingQueue<>();
    ContextCarrier.Binder binder = context.capture();
    List<Future<?>> futures = new ArrayList<>(3);
    for (int i = 0; i < 3; i++) {
      int index = i;
      Call<?> call = calls.get(i);
      futures.add(
          executor.submit(
              binder.wrap(() -> {
                published.set(index, CallResult.of(call));
                done.add(index);
                return null;
              })));
    }
    int pending = 3;
    try {
      while (pending > 0) {
        Integer i = done.poll(deadline.remaining(nanos).toNanos(), TimeUnit.NANOSECONDS);
        if (i == null) {
          break; // deadline
        }
        pending--;
        CallResult<?> r = published.get(i);
        if (calls.get(i).required() && !r.ok()) {
          break; // a required call failed: no answer is possible, cancel the rest
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    boolean timedOut = deadline.expired(nanos);
    CallResult<?>[] results = new CallResult<?>[3];
    for (int i = 0; i < 3; i++) {
      results[i] = published.get(i);
      if (results[i] == null) {
        futures.get(i).cancel(true);
        results[i] = timedOut ? new CallResult.TimedOut<>() : new CallResult.Skipped<>("cancelled");
      }
    }
    return new Tri<>(
        (CallResult<A>) results[0], (CallResult<B>) results[1], (CallResult<C>) results[2]);
  }
}
