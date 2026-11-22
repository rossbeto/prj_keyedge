package dev.keyedge.testkit.aws;

import dev.keyedge.awstarget.AwsTarget;
import dev.keyedge.awstarget.TargetKind;
import io.floci.testcontainers.FlociContainer;
import java.util.EnumMap;
import java.util.Map;

/**
 * One emulator container per kind per test JVM, started on first use and stopped by Testcontainers'
 * reaper at JVM exit. Every test class shares them; each test creates uniquely named resources.
 */
public final class Emulators {
  private static final Map<TargetKind, AwsTarget> TARGETS = new EnumMap<>(TargetKind.class);

  private Emulators() {}

  /** The running emulator's target, starting it if needed. Real AWS is never started here. */
  public static synchronized AwsTarget start(TargetKind kind) {
    return TARGETS.computeIfAbsent(
        kind,
        k ->
            switch (k) {
              case FLOCI -> {
                FlociContainer floci = FlociSupport.container();
                floci.start();
                yield FlociSupport.target(floci);
              }
              case AWS_EMULATOR -> {
                var emulator = new AwsEmulatorContainer();
                emulator.start();
                yield emulator.target();
              }
              case AWS -> throw new IllegalArgumentException("tests never use real AWS (D-33)");
            });
  }
}
