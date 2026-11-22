package dev.keyedge.keyformat;

/** Fixed-width base62 for the 32-bit checksum (6 characters cover 2^32). */
final class Base62 {
  static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  static final int WIDTH = 6;

  private Base62() {}

  static String encode(long unsigned32) {
    char[] out = new char[WIDTH];
    long v = unsigned32;
    for (int i = WIDTH - 1; i >= 0; i--) {
      out[i] = ALPHABET.charAt((int) (v % 62));
      v /= 62;
    }
    return new String(out);
  }

  static boolean isValid(CharSequence text) {
    if (text.length() != WIDTH) {
      return false;
    }
    for (int i = 0; i < WIDTH; i++) {
      if (ALPHABET.indexOf(text.charAt(i)) < 0) {
        return false;
      }
    }
    return true;
  }
}
