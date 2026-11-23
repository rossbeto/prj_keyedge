package dev.keyedge.bench.adapters;

import dev.keyedge.bench.app.ResultStore;
import dev.keyedge.bench.domain.BenchResult;
import dev.keyedge.bench.domain.Prediction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** results/bench/&lt;runId&gt;/{prediction.json, bench-result.json}. */
public final class FsResultStore implements ResultStore {
  private final Path root;

  public FsResultStore(Path root) {
    this.root = root;
  }

  @Override
  public Optional<Prediction> prediction(String runId) {
    Path f = root.resolve(runId).resolve("prediction.json");
    return Files.exists(f)
        ? Optional.of(Json.JSON.readValue(f.toFile(), Prediction.class))
        : Optional.empty();
  }

  @Override
  public void save(Prediction prediction) {
    write(prediction.runId(), "prediction.json", prediction);
  }

  @Override
  public void save(BenchResult result) {
    write(result.runId(), "bench-result.json", result);
  }

  private void write(String runId, String name, Object value) {
    try {
      Path dir = Files.createDirectories(root.resolve(runId));
      Files.writeString(dir.resolve(name), Json.JSON.writeValueAsString(value) + "\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
