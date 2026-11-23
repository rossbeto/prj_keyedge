package dev.keyedge.bench.domain;

import java.time.Instant;
import java.util.Map;

/**
 * What we expect, written to disk before any load is offered (D-72). The report shows it next to
 * the measurement; {@code bench run} refuses to start without it.
 *
 * @param numbers the derived figures (in-flight requests, saturation estimate, ...)
 */
public record Prediction(
    String scenario,
    String runId,
    Instant writtenAt,
    String statement,
    Map<String, Double> numbers) {
  public Prediction {
    numbers = Map.copyOf(numbers);
  }
}
