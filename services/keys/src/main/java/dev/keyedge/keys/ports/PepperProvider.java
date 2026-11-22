package dev.keyedge.keys.ports;

import dev.keyedge.keyformat.Pepper;
import dev.keyedge.keyformat.PepperVersion;
import java.util.Optional;

/** Server-side HMAC keys; they live in Secrets Manager, never in the table. */
public interface PepperProvider {
  /** The pepper new hashes use. */
  Pepper current();

  /** The pepper of an existing hash; empty if retired. */
  Optional<Pepper> byVersion(PepperVersion version);
}
