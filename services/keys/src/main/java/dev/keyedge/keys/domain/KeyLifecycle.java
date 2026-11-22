package dev.keyedge.keys.domain;

import dev.keyedge.keyformat.SecretHash;
import java.time.Duration;
import java.time.Instant;

/** State changes of a key: rotate with a grace period, revoke. Pure. */
public final class KeyLifecycle {
  private KeyLifecycle() {}

  /**
   * New secret; the current one stays valid for {@code grace}. Rotating while already rotating
   * drops the oldest secret: only one previous secret is ever valid.
   */
  public static StoredKey rotate(
      StoredKey key, SecretHash newHash, String newHint, Duration grace, Instant now) {
    if (key.status() instanceof KeyStatus.Revoked) {
      throw new IllegalStateException("a revoked key cannot be rotated");
    }
    return key.next(new KeyStatus.Rotating(key.hash(), now.plus(grace)), newHash, newHint);
  }

  /** Revoked now; revoking a revoked key keeps the first revocation time (idempotent). */
  public static StoredKey revoke(StoredKey key, Instant now) {
    if (key.status() instanceof KeyStatus.Revoked) {
      return key;
    }
    return key.next(new KeyStatus.Revoked(now), key.hash(), key.hint());
  }
}
