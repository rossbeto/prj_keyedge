package dev.keyedge.keys.domain;

import dev.keyedge.keyformat.SecretHash;
import java.time.Instant;

/** Lifecycle of a stored key. */
public sealed interface KeyStatus {
  /** In use with one secret. */
  record Active() implements KeyStatus {}

  /** Rotated: the previous secret stays valid until {@code retireAfter} (grace period). */
  record Rotating(SecretHash previous, Instant retireAfter) implements KeyStatus {}

  /** Revoked: never valid again. */
  record Revoked(Instant at) implements KeyStatus {}

  /** Label used in the list index and the API. */
  default String label() {
    return switch (this) {
      case Active a -> "ACTIVE";
      case Rotating r -> "ACTIVE"; // still usable
      case Revoked r -> "REVOKED";
    };
  }
}
