package dev.keyedge.bench.domain;

/** What a step must meet to count as "sustained": p99 latency and error ratio. */
public record Slo(double p99Ms, double maxErrorRatio) {
  public Slo {
    if (p99Ms <= 0 || maxErrorRatio < 0 || maxErrorRatio > 1) {
      throw new IllegalArgumentException("SLO needs p99Ms > 0 and 0 <= maxErrorRatio <= 1");
    }
  }

  /** True if the step is valid and inside the SLO. */
  public boolean metBy(StepResult r) {
    return r.valid() && r.p99Ms() <= p99Ms && r.errorRatio() <= maxErrorRatio;
  }
}
