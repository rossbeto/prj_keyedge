package dev.keyedge.bench.adapters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs a command, returns stdout; stderr goes to ours; non-zero exit or time-out throws. */
public final class Proc {
  private Proc() {}

  public static String run(List<String> cmd, Map<String, String> env, Duration timeout) {
    try {
      ProcessBuilder pb = new ProcessBuilder(cmd).directory(Path.of("").toAbsolutePath().toFile());
      pb.environment().putAll(env);
      pb.redirectError(ProcessBuilder.Redirect.INHERIT);
      Process p = pb.start();
      byte[] out = p.getInputStream().readAllBytes();
      if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        p.destroyForcibly();
        throw new IllegalStateException("timed out: " + cmd.getFirst());
      }
      if (p.exitValue() != 0) {
        throw new IllegalStateException(
            "exit "
                + p.exitValue()
                + ": "
                + String.join(" ", cmd.subList(0, Math.min(4, cmd.size()))));
      }
      return new String(out, StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      throw new IllegalStateException("cannot run " + cmd.getFirst() + ": " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }

  public static String run(String... cmd) {
    return run(List.of(cmd), Map.of(), Duration.ofMinutes(5));
  }
}
