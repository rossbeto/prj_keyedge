package dev.keyedge.edge.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.edge.adapters.observe.JfrEdgeObserver;
import dev.keyedge.edge.domain.Admission;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.PrincipalHeader;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.keyformat.TenantId;
import dev.keyedge.problem.ProblemType;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.Test;

/** The custom JFR events carry the right fields (recorded in-process with a RecordingStream). */
class JfrEventsTest {
  @Test
  void rateLimitAndVerifyEventsAreRecorded() throws Exception {
    List<RecordedEvent> seen = new CopyOnWriteArrayList<>();
    var observer = new JfrEdgeObserver();
    var principal =
        new PrincipalHeader.Principal(
            new TenantId("acme"), new KeyId(7, 7), Set.of(new Scope("q:r")));
    try (var rs = new RecordingStream()) {
      rs.enable("dev.keyedge.RateLimitDecision");
      rs.enable("dev.keyedge.VerifyCacheMiss");
      rs.onEvent(seen::add);
      rs.startAsync();
      observer.decided(new Admission.Admitted(principal, false), Duration.ofNanos(1));
      observer.decided(
          new Admission.Rejected(
              ProblemType.RATE_LIMITED,
              Optional.of(Duration.ofMillis(250)),
              Optional.of(principal)),
          Duration.ofNanos(1));
      observer.decided(Admission.Rejected.of(ProblemType.UNKNOWN_KEY), Duration.ofNanos(1));
      observer.loaded(new KeyId(7, 7), 1_500_000, "miss", "Valid");
      long until = System.nanoTime() + 10_000_000_000L;
      while (seen.size() < 3 && System.nanoTime() < until) {
        Thread.sleep(50);
      }
    }
    var limits =
        seen.stream()
            .filter(e -> e.getEventType().getName().endsWith("RateLimitDecision"))
            .toList();
    assertThat(limits).hasSize(2); // the unknown-key rejection is not a rate-limit decision
    assertThat(limits)
        .anySatisfy(e -> {
          assertThat(e.getBoolean("allowed")).isFalse();
          assertThat(e.getLong("retryAfterMs")).isEqualTo(250);
          assertThat(e.getString("keyId")).isEqualTo(new KeyId(7, 7).toString());
        });
    var loads =
        seen.stream().filter(e -> e.getEventType().getName().endsWith("VerifyCacheMiss")).toList();
    assertThat(loads)
        .singleElement()
        .satisfies(e -> {
          assertThat(e.getString("kind")).isEqualTo("miss");
          assertThat(e.getLong("loadNanos")).isEqualTo(1_500_000);
        });
  }
}
