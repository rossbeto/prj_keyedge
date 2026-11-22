package dev.keyedge.keyformat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Secrets never appear in toString(): logs and exception messages call it implicitly. */
class NoSecretLeakTest {
  @Test
  void secretsAreMasked() {
    byte[] b = new byte[32];
    Arrays.fill(b, (byte) 0x5a);
    var secret = KeySecret.ofBytes(b);
    String text = secret.text();
    var parsed = new ParseResult.Parsed(KeyEnvironment.LIVE, new KeyId(1, 2), secret);
    var pepper = new Pepper(new PepperVersion(1), b);
    var hash = new SecretHasher().hash(secret, pepper);

    assertThat(secret.toString()).doesNotContain(text).contains("***");
    assertThat(parsed.toString()).doesNotContain(text).contains("***");
    assertThat(pepper.toString()).doesNotContain(text).contains("***");
    assertThat(hash.toString()).doesNotContain(hash.digestBase64());
  }

  @Test
  void displayHintShowsOnlyTheLastFourCharacters() {
    var secret = KeySecret.ofBytes(new byte[32]);
    var hint = DisplayHint.of(KeyEnvironment.LIVE, secret);
    assertThat(hint.value()).isEqualTo("ke_live_…AAAA");
  }
}
