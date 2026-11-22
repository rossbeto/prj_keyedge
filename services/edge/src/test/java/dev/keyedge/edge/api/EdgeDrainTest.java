package dev.keyedge.edge.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.edge.app.MeterUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EdgeDrainTest {
  @Test
  void readinessGoesDownThenInFlightDrainsThenUsageIsFlushed() throws Exception {
    var gate = new Gate();
    var flushes = new AtomicInteger();
    var usage = new MeterUsage(Instant::now, batch -> flushes.incrementAndGet(), 10);
    usage.record(
        new dev.keyedge.keyformat.TenantId("acme"), new dev.keyedge.keyformat.KeyId(1, 1), true);
    var drain =
        new EdgeDrain(
            gate, usage, new EdgeDrain.DrainSettings(Duration.ofMillis(50), Duration.ofSeconds(2)));
    gate.enter();
    var done = drain.shutdownGracefully().toCompletableFuture();
    Thread.sleep(150);
    assertThat(gate.ready()).isFalse();
    assertThat(done).isNotDone(); // still waiting for the in-flight request
    assertThat(drain.reportActiveTasks()).hasValue(1);
    gate.leave();
    done.get();
    assertThat(flushes.get()).isEqualTo(1);
  }

  @Test
  void theDrainGivesUpAtItsTimeout() throws Exception {
    var gate = new Gate();
    var drain =
        new EdgeDrain(
            gate,
            new MeterUsage(Instant::now, b -> {}, 10),
            new EdgeDrain.DrainSettings(Duration.ZERO, Duration.ofMillis(200)));
    gate.enter();
    long start = System.nanoTime();
    drain.shutdownGracefully().toCompletableFuture().get();
    assertThat((System.nanoTime() - start) / 1_000_000).isBetween(150L, 1500L);
  }
}
