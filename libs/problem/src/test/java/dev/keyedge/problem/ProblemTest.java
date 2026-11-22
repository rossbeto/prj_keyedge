package dev.keyedge.problem;

import static dev.keyedge.testkit.KeyedgeArchitectureRules.importMain;
import static dev.keyedge.testkit.KeyedgeArchitectureRules.pureLibrary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ProblemTest {
  @Test
  void slugsAreAPinnedPublicContract() throws IOException {
    String actual =
        Arrays.stream(ProblemType.values())
            .map(t -> t.uri() + " " + t.status() + " " + t.title())
            .collect(Collectors.joining("\n", "", "\n"));
    try (var in = getClass().getResourceAsStream("/problem-types.snapshot")) {
      assertThat(in).isNotNull();
      assertThat(actual).isEqualTo(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void membersAreInRfcOrderWithExtensionsFlattened() {
    var p =
        Problem.of(ProblemType.RATE_LIMITED, "slow down")
            .with(Problem.RETRY_AFTER_SECONDS, 3)
            .with("keyId", "01ABC")
            .at(URI.create("/v1/quotes/x"));
    assertThat(p.members().keySet())
        .containsExactly(
            "type", "title", "status", "detail", "instance", "keyId", "retryAfterSeconds");
    assertThat(p.members())
        .containsEntry("type", "urn:keyedge:problem:rate-limited")
        .containsEntry("status", 429);
  }

  @Test
  void absentMembersAreOmitted() {
    assertThat(Problem.of(ProblemType.INTERNAL, null).members().keySet())
        .containsExactly("type", "title", "status");
  }

  @Test
  void rejectsBadStatusAndReservedExtensionNames() {
    assertThatThrownBy(() -> new Problem(URI.create("about:blank"), "x", 200, null, null, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Problem.of(ProblemType.INTERNAL, null).with("status", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Problem(URI.create("about:blank"), "x", 600, null, null, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void statusBoundariesAreInclusive() {
    assertThat(new Problem(URI.create("about:blank"), "x", 400, null, null, Map.of()).status())
        .isEqualTo(400);
    assertThat(new Problem(URI.create("about:blank"), "x", 599, null, null, Map.of()).status())
        .isEqualTo(599);
    assertThatThrownBy(() -> new Problem(URI.create("about:blank"), "x", 399, null, null, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exceptionCarriesTheProblemAndOnlyTheTitle() {
    var p = Problem.of(ProblemType.UPSTREAM_UNAVAILABLE, "pricing down: secret detail");
    var e = new ProblemException(p);
    assertThat(e.problem()).isSameAs(p);
    assertThat(e.getMessage()).isEqualTo(p.title()).doesNotContain("secret");
    assertThat(e.getStackTrace()).isEmpty();
  }

  @Test
  void isAPureLibrary() {
    pureLibrary("dev.keyedge.problem").check(importMain("dev.keyedge.problem"));
  }
}
