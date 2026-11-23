package dev.keyedge.bench.domain;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A bench scenario (bench/scenarios/*.yaml): what to load, at which open-model rates, against which
 * stack variants, and the SLO that defines "sustained".
 *
 * @param target {@code edge} (through the sidecar) or {@code direct} (k6 in the edge's network
 *     namespace, quotes on 127.0.0.1:15081 with a signed principal: no sidecar on the path)
 * @param simScenario upstream-sim scenario switched on before the run
 * @param model numbers for the prediction (Little's Law inputs), written before any load
 */
public record Scenario(
    String name,
    String hypothesis,
    String target,
    String path,
    String simScenario,
    List<Integer> rates,
    int stepSeconds,
    int warmupSeconds,
    int repetitions,
    Slo slo,
    Map<String, Double> model,
    List<Variant> variants) {

  public Scenario {
    rates = List.copyOf(rates);
    model = Map.copyOf(model);
    variants = List.copyOf(variants);
    if (rates.isEmpty() || variants.isEmpty() || repetitions < 1 || stepSeconds < 1) {
      throw new IllegalArgumentException("scenario " + name + " needs rates, variants, steps");
    }
    if (!target.equals("edge") && !target.equals("direct")) {
      throw new IllegalArgumentException("target must be edge or direct, got " + target);
    }
  }

  /** The highest offered rate (sizes the key pool and k6's VUs). */
  public int maxRate() {
    return rates.stream().mapToInt(Integer::intValue).max().orElseThrow();
  }

  /**
   * One stack variant.
   *
   * @param flavor {@code jvm} or {@code native} images for edge and quotes
   * @param env compose variables (for example {@code KEYEDGE_QUOTES_FANOUT})
   * @param path request path override (the Reactor twin has its own endpoint)
   * @param target target override ({@code edge|direct})
   * @param cpus CPU limit of the edge container ({@code docker update --cpus}), if any
   * @param memory memory limit of the edge container, if any
   */
  public record Variant(
      String name,
      String flavor,
      Map<String, String> env,
      @Nullable String path,
      @Nullable String target,
      @Nullable Double cpus,
      @Nullable String memory) {
    public Variant {
      env = Map.copyOf(env);
      if (!flavor.equals("jvm") && !flavor.equals("native")) {
        throw new IllegalArgumentException("flavor must be jvm or native, got " + flavor);
      }
    }

    /** The variant's path, or the scenario's. */
    public String pathOr(String scenarioPath) {
      return path != null ? path : scenarioPath;
    }

    /** The variant's target, or the scenario's. */
    public String targetOr(String scenarioTarget) {
      return target != null ? target : scenarioTarget;
    }
  }
}
