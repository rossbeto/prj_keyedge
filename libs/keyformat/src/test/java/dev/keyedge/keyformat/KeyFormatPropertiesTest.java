package dev.keyedge.keyformat;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** The key format specification as properties. */
class KeyFormatPropertiesTest {
  @Provide
  Arbitrary<KeyId> ids() {
    return Combinators.combine(Arbitraries.longs(), Arbitraries.longs()).as(KeyId::new);
  }

  @Provide
  Arbitrary<KeySecret> secrets() {
    return Arbitraries.bytes().array(byte[].class).ofSize(KeySecret.BYTES).map(KeySecret::ofBytes);
  }

  @Property(tries = 2000)
  void formatThenParseRoundTrips(
      @ForAll KeyEnvironment env, @ForAll("ids") KeyId id, @ForAll("secrets") KeySecret secret) {
    String key = KeyFormat.format(env, id, secret);
    assertThat(key).hasSize(KeyFormat.LENGTH).startsWith("ke_" + env.label() + "_");
    assertThat(KeyFormat.parse(key)).isEqualTo(new ParseResult.Parsed(env, id, secret));
  }

  @Property(tries = 2000)
  void anySingleCharacterChangeIsRejected(
      @ForAll("ids") KeyId id,
      @ForAll("secrets") KeySecret secret,
      @ForAll("positions") int position,
      @ForAll("replacements") char replacement) {
    String key = KeyFormat.format(KeyEnvironment.LIVE, id, secret);
    if (key.charAt(position) == replacement) {
      return; // not a change
    }
    String changed = key.substring(0, position) + replacement + key.substring(position + 1);
    assertThat(KeyFormat.parse(changed)).isInstanceOf(ParseResult.Malformed.class);
  }

  @Provide
  Arbitrary<Integer> positions() {
    return Arbitraries.integers().between(0, KeyFormat.LENGTH - 1);
  }

  @Provide
  Arbitrary<Character> replacements() {
    return Arbitraries.of(
        "0123456789ABCDEFGHJKMNPQRSTVWXYZabcdefghijklmnopqrstuvwxyz-_ ."
            .chars()
            .mapToObj(c -> (char) c)
            .toList());
  }

  @Property(tries = 1000)
  void keyIdTextRoundTripsAndKeepsOrder(@ForAll("ids") KeyId a, @ForAll("ids") KeyId b) {
    assertThat(KeyId.parse(a.toString())).contains(a);
    assertThat(Integer.signum(a.toString().compareTo(b.toString())))
        .isEqualTo(Integer.signum(a.compareTo(b)));
  }
}
