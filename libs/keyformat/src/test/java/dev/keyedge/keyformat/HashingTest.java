package dev.keyedge.keyformat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class HashingTest {
  private static final byte[] PEPPER_BYTES = filled(131, (byte) 0xaa);
  private final SecretHasher hasher = new SecretHasher();

  private static byte[] filled(int n, byte b) {
    byte[] a = new byte[n];
    Arrays.fill(a, b);
    return a;
  }

  private static KeySecret sequentialSecret() {
    byte[] b = new byte[32];
    for (int i = 0; i < b.length; i++) {
      b[i] = (byte) i;
    }
    return KeySecret.ofBytes(b);
  }

  @Test
  void matchesAnIndependentHmacVector() {
    // HMAC-SHA256(key = 0xaa x 131, msg = bytes 0..31), computed with Python's hmac module.
    var hash = hasher.hash(sequentialSecret(), new Pepper(new PepperVersion(1), PEPPER_BYTES));
    assertThat(hash.digestBase64()).isEqualTo("Skid1V0Ot6nAAp9b2-kqQbL16aIMPxnemCpqtZ8Se8k");
  }

  @Test
  void sameSecretWithAnotherPepperVersionDoesNotMatch() {
    var v1 = hasher.hash(sequentialSecret(), new Pepper(new PepperVersion(1), PEPPER_BYTES));
    var v2 = hasher.hash(sequentialSecret(), new Pepper(new PepperVersion(2), PEPPER_BYTES));
    assertThat(v1.matches(v2)).isFalse(); // same bytes, different version
    assertThat(v1.matches(SecretHash.of(new PepperVersion(1), v1.digestBase64()))).isTrue();
    assertThat(v1).isEqualTo(SecretHash.of(new PepperVersion(1), v1.digestBase64()));
    assertThat(v1.hashCode())
        .isEqualTo(SecretHash.of(new PepperVersion(1), v1.digestBase64()).hashCode());
  }

  @Test
  void differentSecretsDoNotMatch() {
    var pepper = new Pepper(new PepperVersion(1), PEPPER_BYTES);
    var a = hasher.hash(sequentialSecret(), pepper);
    var b = hasher.hash(KeySecret.ofBytes(new byte[32]), pepper);
    assertThat(a.matches(b)).isFalse();
  }

  @Test
  void rejectsWeakInputs() {
    assertThatThrownBy(() -> new Pepper(new PepperVersion(1), new byte[16]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PepperVersion(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> KeySecret.ofBytes(new byte[31]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SecretHash(new PepperVersion(1), new byte[31]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void valueSemanticsUseContentNotIdentity() {
    var p1 = new Pepper(new PepperVersion(3), PEPPER_BYTES);
    var p2 = new Pepper(new PepperVersion(3), PEPPER_BYTES.clone());
    assertThat(p1).isEqualTo(p2).hasSameHashCodeAs(p2);
    assertThat(p1).isNotEqualTo(new Pepper(new PepperVersion(4), PEPPER_BYTES));
    assertThat(sequentialSecret())
        .isEqualTo(sequentialSecret())
        .hasSameHashCodeAs(sequentialSecret());
    assertThat(sequentialSecret()).isNotEqualTo(KeySecret.ofBytes(new byte[32]));
  }

  @Test
  void inputsAreCopiedDefensively() {
    byte[] raw = new byte[32];
    var secret = KeySecret.ofBytes(raw);
    raw[0] = 1;
    secret.bytes()[1] = 1;
    assertThat(secret).isEqualTo(KeySecret.ofBytes(new byte[32]));
  }
}
