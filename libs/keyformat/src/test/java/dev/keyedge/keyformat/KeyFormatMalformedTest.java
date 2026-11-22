package dev.keyedge.keyformat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class KeyFormatMalformedTest {
  // Independent vector: body and CRC32 computed with Python's zlib (steps doc, Phase 002).
  static final String ZERO_BODY =
      "ke_test_00000000000000000000000000_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
  static final String ZERO_KEY = ZERO_BODY + "_0wcODk";

  static Stream<Arguments> cases() {
    String valid = ZERO_KEY;
    return Stream.of(
        Arguments.of("", MalformedReason.LENGTH),
        Arguments.of("ke_live_x", MalformedReason.LENGTH),
        Arguments.of("x".repeat(10_000), MalformedReason.LENGTH),
        Arguments.of("kx" + valid.substring(2), MalformedReason.PREFIX),
        Arguments.of("ke_prod" + valid.substring(7), MalformedReason.ENVIRONMENT),
        Arguments.of(valid.substring(0, 7) + "-" + valid.substring(8), MalformedReason.CHARSET),
        Arguments.of(valid.substring(0, 8) + "I" + valid.substring(9), MalformedReason.CHARSET),
        Arguments.of(valid.substring(0, 8) + "8" + valid.substring(9), MalformedReason.CHARSET),
        Arguments.of(valid.substring(0, 35) + "*" + valid.substring(36), MalformedReason.CHARSET),
        Arguments.of(valid.substring(0, 77) + "B" + valid.substring(78), MalformedReason.CHARSET),
        Arguments.of(valid.substring(0, 79) + "*" + valid.substring(80), MalformedReason.CHARSET),
        Arguments.of(valid.substring(0, 79) + "0wcODl", MalformedReason.CHECKSUM),
        Arguments.of(valid.substring(0, 9) + "1" + valid.substring(10), MalformedReason.CHECKSUM));
  }

  @ParameterizedTest(name = "{1}: {0}")
  @MethodSource("cases")
  void rejectsWithReason(String raw, MalformedReason reason) {
    assertThat(KeyFormat.parse(raw)).isEqualTo(new ParseResult.Malformed(reason));
  }

  @Test
  void matchesAnIndependentlyComputedVector() {
    var parsed = KeyFormat.parse(ZERO_KEY);
    assertThat(parsed)
        .isEqualTo(
            new ParseResult.Parsed(
                KeyEnvironment.TEST, new KeyId(0, 0), KeySecret.ofBytes(new byte[32])));
    assertThat(Crc32Checksum.of(ZERO_BODY)).isEqualTo("0wcODk");
  }

  @Test
  void secretAndIdAloneParseOnlyCanonicalText() {
    assertThat(KeySecret.parse("A".repeat(42) + "B")).isEmpty(); // unused low bits set
    assertThat(KeySecret.parse("A".repeat(42))).isEmpty();
    assertThat(KeyId.parse("8" + "0".repeat(25))).isEmpty(); // more than 128 bits
    assertThat(KeyId.parse("0".repeat(25) + "o")).isEmpty(); // lower case
    assertThat(KeyId.parse("0".repeat(25) + "é")).isEmpty(); // non-ASCII
  }

  @Test
  void environmentLabelsAreFixed() {
    assertThat(KeyEnvironment.ofLabel("live")).contains(KeyEnvironment.LIVE);
    assertThat(KeyEnvironment.ofLabel("LIVE")).isEmpty();
  }
}
