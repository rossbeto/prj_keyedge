package dev.keyedge.bench.domain;

/** The outcome of an experiment against the hypothesis written before it ran. */
public enum Verdict {
  CONFIRMED,
  REFUTED,
  INCONCLUSIVE;

  /** Lower case, as in every results file. */
  public String label() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
