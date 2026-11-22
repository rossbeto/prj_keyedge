package dev.keyedge.keyformat;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/**
 * A server-side HMAC key ("pepper") with its version. It lives in Secrets Manager, never in the
 * table, so a leaked table alone is useless. Never logged.
 */
public final class Pepper {
  /** Minimum key size in bytes. */
  public static final int MIN_BYTES = 32;

  private final PepperVersion version;
  private final byte[] key;

  public Pepper(PepperVersion version, byte[] key) {
    if (key.length < MIN_BYTES) {
      throw new IllegalArgumentException("a pepper needs at least " + MIN_BYTES + " bytes");
    }
    this.version = version;
    this.key = key.clone();
  }

  public PepperVersion version() {
    return version;
  }

  byte[] key() {
    return key.clone();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Pepper other
        && version.equals(other.version)
        && MessageDigest.isEqual(key, other.key);
  }

  @Override
  public int hashCode() {
    return Objects.hash(version, Arrays.hashCode(key));
  }

  @Override
  public String toString() {
    return "Pepper[v" + version.value() + ", ***]";
  }
}
