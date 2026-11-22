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

  @Test
  void aSignedRequestVerifies() {
    String sig = InternalSignature.sign(CRED, "POST", "/internal/v1/verify", AT, BODY);
    assertThat(
            InternalSignature.verify(
                CRED, "POST", "/internal/v1/verify", AT, BODY, sig, AT.plusSeconds(5), SKEW))
        .isTrue();
  }

  @Test
  void anyChangeOrOldTimestampFails() {
    String sig = InternalSignature.sign(CRED, "POST", "/internal/v1/verify", AT, BODY);
    byte[] other = "{\"keyId\":\"y\"}".getBytes(StandardCharsets.UTF_8);
    assertThat(
            InternalSignature.verify(CRED, "POST", "/internal/v1/verify", AT, other, sig, AT, SKEW))
        .isFalse();
    assertThat(
            InternalSignature.verify(CRED, "POST", "/internal/v1/usage", AT, BODY, sig, AT, SKEW))
        .isFalse();
    assertThat(
            InternalSignature.verify(CRED, "GET", "/internal/v1/verify", AT, BODY, sig, AT, SKEW))
        .isFalse();
    assertThat(
            InternalSignature.verify(
                CRED, "POST", "/internal/v1/verify", AT, BODY, sig, AT.plusSeconds(31), SKEW))
        .isFalse();
    assertThat(
            InternalSignature.verify(
                CRED, "POST", "/internal/v1/verify", AT, BODY, sig, AT.minusSeconds(31), SKEW))
        .isFalse();
    byte[] wrong = "another-32-byte-test-credential-0".getBytes(StandardCharsets.UTF_8);
    assertThat(
            InternalSignature.verify(wrong, "POST", "/internal/v1/verify", AT, BODY, sig, AT, SKEW))
        .isFalse();
  }
}
