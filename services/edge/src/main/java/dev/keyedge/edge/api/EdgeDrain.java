package dev.keyedge.edge.api;

import dev.keyedge.edge.app.MeterUsage;
import io.micronaut.runtime.graceful.GracefulShutdownCapable;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Graceful shutdown inside the ECS stop window (D-29): readiness DOWN → wait {@code grace} (the
 * load balancer notices) → wait for in-flight requests up to {@code drainTimeout} → last usage
 * flush. grace + drain + flush stays under the task's stop timeout (30 s), after which ECS sends
 * SIGKILL.
 */
@Singleton
public class EdgeDrain implements GracefulShutdownCapable {
  private static final Logger LOG = LoggerFactory.getLogger(EdgeDrain.class);
  private final Gate gate;
  private final MeterUsage usage;
  private final DrainSettings settings;

  /** Timings of the drain. */
  public record DrainSettings(Duration grace, Duration drainTimeout) {}

  EdgeDrain(Gate gate, MeterUsage usage, DrainSettings settings) {
    this.gate = gate;
    this.usage = usage;
    this.settings = settings;
  }

  @Override
  public CompletionStage<?> shutdownGracefully() {
    return CompletableFuture.runAsync(
        this::drain, r -> Thread.ofVirtual().name("edge-drain").start(r));
  }

  void drain() {
    gate.close();
    LOG.info("drain: readiness DOWN, waiting {} for the load balancer", settings.grace());
    sleep(settings.grace());
    long deadline =
        System.nanoTime() + settings.drainTimeout().toNanos(); // monotonic, not wall clock
    while (gate.inFlight() > 0 && System.nanoTime() - deadline < 0) {
      sleep(Duration.ofMillis(50));
    }
    LOG.info("drain: {} requests still in flight at the deadline; flushing usage", gate.inFlight());
    usage.flush();
  }

  @Override
  public OptionalLong reportActiveTasks() {
    return OptionalLong.of(gate.inFlight());
  }

  private static void sleep(Duration d) {
    try {
      Thread.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
