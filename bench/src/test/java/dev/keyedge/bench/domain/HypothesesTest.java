package dev.keyedge.bench.domain;

import static dev.keyedge.bench.Fixtures.scenario;
import static dev.keyedge.bench.Fixtures.step;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HypothesesTest {
  private final Scenario vt =
      scenario("vt-vs-reactor", List.of(100, 200, 300), "structured", "future", "reactor");

  @Test
  void vtWithinTheBandsIsConfirmed() {
    var steps =
        List.of(
            step("structured", 100, 50, 0),
            step("future", 100, 55, 0),
            step("reactor", 100, 52, 0));
    var e =
        Hypotheses.evaluate(vt, steps, Map.of("structured", 200, "future", 200, "reactor", 200));
    assertThat(e.verdict()).isEqualTo(Verdict.CONFIRMED);
    assertThat(e.findings())
        .containsKeys("saturation_vs_reactor.future", "p99_vs_reactor.structured@100");
  }

  @Test
  void aVtSaturationFarFromReactorIsRefuted() {
    var steps = List.of(step("structured", 100, 50, 0), step("reactor", 100, 50, 0));
    var e =
        Hypotheses.evaluate(vt, steps, Map.of("structured", 100, "future", 200, "reactor", 200));
    assertThat(e.verdict()).isEqualTo(Verdict.REFUTED);
  }

  @Test
  void saturationNotReachedIsInconclusive() {
    var e =
        Hypotheses.evaluate(
            vt, List.of(), Map.of("structured", 300, "future", 300, "reactor", 300));
    assertThat(e.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(e.detail()).contains("not reached");
  }

  @Test
  void aReactorThatNeverMetTheSloIsInconclusive() {
    var e =
        Hypotheses.evaluate(vt, List.of(), Map.of("structured", 300, "future", 300, "reactor", 0));
    assertThat(e.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
  }

  @Test
  void nativeOverheadComparesTheMiddleRate() {
    var s = scenario("native-overhead", List.of(100, 200, 300), "via-edge", "direct");
    var steps =
        List.of(
            new StepResult(
                "via-edge", 200, 1, 200, 11, 15, 24, 30, 1000, 0, 0, 0, null, true, null),
            new StepResult("direct", 200, 1, 200, 10, 14, 21, 30, 1000, 0, 0, 0, null, true, null));
    var e = Hypotheses.evaluate(s, steps, Map.of());
    assertThat(e.verdict()).isEqualTo(Verdict.CONFIRMED);
    assertThat(e.findings()).containsEntry("added_p50_ms", 1.0).containsEntry("added_p99_ms", 3.0);
    assertThat(Hypotheses.evaluate(s, List.of(), Map.of()).verdict())
        .isEqualTo(Verdict.INCONCLUSIVE);
  }

  @Test
  void fargateSizeNeedsTheSmallNativeToPassAndTheSmallJvmToFail() {
    var s = scenario("fargate-size", List.of(200), "native-0.25", "jvm-0.25", "jvm-0.5");
    var steps =
        List.of(
            step("native-0.25", 200, 40, 0),
            step("jvm-0.25", 200, 400, 0),
            step("jvm-0.5", 200, 60, 0));
    assertThat(Hypotheses.evaluate(s, steps, Map.of()).verdict()).isEqualTo(Verdict.CONFIRMED);
    var allPass =
        List.of(
            step("native-0.25", 200, 40, 0),
            step("jvm-0.25", 200, 40, 0),
            step("jvm-0.5", 200, 60, 0));
    assertThat(Hypotheses.evaluate(s, allPass, Map.of()).verdict()).isEqualTo(Verdict.REFUTED);
    var missing = scenario("fargate-size", List.of(200), "native-1");
    assertThat(Hypotheses.evaluate(missing, List.of(), Map.of()).verdict())
        .isEqualTo(Verdict.INCONCLUSIVE);
  }

  @Test
  void smokeAndUnknownScenarios() {
    var smoke = scenario("bench-smoke", List.of(20), "jvm");
    assertThat(Hypotheses.evaluate(smoke, List.of(step("jvm", 20, 10, 0)), Map.of()).verdict())
        .isEqualTo(Verdict.CONFIRMED);
    assertThat(
            Hypotheses.evaluate(smoke, List.of(step("jvm", 20, 10, 0).invalid("x")), Map.of())
                .verdict())
        .isEqualTo(Verdict.REFUTED);
    assertThat(
            Hypotheses.evaluate(scenario("other", List.of(1), "v"), List.of(), Map.of()).verdict())
        .isEqualTo(Verdict.INCONCLUSIVE);
  }

  @Test
  void sloAndScenarioValidation() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Slo(0, 0.1))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> scenario("x", List.of(), "v"))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new Scenario.Variant("v", "graal", Map.of(), null, null, null, null))
        .hasMessageContaining("flavor");
    var v = new Scenario.Variant("reactor", "jvm", Map.of(), "/r", "direct", 0.5, "512m");
    assertThat(v.pathOr("/p")).isEqualTo("/r");
    assertThat(v.targetOr("edge")).isEqualTo("direct");
    assertThat(Verdict.INCONCLUSIVE.label()).isEqualTo("inconclusive");
    assertThat(dev.keyedge.bench.Fixtures.fingerprint("unknown").complete()).isFalse();
    assertThat(dev.keyedge.bench.Fixtures.fingerprint("2.5.0").complete()).isTrue();
  }
}
