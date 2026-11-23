package dev.keyedge.edge.ports;

import dev.keyedge.edge.domain.Admission;
import java.time.Duration;

/**
 * Told about every admission decision (Phase 011): metrics and JFR events live behind this port so
 * the app layer stays free of telemetry libraries.
 */
@FunctionalInterface
public interface AdmissionObserver {
  void decided(Admission admission, Duration took);

  static AdmissionObserver none() {
    return (a, d) -> {};
  }
}
