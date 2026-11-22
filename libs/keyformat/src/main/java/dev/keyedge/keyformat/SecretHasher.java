package dev.keyedge.keyformat;

import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 with a versioned pepper. Not bcrypt/argon2 on purpose: a 256-bit random secret cannot
 * be brute-forced, so a slow hash would add latency to every request and no security; the pepper is
 * what makes a leaked table useless (ADR 0003).
 */
public final class SecretHasher {
  private static final String ALGORITHM = "HmacSHA256";

  /** Hash of a secret with a pepper. A new {@link Mac} per call: {@code Mac} is not thread-safe. */
  public SecretHash hash(KeySecret secret, Pepper pepper) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(pepper.key(), ALGORITHM));
      return new SecretHash(pepper.version(), mac.doFinal(secret.bytes()));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is required by every JDK", e);
    }
  }
}
