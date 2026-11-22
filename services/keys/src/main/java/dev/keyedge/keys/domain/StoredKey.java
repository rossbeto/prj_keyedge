package dev.keyedge.keys.domain;

import dev.keyedge.keyformat.KeyEnvironment;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.RatePlan;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.keyformat.SecretHash;
import dev.keyedge.keyformat.TenantId;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * What the control plane stores for a key: never the secret, only its peppered hash. {@code
 * version} is the optimistic-lock counter (and the ETag).
 */
public record StoredKey(
    TenantId tenant,
    KeyId keyId,
    KeyEnvironment environment,
    String name,
    Set<Scope> scopes,
    RatePlan plan,
    SecretHash hash,
    String hint,
    KeyStatus status,
    Instant createdAt,
    Optional<Instant> expiresAt,
    long version) {

  public StoredKey {
    scopes = Set.copyOf(scopes);
    if (name.isBlank() || name.length() > 80) {
      throw new IllegalArgumentException("name must be 1-80 characters");
    }
  }

  /** The same key with a new status and hash, one version later. */
  public StoredKey next(KeyStatus newStatus, SecretHash newHash, String newHint) {
    return new StoredKey(
        tenant,
        keyId,
        environment,
        name,
        scopes,
        plan,
        newHash,
        newHint,
        newStatus,
        createdAt,
        expiresAt,
        version + 1);
  }
}
