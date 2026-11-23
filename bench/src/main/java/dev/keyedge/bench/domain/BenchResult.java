package dev.keyedge.bench.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * results/bench/&lt;runId&gt;/bench-result.json, schema v1 (results/schema/bench-result.v1.json).
 *
 * @param saturationRps per variant, 0 when no step met the SLO
 * @param findings the numbers the verdict was decided on
 */
public record BenchResult(
    String schema,
    String runId,
    String scenario,
    String hypothesis,
    Instant startedAt,
    Git git,
    Fingerprint fingerprint,
    Prediction prediction,
    Slo slo,
    List<StepResult> steps,
    Map<String, Integer> saturationRps,
    String verdict,
    String verdictDetail,
    Map<String, Double> findings,
    List<String> notes) {
  public static final String SCHEMA = "keyedge.bench-result.v1";

  public BenchResult {
    steps = List.copyOf(steps);
    saturationRps = Map.copyOf(saturationRps);
    findings = Map.copyOf(findings);
    notes = List.copyOf(notes);
  }

  /** The commit and whether the working tree had uncommitted changes (dirty runs are flagged). */
  public record Git(String sha, boolean dirty) {}
}
