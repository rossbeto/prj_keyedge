package dev.keyedge.keyformat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InternalSignatureTest {
  private static final byte[] CRED =
      "a-32-byte-test-credential-000000".getBytes(StandardCharsets.UTF_8);
  private static final byte[] BODY = "{\"keyId\":\"x\"}".getBytes(StandardCharsets.UTF_8);
  private static final Instant AT = Instant.parse("2026-11-23T10:00:00Z");
  private static final Duration SKEW = Duration.ofSeconds(30);
  private static final String N = "nonce-0123456789abcdef";

  @Test
  void aSignedRequestVerifies() {
    String sig = InternalSignature.sign(CRED, "POST", "/internal/v1/verify", AT, N, BODY);
    assertThat(
            InternalSignature.verify(
                CRED,
                new InternalSignature.SignedRequest(
                    "POST", "/internal/v1/verify", AT, N, BODY, sig),
                AT.plusSeconds(5),
                SKEW))
        .isTrue();
  }

  @Test
  void theNonceMakesIdenticalRequestsDistinctAndShortNoncesFail() {
    String a = InternalSignature.sign(CRED, "POST", "/p", AT, N, BODY);
    String b = InternalSignature.sign(CRED, "POST", "/p", AT, N + "x", BODY);
    assertThat(a).isNotEqualTo(b);
    String shortSig = InternalSignature.sign(CRED, "POST", "/p", AT, "short", BODY);
    assertThat(
            InternalSignature.verify(
                CRED,
                new InternalSignature.SignedRequest("POST", "/p", AT, "short", BODY, shortSig),
                AT,
                SKEW))
        .isFalse();
    assertThat(
            InternalSignature.verify(
                CRED,
                new InternalSignature.SignedRequest("POST", "/p", AT, N + "x", BODY, a),
                AT,
                SKEW))
        .as("nonce is signed")
        .isFalse();
  }

  @Test
  void anyChangeOrOldTimestampFails() {
    String sig = InternalSignature.sign(CRED, "POST", "/internal/v1/verify", AT, N, BODY);
    byte[] other = "{\"keyId\":\"y\"}".getBytes(StandardCharsets.UTF_8);
    assertThat(
            InternalSignature.verify(
                CRED,
                new InternalSignature.SignedRequest(
                    "POST", "/internal/v1/verify", AT, N, other, sig),
                AT,
                SKEW))
        .isFalse();
    assertThat(
            InternalSignature.verify(
                CRED,
                new InternalSignature.SignedRequest("POST", "/internal/v1/usage", AT, N, BODY, sig),
                AT,
                SKEW))
        .isFalse();
    assertThat(
            InternalSignature.verify(
                CRED,
                new InternalSignature.SignedRequest("GET", "/internal/v1/verify", AT, N, BODY, sig),
                AT,
                SKEW))
        .isFalse();
    assertThat(
            InternalSignature.verify(
                CRED,
                new InternalSignature.SignedRequest(
                    "POST", "/internal/v1/verify", AT, N, BODY, sig),
                AT.plusSeconds(31),
                SKEW))
        .isFalse();
    assertThat(
            InternalSignature.verify(
                CRED,
                new InternalSignature.SignedRequest(
                    "POST", "/internal/v1/verify", AT, N, BODY, sig),
                AT.minusSeconds(31),
                SKEW))
        .isFalse();
    byte[] wrong = "another-32-byte-test-credential-0".getBytes(StandardCharsets.UTF_8);
    assertThat(
            InternalSignature.verify(
                wrong,
                new InternalSignature.SignedRequest(
                    "POST", "/internal/v1/verify", AT, N, BODY, sig),
                AT,
                SKEW))
        .isFalse();
  }
}
