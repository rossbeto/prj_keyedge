package dev.keyedge.bench.domain;

import java.util.List;
import java.util.OptionalDouble;
import java.util.function.ToDoubleFunction;

/** Medians over the valid repetitions of one (variant, rate) cell. */
public final class Steps {
  private Steps() {}

  public static OptionalDouble median(
      List<StepResult> steps, String variant, int rate, ToDoubleFunction<StepResult> metric) {
    double[] values =
        steps.stream()
            .filter(s -> s.valid() && s.variant().equals(variant) && s.offeredRps() == rate)
            .mapToDouble(metric)
            .sorted()
            .toArray();
    if (values.length == 0) {
      return OptionalDouble.empty();
    }
    int mid = values.length / 2;
    return OptionalDouble.of(
        values.length % 2 == 1 ? values[mid] : (values[mid - 1] + values[mid]) / 2);
  }

  /** True if every valid repetition of the cell met the SLO (and there was at least one). */
  public static boolean met(List<StepResult> steps, String variant, int rate, Slo slo) {
    var cell =
        steps.stream()
            .filter(s -> s.valid() && s.variant().equals(variant) && s.offeredRps() == rate)
            .toList();
    return !cell.isEmpty() && cell.stream().allMatch(slo::metBy);
  }
}
