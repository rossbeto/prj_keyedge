package dev.keyedge.bench.app;

import dev.keyedge.bench.domain.BenchResult;
import dev.keyedge.bench.domain.Fingerprint;

/** Where the run happens: host fingerprint and the git state. */
public interface Environment {
  Fingerprint fingerprint();

  BenchResult.Git git();
}
