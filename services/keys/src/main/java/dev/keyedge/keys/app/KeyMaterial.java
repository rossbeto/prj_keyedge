package dev.keyedge.keys.app;

import dev.keyedge.keyformat.DisplayHint;
import dev.keyedge.keyformat.KeyEnvironment;
import dev.keyedge.keyformat.KeyFormat;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.KeyIds;
import dev.keyedge.keyformat.KeySecret;
import dev.keyedge.keyformat.SecretHash;
import dev.keyedge.keyformat.SecretHasher;
import dev.keyedge.keys.ports.PepperProvider;
import java.util.random.RandomGenerator;

/**
 * Makes new key material: id, secret, its peppered hash, the full key text shown once, the hint.
 */
public final class KeyMaterial {
  private final KeyIds ids;
  private final RandomGenerator random;
  private final SecretHasher hasher;
  private final PepperProvider peppers;
  private final KeyEnvironment environment;

  public KeyMaterial(
      KeyIds ids,
      RandomGenerator random,
      SecretHasher hasher,
      PepperProvider peppers,
      KeyEnvironment environment) {
    this.ids = ids;
    this.random = random;
    this.hasher = hasher;
    this.peppers = peppers;
    this.environment = environment;
  }

  /** Fresh material for a new key. */
  public Fresh create() {
    return forId(ids.next());
  }

  /** Fresh secret for an existing key id (rotation). */
  public Fresh forId(KeyId keyId) {
    KeySecret secret = KeySecret.random(random);
    return new Fresh(
        keyId,
        hasher.hash(secret, peppers.current()),
        KeyFormat.format(environment, keyId, secret),
        DisplayHint.of(environment, secret).value(),
        environment);
  }

  /** One-time material: {@code fullKey} must be returned to the caller and never stored. */
  public record Fresh(
      KeyId keyId, SecretHash hash, String fullKey, String hint, KeyEnvironment environment) {
    @Override
    public String toString() {
      return "Fresh[" + keyId + ", " + hint + "]";
    }
  }
}
