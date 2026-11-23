package dev.keyedge.bench.app;

/** Offers load at a constant arrival rate and reports what came back (k6 in a container). */
public interface LoadGenerator {
  LoadOutcome run(LoadRequest request);

  /**
   * One step of load.
   *
   * @param target {@code edge} or {@code direct}
   * @param auth API keys (edge) or the signed-principal material (direct)
   */
  record LoadRequest(String target, String path, int rate, int seconds, Auth auth) {}

  /**
   * What the generator measured; {@code droppedIterations > 0} means it could not keep the rate.
   */
  record LoadOutcome(
      double achievedRps,
      double p50Ms,
      double p90Ms,
      double p99Ms,
      double maxMs,
      long requests,
      long errors,
      long shed,
      long droppedIterations) {}
}
