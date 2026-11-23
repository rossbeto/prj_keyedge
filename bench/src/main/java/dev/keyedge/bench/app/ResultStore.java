package dev.keyedge.bench.app;

import dev.keyedge.bench.domain.BenchResult;
import dev.keyedge.bench.domain.Prediction;
import java.util.Optional;

/** results/bench/&lt;runId&gt;/: prediction.json first, bench-result.json after the run. */
public interface ResultStore {
  Optional<Prediction> prediction(String runId);

  void save(Prediction prediction);

  void save(BenchResult result);
}
