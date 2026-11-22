package dev.keyedge.keyformat;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * The verdict on a presented key, shared by the control plane (which decides) and the sidecar
 * (which caches and enforces). A sealed type: every consumer handles every case, checked by the
 * compiler.
 */
public sealed interface KeyCheck {
  /** The key is valid: who it belongs to, what it may do, how fast, and how long to cache that. */
  record Valid(TenantId tenant, KeyId keyId, Set<Scope> scopes, RatePlan plan, Duration ttlHint)
      implements KeyCheck {
    public Valid {
      scopes = Set.copyOf(scopes);
    }
  }

  /** Not a keyedge key at all (decided locally, no lookup). */
  record Malformed(MalformedReason reason) implements KeyCheck {}

  /** Well-formed, but no such key or the secret does not match. */
  record Unknown(KeyId keyId) implements KeyCheck {}

  /** Revoked by its owner. */
  record Revoked(KeyId keyId, Instant at) implements KeyCheck {}

  /** Past its expiry. */
  record Expired(KeyId keyId, Instant at) implements KeyCheck {}

  /** A rotated-away secret past its grace period. */
  record RotatedOut(KeyId keyId, Instant retiredAt) implements KeyCheck {}
}
