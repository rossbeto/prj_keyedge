package dev.keyedge.bench.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class K6SummaryTest {
  @Test
  void parsesARecordedSummary() throws Exception {
    var o = K6Summary.parse(Files.readString(Path.of("bench/src/test/resources/k6-summary.json")));
    assertThat(o.requests()).isPositive();
    assertThat(o.p99Ms()).isGreaterThanOrEqualTo(o.p90Ms()).isGreaterThanOrEqualTo(o.p50Ms());
    assertThat(o.maxMs()).isGreaterThanOrEqualTo(o.p99Ms());
    assertThat(o.achievedRps()).isPositive();
  }

  @Test
  void droppedIterationsAndFailedRequestsAreCounted() {
    var o =
        K6Summary.parse(
            """
            {"metrics":{"http_reqs":{"count":100,"rate":10},"http_req_failed":{"passes":7,"fails":93,"value":0.07},
             "http_req_duration":{"p(50)":1,"p(90)":2,"p(99)":3,"max":4},"dropped_iterations":{"count":5}}}
            """);
    assertThat(o.errors()).isEqualTo(7);
    assertThat(o.droppedIterations()).isEqualTo(5);
  }
}
