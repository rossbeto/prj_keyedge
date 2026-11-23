package dev.keyedge.bench.domain;

import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;

/**
 * The saturation rate of one variant: the highest offered rate whose steps all met the SLO, below
 * the first offered rate where any valid step failed it (the knee). Invalid steps are ignored, so a
 * generator-bound step never counts for or against the system.
 */
public final class Saturation {
  private Saturation() {}

  public static OptionalInt find(List<StepResult> steps, Slo slo) {
    List<Integer> rates =
        steps.stream()
            .filter(StepResult::valid)
            .map(StepResult::offeredRps)
            .distinct()
            .sorted(Comparator.naturalOrder())
            .toList();
    OptionalInt best = OptionalInt.empty();
    for (int rate : rates) {
      boolean allMet =
          steps.stream().filter(s -> s.valid() && s.offeredRps() == rate).allMatch(slo::metBy);
      if (!allMet) {
        break;
      }
      best = OptionalInt.of(rate);
    }
    return best;
  }
}
