package dev.keyedge.bench;

import dev.keyedge.bench.domain.BenchResult;
import dev.keyedge.bench.domain.Fingerprint;
import dev.keyedge.bench.domain.Prediction;
import dev.keyedge.bench.domain.Scenario;
import dev.keyedge.bench.domain.Slo;
import dev.keyedge.bench.domain.StepResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Shared test data. */
public final class Fixtures {
  public static final Slo SLO = new Slo(100, 0.01);

  private Fixtures() {}

  public static StepResult step(String variant, int rate, double p99, long errors) {
    return new StepResult(
        variant, rate, 1, rate, p99 / 3, p99 / 2, p99, p99 * 2, 1000, errors, 0, 0, 0.5, true,
        null);
  }

  public static Scenario scenario(String name, List<Integer> rates, String... variants) {
    return new Scenario(
        name,
        "the hypothesis",
        "edge",
        "/v1/quotes/sku-7",
        "healthy",
        rates,
        5,
        0,
        1,
        SLO,
        Map.of("latencyMs", 40.0, "concurrencyCap", 500.0, "sutCpus", 4.0, "cpuMsPerRequest", 2.0),
        java.util.Arrays.stream(variants)
            .map(v -> new Scenario.Variant(v, "jvm", Map.of(), null, null, null, null))
            .toList());
  }

  public static Fingerprint fingerprint(String emulatorVersion) {
    return new Fingerprint(
        "host",
        14,
        19000,
        "6.6",
        "29.7.2",
        "OpenJDK 25",
        Map.of("edge", "keyedge/edge:dev sha256:1"),
        "floci",
        emulatorVersion,
        1.5);
  }

  public static BenchResult result(String scenario, List<StepResult> steps, String verdict) {
    return new BenchResult(
        BenchResult.SCHEMA,
        "run-1",
        scenario,
        "the hypothesis",
        Instant.parse("2026-11-24T10:00:00Z"),
        new BenchResult.Git("abc1234", false),
        fingerprint("2.5.0"),
        new Prediction(
            scenario,
            "run-1",
            Instant.parse("2026-11-24T09:59:00Z"),
            "we expect 2000 rps",
            Map.of("x", 1.0)),
        SLO,
        steps,
        Map.of("reactor", 200),
        verdict,
        "detail",
        Map.of("f", 1.0),
        List.of());
  }
}
