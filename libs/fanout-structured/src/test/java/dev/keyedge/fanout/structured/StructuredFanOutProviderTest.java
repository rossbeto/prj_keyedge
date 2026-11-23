package dev.keyedge.fanout.structured;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.fanout.ContextCarrier;
import dev.keyedge.fanout.FanOutProvider;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class StructuredFanOutProviderTest {
  @Test
  void registeredAsStructured() {
    try (var vt = Executors.newVirtualThreadPerTaskExecutor()) {
      var r = new FanOutProvider.Resources(System::nanoTime, vt, ContextCarrier.none());
      assertThat(FanOutProvider.load("structured", r)).isInstanceOf(StructuredFanOut.class);
      assertThat(FanOutProvider.load("future", r)).isNotInstanceOf(StructuredFanOut.class);
    }
  }
}
