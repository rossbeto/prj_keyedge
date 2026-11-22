package dev.keyedge.edge.wiring;

import dev.keyedge.edge.app.MeterUsage;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;

/** Flushes aggregated usage on a fixed delay (off the request path). */
@Singleton
public class UsageFlusher {
  private final MeterUsage usage;

  UsageFlusher(MeterUsage usage) {
    this.usage = usage;
  }

  @Scheduled(fixedDelay = "${keyedge.edge.usage-flush-interval:10s}", initialDelay = "10s")
  void flush() {
    usage.flush();
  }
}
