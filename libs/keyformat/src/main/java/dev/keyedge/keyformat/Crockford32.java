package dev.keyedge.keyformat;

/** Crockford base32 (no I, L, O, U), upper case, used for key ids. */
final class Crockford32 {
  static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
  private static final int[] DECODE = new int[128];

  static {
    java.util.Arrays.fill(DECODE, -1);
    for (int i = 0; i < ALPHABET.length(); i++) {
      DECODE[ALPHABET.charAt(i)] = i;
    }
  }

  private Crockford32() {}

  /** Value of one character, or -1 if it is not in the alphabet (lower case is rejected). */
  static int decode(char c) {
    return c < DECODE.length ? DECODE[c] : -1;
  }
}
