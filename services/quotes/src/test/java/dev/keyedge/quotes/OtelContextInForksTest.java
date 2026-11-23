package dev.keyedge.quotes;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.fanout.Call;
import dev.keyedge.fanout.CallResult;
import dev.keyedge.fanout.ContextCarrier;
import dev.keyedge.fanout.Deadline;
import dev.keyedge.fanout.FanOutProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Evidence for the carrier (Phase 011): OpenTelemetry's context is ThreadLocal-based, so a
 * structured fork does NOT see it — unlike a ScopedValue — until the carrier re-makes it current.
 */
class OtelContextInForksTest {
  private static final ContextKey<String> KEY = ContextKey.named("request");

  private static CallResult<String> seenInFork(ContextCarrier carrier) {
    try (var vt = Executors.newVirtualThreadPerTaskExecutor();
        var scope = Context.current().with(KEY, "req-1").makeCurrent()) {
      var fanOut =
          FanOutProvider.load(
              "structured", new FanOutProvider.Resources(System::nanoTime, vt, carrier));
      Call.Work<String> read = () -> String.valueOf(Context.current().get(KEY));
      return fanOut
          .all(
              Call.required("a", read),
              Call.required("b", read),
              Call.optional("c", read),
              Deadline.after(Duration.ofSeconds(2), System::nanoTime))
          .first();
    }
  }

  @Test
  void forksDoNotInheritOtelContextWithoutTheCarrier() {
    assertThat(seenInFork(ContextCarrier.none())).isEqualTo(new CallResult.Ok<>("null"));
  }

  @Test
  void theCarrierMakesItCurrentInForks() {
    ContextCarrier otel =
        () -> {
          Context captured = Context.current();
          return new ContextCarrier.Binder() {
            @Override
            public <T> java.util.concurrent.Callable<T> wrap(
                java.util.concurrent.Callable<T> task) {
              return captured.wrap(task);
            }
          };
        };
    assertThat(seenInFork(otel)).isEqualTo(new CallResult.Ok<>("req-1"));
  }
}
