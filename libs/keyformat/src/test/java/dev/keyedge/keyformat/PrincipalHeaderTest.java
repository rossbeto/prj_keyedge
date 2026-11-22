package dev.keyedge.keyformat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PrincipalHeaderTest {
  private static final byte[] KEY =
      "principal-key-32-bytes-0123456789".getBytes(StandardCharsets.UTF_8);
  private static final Instant AT = Instant.parse("2026-11-23T10:00:00Z");
  private static final Duration AGE = Duration.ofSeconds(30);
  private final PrincipalHeader.Principal p =
      new PrincipalHeader.Principal(
          new TenantId("acme"),
          new KeyId(1, 2),
          Set.of(new Scope("quotes:read"), new Scope("a:b")));

  @Test
  void roundTripsForTheSameMethodAndPath() {
    var s = PrincipalHeader.sign(KEY, p, AT, "GET", "/v1/quotes/x");
    assertThat(
            PrincipalHeader.verify(
                KEY, s.principal(), s.signature(), "GET", "/v1/quotes/x", AT.plusSeconds(5), AGE))
        .contains(p);
  }

  @Test
  void rejectsOtherPathsMethodsKeysOldAgeAndTampering() {
    var s = PrincipalHeader.sign(KEY, p, AT, "GET", "/v1/quotes/x");
    assertThat(
            PrincipalHeader.verify(
                KEY, s.principal(), s.signature(), "GET", "/v1/quotes/y", AT, AGE))
        .isEmpty();
    assertThat(
            PrincipalHeader.verify(
                KEY, s.principal(), s.signature(), "POST", "/v1/quotes/x", AT, AGE))
        .isEmpty();
    assertThat(
            PrincipalHeader.verify(
                "another-key-32-bytes-0123456789xx".getBytes(StandardCharsets.UTF_8),
                s.principal(),
                s.signature(),
                "GET",
                "/v1/quotes/x",
                AT,
                AGE))
        .isEmpty();
    assertThat(
            PrincipalHeader.verify(
                KEY, s.principal(), s.signature(), "GET", "/v1/quotes/x", AT.plusSeconds(31), AGE))
        .isEmpty();
    var other =
        PrincipalHeader.sign(
            KEY,
            new PrincipalHeader.Principal(new TenantId("globex"), new KeyId(1, 2), Set.of()),
            AT,
            "GET",
            "/v1/quotes/x");
    assertThat(
            PrincipalHeader.verify(
                KEY, other.principal(), s.signature(), "GET", "/v1/quotes/x", AT, AGE))
        .isEmpty();
  }

  @Test
  void malformedPrincipalsAreRejectedEvenWithAValidSignatureOverThem() {
    for (String raw :
        new String[] {
          "bad", "a|b|c", "acme|not-an-id|q:r|1", "acme|" + new KeyId(1, 2) + "|q:r|notanumber"
        }) {
      String principal =
          java.util.Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
      var forged = PrincipalHeader.sign(KEY, p, AT, "GET", "/p");
      // re-sign the raw principal the way the signer would, to isolate parsing from signature
      // checks
      String sig = new PrincipalHeaderTestSupport().sign(KEY, principal, "GET", "/p");
      assertThat(PrincipalHeader.verify(KEY, principal, sig, "GET", "/p", AT, AGE))
          .as(raw)
          .isEmpty();
      assertThat(forged).isNotNull();
    }
    var noScopes =
        PrincipalHeader.sign(
            KEY,
            new PrincipalHeader.Principal(new TenantId("acme"), new KeyId(1, 2), Set.of()),
            AT,
            "GET",
            "/p");
    assertThat(
            PrincipalHeader.verify(
                KEY, noScopes.principal(), noScopes.signature(), "GET", "/p", AT, AGE))
        .hasValueSatisfying(x -> assertThat(x.scopes()).isEmpty());
  }

  /** Signs arbitrary principal text with the same MAC as the header (test-only helper). */
  static final class PrincipalHeaderTestSupport {
    String sign(byte[] key, String principal, String method, String path) {
      try {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
        return java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                mac.doFinal(
                    (principal + "|" + method + "|" + path).getBytes(StandardCharsets.UTF_8)));
      } catch (java.security.GeneralSecurityException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
