package dev.keyedge.bench.adapters;

import dev.keyedge.bench.app.Environment;
import dev.keyedge.bench.domain.BenchResult;
import dev.keyedge.bench.domain.Fingerprint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The host, Docker, images and emulator of a run. Every probe that fails yields "unknown" instead
 * of failing the run; {@link Fingerprint#complete()} flags a missing emulator version.
 */
public final class HostEnvironment implements Environment {
  private final String awsTarget;

  public HostEnvironment(String awsTarget) {
    this.awsTarget = awsTarget;
  }

  @Override
  public Fingerprint fingerprint() {
    Map<String, String> images = new TreeMap<>();
    for (String c :
        List.of("keyedge-edge-1", "keyedge-quotes-1", "keyedge-keys-1", "keyedge-upstream-sim-1")) {
      images.put(
          c.replace("keyedge-", "").replace("-1", ""),
          probe(() -> Proc.run("docker", "inspect", "-f", "{{.Config.Image}} {{.Image}}", c)));
    }
    return new Fingerprint(
        probe(() -> Proc.run("hostname")),
        Runtime.getRuntime().availableProcessors(),
        memTotalMb(),
        probe(() -> Proc.run("uname", "-r")),
        probe(() -> Proc.run("docker", "version", "--format", "{{.Server.Version}}")),
        System.getProperty("java.vm.name") + " " + Runtime.version(),
        images,
        awsTarget,
        emulatorVersion(),
        loadAverage());
  }

  private String emulatorVersion() {
    return switch (awsTarget) {
      case "floci" ->
          probe(() ->
              Proc.run(
                  "docker",
                  "inspect",
                  "-f",
                  "{{index .Config.Labels \"org.opencontainers.image.version\"}}",
                  "keyedge-floci-1"));
      case "aws-emulator" ->
          probe(() ->
              Proc.run("docker", "exec", "keyedge-aws-emulator-1", "aws-emulator", "-v")
                  .lines()
                  .findFirst()
                  .orElse("")
                  .replace("Version: ", ""));
      default -> Fingerprint.UNKNOWN;
    };
  }

  @Override
  public BenchResult.Git git() {
    String sha = probe(() -> Proc.run("git", "rev-parse", "--short", "HEAD"));
    // results/ and docs/ are written by the bench itself
    String status =
        probe(() ->
            Proc.run(
                "git", "status", "--porcelain", "--", ".", ":!results", ":!docs", ":!README.md"));
    return new BenchResult.Git(sha, !status.isEmpty());
  }

  private static long memTotalMb() {
    try {
      for (String l : Files.readAllLines(Path.of("/proc/meminfo"))) {
        if (l.startsWith("MemTotal:")) {
          return Long.parseLong(l.replaceAll("\\D", "")) / 1024;
        }
      }
    } catch (IOException | NumberFormatException e) {
      return 0;
    }
    return 0;
  }

  private static double loadAverage() {
    try {
      return Double.parseDouble(Files.readString(Path.of("/proc/loadavg")).split(" ", -1)[0]);
    } catch (IOException | NumberFormatException e) {
      return -1;
    }
  }

  private static String probe(Supplier<String> s) {
    try {
      String v = s.get();
      return v.isBlank() ? Fingerprint.UNKNOWN : v;
    } catch (RuntimeException e) {
      return Fingerprint.UNKNOWN;
    }
  }
}
