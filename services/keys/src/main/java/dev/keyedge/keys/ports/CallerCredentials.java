package dev.keyedge.keys.ports;

import java.util.Optional;

/** Credentials of internal callers (sidecars) that sign /internal requests (D-25). */
public interface CallerCredentials {
  Optional<byte[]> credential(String callerId);
}
