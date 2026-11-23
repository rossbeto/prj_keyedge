package dev.keyedge.bench.domain;

import org.jspecify.annotations.Nullable;

/**
 * One load step as measured. A step with dropped iterations was generator-bound (k6 could not keep
 * the offered rate), so it says nothing about the system under test: {@code valid} is false.
 *
 * @param cpuPerReqMs CPU time of the system-under-test containers per completed request, if read
 */
public record StepResult(
    String variant,
    int offeredRps,
    int repetition,
    double achievedRps,
    double p50Ms,
    double p90Ms,
    double p99Ms,
    double maxMs,
    long requests,
    long errors,
    long droppedIterations,
    @Nullable Double cpuPerReqMs,
    boolean valid,
    @Nullable String invalidReason) {

  /** Errors over requests; 1 when nothing completed. */
  public double errorRatio() {
    return requests == 0 ? 1.0 : (double) errors / requests;
  }

  /** The same step marked invalid with a reason (dropped iterations, dirty tree, ...). */
  public StepResult invalid(String reason) {
    return new StepResult(
        variant,
        offeredRps,
        repetition,
        achievedRps,
        p50Ms,
        p90Ms,
        p99Ms,
        maxMs,
        requests,
        errors,
        droppedIterations,
        cpuPerReqMs,
        false,
        reason);
  }
}
