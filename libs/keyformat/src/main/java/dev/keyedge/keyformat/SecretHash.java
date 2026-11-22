package dev.keyedge.keyformat;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** HMAC-SHA256 of a secret, with the pepper version used. This, not the secret, is stored. */
public final class SecretHash {
  private final PepperVersion version;
  private final byte[] digest;

  public SecretHash(PepperVersion version, byte[] digest) {
    if (digest.length != 32) {
      throw new IllegalArgumentException("HMAC-SHA256 digests have 32 bytes");
    }
    this.version = version;
    this.digest = digest.clone();
  }

  /** Restores a stored hash ({@link #digestBase64()} form). */
  public static SecretHash of(PepperVersion version, String digestBase64) {
    return new SecretHash(version, Base64.getUrlDecoder().decode(digestBase64));
  }

  public PepperVersion version() {
    return version;
  }

  /** The digest as unpadded base64url, for storage. */
  public String digestBase64() {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
  }

  /**
   * Constant-time comparison: the pepper versions must match, then {@link MessageDigest#isEqual}
   * compares every byte, so timing does not reveal how many leading bytes were right.
   */
  @SuppressWarnings("ShortCircuitBoolean") // & on purpose: always run the byte comparison
  public boolean matches(SecretHash other) {
    return version.equals(other.version) & MessageDigest.isEqual(digest, other.digest);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof SecretHash other && matches(other);
  }

  @Override
  public int hashCode() {
    return Objects.hash(version, Arrays.hashCode(digest));
  }

  @Override
  public String toString() {
    return "SecretHash[v" + version.value() + "]";
  }
}
