package dev.keyedge.edge.ports;

import dev.keyedge.keyformat.KeyId;

/**
 * Told about every call the verdict cache makes to the control plane (a miss, refresh or
 * revalidation).
 */
@FunctionalInterface
public interface VerifyObserver {
  /** {@code outcome}: the verdict kind, or {@code unavailable}. */
  void loaded(KeyId keyId, long nanos, String kind, String outcome);

  static VerifyObserver none() {
    return (k, n, kind, o) -> {};
  }
}
