package dev.keyedge.edge.ports;

import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.KeySecret;

/** The control plane's verdict for a presented key (through the cache). */
public interface Verdicts {
  /** The verdict; throws {@link Unavailable} when the control plane cannot answer in time. */
  KeyCheck verdict(KeyId keyId, KeySecret secret);

  /** The control plane is unreachable or too slow (Phase 008 decides what to do instead). */
  final class Unavailable extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public Unavailable(String message, @org.jspecify.annotations.Nullable Throwable cause) {
      super(message, cause);
    }
  }
}
