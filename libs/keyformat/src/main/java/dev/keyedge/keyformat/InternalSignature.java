package dev.keyedge.keyformat;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC signature for sidecar → control-plane calls (D-25). Signs method, path, timestamp, a random
 * nonce and the SHA-256 of the body, so neither the body nor the target can be changed, an old
 * request cannot be replayed outside the skew window, and the receiver's replay cache (signatures
 * seen) never mistakes two legitimate identical requests in the same second for a replay: the nonce
 * makes every signature unique.
 *
 * <pre>
 *   string-to-sign = METHOD "\n" path "\n" epochSeconds "\n" nonce "\n" hex(sha256(body))
 *   signature      = base64url(HMAC-SHA256(credential, string-to-sign))
 * </pre>
 */
public final class InternalSignature {
  /** Header with the caller's credential id. */
  public static final String CALLER = "X-Keyedge-Caller";

  /** Header with the signing time in epoch seconds. */
  public static final String TIMESTAMP = "X-Keyedge-Timestamp";

  /** Header with a random per-request value (at least {@value #MIN_NONCE} characters). */
  public static final String NONCE = "X-Keyedge-Nonce";

  /** Header with the signature. */
  public static final String SIGNATURE = "X-Keyedge-Signature";

  /** Shortest accepted nonce. */
  public static final int MIN_NONCE = 16;

  private InternalSignature() {}

  /** The signature for a request. */
  public static String sign(
      byte[] credential, String method, String path, Instant at, String nonce, byte[] body) {
    try {
      String toSign =
          method
              + "\n"
              + path
              + "\n"
              + at.getEpochSecond()
              + "\n"
              + nonce
              + "\n"
              + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(credential, "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256/SHA-256 are required by every JDK", e);
    }
  }

  /** What the receiver got: the signed parts of the request and the signature header. */
  public record SignedRequest(
      String method, String path, Instant signedAt, String nonce, byte[] body, String signature) {}

  /**
   * Constant-time check of signature, nonce length and skew; replay protection is the receiver's
   * job.
   */
  public static boolean verify(byte[] credential, SignedRequest r, Instant now, Duration maxSkew) {
    if (r.nonce().length() < MIN_NONCE
        || Duration.between(r.signedAt(), now).abs().compareTo(maxSkew) > 0) {
      return false;
    }
    byte[] expected =
        sign(credential, r.method(), r.path(), r.signedAt(), r.nonce(), r.body())
            .getBytes(StandardCharsets.US_ASCII);
    return MessageDigest.isEqual(expected, r.signature().getBytes(StandardCharsets.US_ASCII));
  }
}
