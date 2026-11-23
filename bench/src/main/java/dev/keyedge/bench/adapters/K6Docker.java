package dev.keyedge.bench.adapters;

import dev.keyedge.bench.app.LoadGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * k6 in a container (pinned digest, the same as calibrate.sh). {@code edge}: on the compose network
 * against http://edge:15080. {@code direct}: inside the edge's network namespace against quotes on
 * 127.0.0.1:15081 (the hop the sidecar would make). Credentials go through a 0600 env file that is
 * deleted afterwards, never through the command line.
 */
public final class K6Docker implements LoadGenerator {
  static final String IMAGE =
      "grafana/k6@sha256:3d8e5c7f1d53ec59408c2ffbd9943602a0985ffcbd4a2430ec00f3ffca89796e";
  private final Path scripts;
  private final String cpuset;
  private final String edgeContainer;

  public K6Docker(Path scripts, String cpuset, String edgeContainer) {
    this.scripts = scripts.toAbsolutePath();
    this.cpuset = cpuset;
    this.edgeContainer = edgeContainer;
  }

  @Override
  public LoadOutcome run(LoadRequest r) {
    Path work = null;
    try {
      work = Files.createTempDirectory("keyedge-k6-");
      Path envFile = work.resolve("k6.env");
      Files.createFile(
          envFile,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      boolean direct = r.target().equals("direct");
      String url = (direct ? "http://127.0.0.1:15081" : "http://edge:15080") + r.path();
      String signPath =
          r.path().contains("?") ? r.path().substring(0, r.path().indexOf('?')) : r.path();
      Files.writeString(
          envFile,
          String.join(
                  "\n",
                  "MODE=" + r.target(),
                  "RATE=" + r.rate(),
                  "DURATION=" + r.seconds() + "s",
                  "TARGET_URL=" + url,
                  "SIGN_PATH=" + signPath,
                  "KEYS=" + String.join(",", r.auth().keys()),
                  "PRINCIPAL_ID=" + r.auth().principalId(),
                  "PRINCIPAL_KEY=" + r.auth().principalKey())
              + "\n");
      Files.setPosixFilePermissions(
          work, PosixFilePermissions.fromString("rwxrwxrwx")); // k6 runs as another uid
      List<String> cmd = new ArrayList<>(List.of("docker", "run", "--rm"));
      cmd.addAll(List.of("--network", direct ? "container:" + edgeContainer : "keyedge_default"));
      if (!cpuset.isBlank()) {
        cmd.addAll(List.of("--cpuset-cpus", cpuset));
      }
      cmd.addAll(
          List.of(
              "--env-file",
              envFile.toString(),
              "-v",
              scripts + ":/scripts:ro",
              "-v",
              work + ":/out",
              IMAGE,
              "run",
              "--quiet",
              "--summary-export",
              "/out/summary.json",
              "/scripts/open.js"));
      Proc.run(cmd, Map.of(), Duration.ofSeconds(r.seconds() + 120L));
      return K6Summary.parse(Files.readString(work.resolve("summary.json")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      if (work != null) {
        deleteQuietly(work);
      }
    }
  }

  private static void deleteQuietly(Path dir) {
    try (var files = Files.walk(dir)) {
      files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException e) {
      // a temp dir left behind holds no secret: the env file is deleted first by the walk order
    }
  }
}
