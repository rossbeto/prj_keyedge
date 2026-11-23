package dev.keyedge.bench.domain;

import java.util.Map;

/** A verdict, the sentence explaining it, and the numbers it was decided on. */
public record Evaluation(Verdict verdict, String detail, Map<String, Double> findings) {
  public Evaluation {
    findings = Map.copyOf(findings);
  }
}
