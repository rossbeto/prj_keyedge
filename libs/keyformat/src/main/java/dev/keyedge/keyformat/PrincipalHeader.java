package dev.keyedge.keyformat;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The identity the sidecar asserts to the app next to it (D-26), so the app never trusts "it came
 * from localhost": {@code X-Keyedge-Principal: base64url(tenant|keyId|scopes|iat)} and {@code
 * X-Keyedge-Principal-Signature: base64url(HMAC-SHA256(key, principal|METHOD|path))}. The app
 * verifies the signature, the method and path it received, and the age.
 */
public final class PrincipalHeader {
  /** Header carrying the encoded principal. */
  public static final String PRINCIPAL = "X-Keyedge-Principal";

  /** Header carrying the signature. */
  public static final String SIGNATURE = "X-Keyedge-Principal-Signature";

  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  private PrincipalHeader() {}

  /** Who the request is for. */
  public record Principal(TenantId tenant, KeyId keyId, Set<Scope> scopes) {
    public Principal {
      scopes = Set.copyOf(scopes);
    }
  }

  /** The two header values. */
  public record Signed(String principal, String signature) {}

  public static Signed sign(byte[] key, Principal p, Instant at, String method, String path) {
    String scopes =
        p.scopes().stream()
            .map(Scope::value)
            .collect(Collectors.toCollection(TreeSet::new))
            .stream()
            .collect(Collectors.joining(" "));
    String raw = p.tenant().value() + "|" + p.keyId() + "|" + scopes + "|" + at.getEpochSecond();
    String principal = B64.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    return new Signed(principal, mac(key, principal + "|" + method + "|" + path));
  }

  /**
   * The principal, if the signature matches this method and path and is not older than {@code
   * maxAge}.
   */
  public static Optional<Principal> verify(
      byte[] key,
      String principal,
      String signature,
      String method,
      String path,
      Instant now,
      Duration maxAge) {
    byte[] expected =
        mac(key, principal + "|" + method + "|" + path).getBytes(StandardCharsets.US_ASCII);
    if (!MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII))) {
      return Optional.empty();
    }
    try {
      String[] parts =
          new String(Base64.getUrlDecoder().decode(principal), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (parts.length != 4) {
        return Optional.empty();
      }
      Instant iat = Instant.ofEpochSecond(Long.parseLong(parts[3]));
      if (Duration.between(iat, now).abs().compareTo(maxAge) > 0) {
        return Optional.empty();
      }
      Set<Scope> scopes =
          parts[2].isEmpty()
              ? Set.of()
              : java.util.Arrays.stream(parts[2].split(" "))
                  .map(Scope::new)
                  .collect(Collectors.toSet());
      return KeyId.parse(parts[1]).map(id -> new Principal(new TenantId(parts[0]), id, scopes));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static String mac(byte[] key, String text) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return B64.encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is required by every JDK", e);
    }
  }
}
