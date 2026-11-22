package dev.keyedge.edge.api;

import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import io.micronaut.management.health.indicator.annotation.Readiness;
import jakarta.inject.Singleton;
import java.util.Map;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/** /health/readiness goes DOWN first on shutdown, so the load balancer stops sending (D-29). */
@Singleton
@Readiness
public class EdgeReadiness implements HealthIndicator {
  private final Gate gate;

  EdgeReadiness(Gate gate) {
    this.gate = gate;
  }

  @Override
  public Publisher<HealthResult> getResult() {
    return Mono.just(
        HealthResult.builder("edge", gate.ready() ? HealthStatus.UP : HealthStatus.DOWN)
            .details(Map.of("inFlight", gate.inFlight()))
            .build());
  }
}
