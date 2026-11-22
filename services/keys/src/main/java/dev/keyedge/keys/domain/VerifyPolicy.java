package dev.keyedge.keys.domain;

import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeySecret;
import dev.keyedge.keyformat.Pepper;
import dev.keyedge.keyformat.PepperVersion;
import dev.keyedge.keyformat.SecretHash;
import dev.keyedge.keyformat.SecretHasher;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

/**
 * Decides a presented secret against a stored key. Pure: hashing, peppers and time are passed in. A
 * wrong secret is {@code Unknown}, the same answer as a key that does not exist, so callers cannot
 * probe which part was wrong.
 */
public final class VerifyPolicy {
  private final SecretHasher hasher;
  private final Duration ttlHint;

  public VerifyPolicy(SecretHasher hasher, Duration ttlHint) {
    this.hasher = hasher;
    this.ttlHint = ttlHint;
  }

  public KeyCheck check(
      StoredKey key,
      KeySecret presented,
      Function<PepperVersion, Optional<Pepper>> peppers,
      Instant now) {
    if (key.status() instanceof KeyStatus.Revoked(Instant at)) {
      return new KeyCheck.Revoked(key.keyId(), at);
    }
    if (key.expiresAt().isPresent() && !now.isBefore(key.expiresAt().get())) {
      return new KeyCheck.Expired(key.keyId(), key.expiresAt().get());
    }
    if (matches(presented, key.hash(), peppers)) {
      return valid(key, capAt(key.expiresAt(), now));
    }
    if (key.status() instanceof KeyStatus.Rotating(SecretHash previous, Instant retireAfter)
        && matches(presented, previous, peppers)) {
      return now.isBefore(retireAfter)
          ? valid(key, capAt(Optional.of(retireAfter), now))
          : new KeyCheck.RotatedOut(key.keyId(), retireAfter);
    }
    return new KeyCheck.Unknown(key.keyId());
  }

  private boolean matches(
      KeySecret presented, SecretHash stored, Function<PepperVersion, Optional<Pepper>> peppers) {
    return peppers
        .apply(stored.version())
        .map(pepper -> hasher.hash(presented, pepper).matches(stored))
        .orElse(false);
  }

  private Duration capAt(Optional<Instant> limit, Instant now) {
    return limit
        .map(l -> Duration.between(now, l))
        .filter(d -> d.compareTo(ttlHint) < 0)
        .orElse(ttlHint);
  }

  private static KeyCheck valid(StoredKey key, Duration ttl) {
    return new KeyCheck.Valid(key.tenant(), key.keyId(), key.scopes(), key.plan(), ttl);
  }
}
