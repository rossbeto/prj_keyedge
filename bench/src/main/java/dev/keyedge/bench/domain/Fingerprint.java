package dev.keyedge.bench.domain;

import java.util.Map;

/**
 * Where a run happened. Runs are only compared inside one fingerprint (plan: no cross-environment
 * comparisons); an unknown emulator version flags the run.
 */
public record Fingerprint(
    String host,
    int cpus,
    long memTotalMb,
    String kernel,
    String docker,
    String jdk,
    Map<String, String> images,
    String emulatorKind,
    String emulatorVersion,
    double loadAverage1m) {
  public static final String UNKNOWN = "unknown";

  public Fingerprint {
    images = Map.copyOf(images);
  }

  /** A run without a known emulator version cannot be reproduced: it is flagged. */
  public boolean complete() {
    return !UNKNOWN.equals(emulatorVersion) && !UNKNOWN.equals(emulatorKind);
  }
}
