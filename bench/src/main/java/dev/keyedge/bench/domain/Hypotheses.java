package dev.keyedge.bench.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The verdict rule of each scenario, fixed in code before the run (the hypotheses of the Phase 015
 * plan). A rule that cannot decide (saturation never reached, a cell without valid steps) says
 * {@link Verdict#INCONCLUSIVE} and why, never a guess.
 */
public final class Hypotheses {
  static final double SATURATION_BAND = 0.15;
  static final double P99_BAND = 0.20;
  static final double OVERHEAD_P50_MS = 2.0;
  static final double OVERHEAD_P99_MS = 5.0;

  private Hypotheses() {}

  public static Evaluation evaluate(
      Scenario scenario, List<StepResult> steps, Map<String, Integer> saturation) {
    return switch (scenario.name()) {
      case "vt-vs-reactor" -> vtVsReactor(scenario, steps, saturation);
      case "native-overhead" -> nativeOverhead(scenario, steps);
      case "fargate-size" -> fargateSize(scenario, steps);
      case "bench-smoke" -> smoke(steps);
      default ->
          new Evaluation(
              Verdict.INCONCLUSIVE, "no verdict rule for scenario " + scenario.name(), Map.of());
    };
  }

  /** VT adapters reach the Reactor twin's saturation within ±15 %, p99 within ±20 % below it. */
  static Evaluation vtVsReactor(
      Scenario scenario, List<StepResult> steps, Map<String, Integer> saturation) {
    Map<String, Double> f = new LinkedHashMap<>();
    saturation.forEach((v, s) -> f.put("saturation_rps." + v, (double) s));
    int reactor = saturation.getOrDefault("reactor", 0);
    int max = scenario.maxRate();
    if (reactor == 0) {
      return new Evaluation(
          Verdict.INCONCLUSIVE, "the Reactor twin met the SLO at no offered rate", f);
    }
    if (saturation.values().stream().allMatch(s -> s == max)) {
      return new Evaluation(
          Verdict.INCONCLUSIVE,
          "every variant met the SLO at the highest offered rate ("
              + max
              + " rps): saturation not reached",
          f);
    }
    boolean satOk = true;
    for (var e : saturation.entrySet()) {
      if (!e.getKey().equals("reactor")) {
        double rel = (e.getValue() - reactor) / (double) reactor;
        f.put("saturation_vs_reactor." + e.getKey(), round(rel));
        satOk &= Math.abs(rel) <= SATURATION_BAND;
      }
    }
    int below = saturation.values().stream().mapToInt(Integer::intValue).min().orElse(0);
    boolean p99Ok = true;
    for (int rate : scenario.rates()) {
      if (rate > below) {
        continue;
      }
      OptionalDouble r = Steps.median(steps, "reactor", rate, StepResult::p99Ms);
      for (String v : saturation.keySet()) {
        OptionalDouble p = Steps.median(steps, v, rate, StepResult::p99Ms);
        if (!v.equals("reactor") && r.isPresent() && p.isPresent()) {
          double rel = (p.getAsDouble() - r.getAsDouble()) / r.getAsDouble();
          f.put("p99_vs_reactor." + v + "@" + rate, round(rel));
          p99Ok &= Math.abs(rel) <= P99_BAND;
        }
      }
    }
    String detail =
        String.format(
            Locale.ROOT,
            "saturation within ±%.0f %% of the Reactor twin: %s; p99 within ±%.0f %% at rates ≤ %d rps: %s",
            SATURATION_BAND * 100,
            satOk ? "yes" : "no",
            P99_BAND * 100,
            below,
            p99Ok ? "yes" : "no");
    return new Evaluation(satOk && p99Ok ? Verdict.CONFIRMED : Verdict.REFUTED, detail, f);
  }

  /** The sidecar adds < 2 ms p50 and < 5 ms p99 at the middle offered rate. */
  static Evaluation nativeOverhead(Scenario scenario, List<StepResult> steps) {
    List<Integer> rates = scenario.rates().stream().sorted().toList();
    int mid = rates.get(rates.size() / 2);
    var viaP50 = Steps.median(steps, "via-edge", mid, StepResult::p50Ms);
    var dirP50 = Steps.median(steps, "direct", mid, StepResult::p50Ms);
    var viaP99 = Steps.median(steps, "via-edge", mid, StepResult::p99Ms);
    var dirP99 = Steps.median(steps, "direct", mid, StepResult::p99Ms);
    Map<String, Double> f = new LinkedHashMap<>();
    f.put("rate_rps", (double) mid);
    if (viaP50.isEmpty() || dirP50.isEmpty() || viaP99.isEmpty() || dirP99.isEmpty()) {
      return new Evaluation(
          Verdict.INCONCLUSIVE, "no valid steps for via-edge and direct at " + mid + " rps", f);
    }
    double d50 = viaP50.getAsDouble() - dirP50.getAsDouble();
    double d99 = viaP99.getAsDouble() - dirP99.getAsDouble();
    f.put("added_p50_ms", round(d50));
    f.put("added_p99_ms", round(d99));
    boolean ok = d50 < OVERHEAD_P50_MS && d99 < OVERHEAD_P99_MS;
    return new Evaluation(
        ok ? Verdict.CONFIRMED : Verdict.REFUTED,
        String.format(
            Locale.ROOT,
            "at %d rps the edge adds %.2f ms p50 (limit %.0f) and %.2f ms p99 (limit %.0f)",
            mid,
            d50,
            OVERHEAD_P50_MS,
            d99,
            OVERHEAD_P99_MS),
        f);
  }

  /**
   * The native edge meets the SLO at 0.25 vCPU / 256 MiB; the JVM edge does not at that size (needs
   * 0.5 / 512 or more). Variants are named {@code <flavor>-<cpus>}.
   */
  static Evaluation fargateSize(Scenario scenario, List<StepResult> steps) {
    int rate = scenario.rates().getFirst();
    Map<String, Double> f = new LinkedHashMap<>();
    for (var v : scenario.variants()) {
      f.put("met." + v.name(), Steps.met(steps, v.name(), rate, scenario.slo()) ? 1.0 : 0.0);
      Steps.median(steps, v.name(), rate, StepResult::p99Ms)
          .ifPresent(p -> f.put("p99_ms." + v.name(), round(p)));
    }
    Double nativeSmall = f.get("met.native-0.25");
    Double jvmSmall = f.get("met.jvm-0.25");
    Double jvmHalf = f.get("met.jvm-0.5");
    if (nativeSmall == null || jvmSmall == null || jvmHalf == null) {
      return new Evaluation(
          Verdict.INCONCLUSIVE, "variants native-0.25, jvm-0.25, jvm-0.5 are required", f);
    }
    boolean ok = nativeSmall == 1.0 && jvmSmall == 0.0 && jvmHalf == 1.0;
    return new Evaluation(
        ok ? Verdict.CONFIRMED : Verdict.REFUTED,
        String.format(
            Locale.ROOT,
            "at %d rps: native 0.25 vCPU %s, JVM 0.25 vCPU %s, JVM 0.5 vCPU %s the SLO",
            rate,
            nativeSmall == 1.0 ? "meets" : "misses",
            jvmSmall == 1.0 ? "meets" : "misses",
            jvmHalf == 1.0 ? "meets" : "misses"),
        f);
  }

  /** The harness works: valid steps, nothing dropped. */
  static Evaluation smoke(List<StepResult> steps) {
    boolean ok = !steps.isEmpty() && steps.stream().allMatch(StepResult::valid);
    return new Evaluation(
        ok ? Verdict.CONFIRMED : Verdict.REFUTED,
        ok ? "harness ok: every step valid" : "invalid steps in the smoke run",
        Map.of("steps", (double) steps.size()));
  }

  static double round(double v) {
    return Math.round(v * 1000) / 1000.0;
  }
}
