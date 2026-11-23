package dev.keyedge.bench.domain;

import static dev.keyedge.bench.Fixtures.SLO;
import static dev.keyedge.bench.Fixtures.step;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class SaturationTest {
  @Test
  void theHighestRateBeforeTheKneeCounts() {
    var steps =
        List.of(
            step("v", 100, 50, 0),
            step("v", 200, 80, 0),
            step("v", 300, 150, 0),
            step("v", 400, 60, 0));
    assertThat(Saturation.find(steps, SLO)).hasValue(200); // 400 met again, but after the knee
  }

  @Test
  void invalidStepsNeitherCountNorBreak() {
    var steps =
        List.of(
            step("v", 100, 50, 0),
            step("v", 200, 500, 0).invalid("dropped"),
            step("v", 300, 90, 0));
    assertThat(Saturation.find(steps, SLO)).hasValue(300);
  }

  @Test
  void errorsAboveTheSloFailAStep() {
    assertThat(Saturation.find(List.of(step("v", 100, 10, 50)), SLO)).isEmpty(); // 5 % errors
  }

  @Test
  void everyRepetitionOfARateMustMeetTheSlo() {
    var steps = List.of(step("v", 100, 50, 0), step("v", 200, 50, 0), step("v", 200, 120, 0));
    assertThat(Saturation.find(steps, SLO)).hasValue(100);
  }

  @Property
  void addingAFailingHigherStepNeverRaisesSaturation(
      @ForAll @Size(min = 1, max = 8) List<@IntRange(min = 1, max = 200) Integer> p99s) {
    List<StepResult> steps = new ArrayList<>();
    for (int i = 0; i < p99s.size(); i++) {
      steps.add(step("v", (i + 1) * 100, p99s.get(i), 0));
    }
    var before = Saturation.find(steps, SLO);
    steps.add(step("v", (p99s.size() + 1) * 100, 999, 0));
    var after = Saturation.find(steps, SLO);
    assertThat(after.orElse(0)).isLessThanOrEqualTo(Math.max(before.orElse(0), 0));
  }
}
