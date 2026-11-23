package dev.keyedge.bench.adapters;

import dev.keyedge.bench.app.LoadGenerator.LoadOutcome;
import tools.jackson.databind.JsonNode;

/** Reads k6's {@code --summary-export} JSON into a {@link LoadOutcome}. */
public final class K6Summary {
  private K6Summary() {}

  public static LoadOutcome parse(String json) {
    JsonNode m = Json.JSON.readTree(json).path("metrics");
    JsonNode d = m.path("http_req_duration");
    JsonNode reqs = m.path("http_reqs");
    // http_req_failed is a Rate: "passes" counts the true values, i.e. the failed requests
    long errors = m.path("http_req_failed").path("passes").asLong(0);
    return new LoadOutcome(
        reqs.path("rate").asDouble(0),
        d.path("p(50)").asDouble(0),
        d.path("p(90)").asDouble(0),
        d.path("p(99)").asDouble(0),
        d.path("max").asDouble(0),
        reqs.path("count").asLong(0),
        errors,
        m.path("dropped_iterations").path("count").asLong(0));
  }
}
