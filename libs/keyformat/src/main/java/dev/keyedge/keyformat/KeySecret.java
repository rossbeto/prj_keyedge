package dev.keyedge.keyformat;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * The 256-bit random part of a key. Never logged: {@link #toString()} is masked. Only its HMAC is
 * stored (see {@link SecretHasher}).
 */
public final class KeySecret {
  /** Random bytes per secret. */
  public static final int BYTES = 32;

  /** Characters in the base64url (no padding) text form. */
  public static final int TEXT_LENGTH = 43;

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private final byte[] bytes;

  private KeySecret(byte[] bytes) {
    this.bytes = bytes;
  }

  /** A new secret from an injected generator (production passes a {@code SecureRandom}). */
  public static KeySecret random(RandomGenerator random) {
    byte[] b = new byte[BYTES];
    random.nextBytes(b);
    return new KeySecret(b);
  }

  /** Wraps exactly {@value #BYTES} bytes (copied). */
  public static KeySecret ofBytes(byte[] bytes) {
    if (bytes.length != BYTES) {
      throw new IllegalArgumentException("a secret has " + BYTES + " bytes");
    }
    return new KeySecret(bytes.clone());
  }

  /** Parses the canonical text form; empty for anything else, including non-canonical encodings. */
  public static Optional<KeySecret> parse(CharSequence text) {
    if (text.length() != TEXT_LENGTH) {
      return Optional.empty();
    }
    String s = text.toString();
    for (int i = 0; i < s.length(); i++) {
      if (!isBase64Url(s.charAt(i))) {
        return Optional.empty();
      }
    }
    byte[] decoded = Base64.getUrlDecoder().decode(s);
    // 43 chars carry 258 bits: reject texts whose unused low bits are set (one secret, one text).
    return ENCODER.encodeToString(decoded).equals(s)
        ? Optional.of(new KeySecret(decoded))
        : Optional.empty();
  }

  static boolean isBase64Url(char c) {
    return (c >= 'A' && c <= 'Z')
        || (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9')
        || c == '-'
        || c == '_';
  }

  /** A copy of the raw bytes. */
  public byte[] bytes() {
    return bytes.clone();
  }

  /** The text form used inside the key. */
  public String text() {
    return ENCODER.encodeToString(bytes);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof KeySecret other && MessageDigest.isEqual(bytes, other.bytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(bytes);
  }

  @Override
  public String toString() {
    return "KeySecret[***]";
  }
}
