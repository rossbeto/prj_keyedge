package dev.keyedge.keyformat;

import java.time.Instant;
import java.util.Optional;

/**
 * A 128-bit, time-ordered key id (ULID layout: 48-bit millisecond timestamp + 80 random bits),
 * written as 26 Crockford base32 characters. It is public: it appears in the key and in logs, and
 * it is the lookup key for the stored hash, so the secret is never used as an index.
 */
public record KeyId(long high, long low) implements Comparable<KeyId> {
  /** Number of characters in the text form. */
  public static final int LENGTH = 26;

  /** Parses the 26-character form; empty for anything else (never throws). */
  public static Optional<KeyId> parse(CharSequence text) {
    if (text.length() != LENGTH) {
      return Optional.empty();
    }
    int first = Crockford32.decode(text.charAt(0));
    if (first < 0 || first > 7) { // 26 chars = 130 bits; the top two must be zero
      return Optional.empty();
    }
    long high = 0;
    long low = 0;
    for (int i = 0; i < LENGTH; i++) {
      int v = Crockford32.decode(text.charAt(i));
      if (v < 0) {
        return Optional.empty();
      }
      high = (high << 5) | (low >>> 59);
      low = (low << 5) | v;
    }
    return Optional.of(new KeyId(high, low));
  }

  /** Creation time encoded in the id (millisecond precision). */
  public Instant timestamp() {
    return Instant.ofEpochMilli(high >>> 16);
  }

  @Override
  public String toString() {
    char[] out = new char[LENGTH];
    long hi = high;
    long lo = low;
    for (int i = LENGTH - 1; i >= 0; i--) {
      out[i] = Crockford32.ALPHABET.charAt((int) (lo & 31));
      lo = (lo >>> 5) | (hi << 59);
      hi = hi >>> 5;
    }
    return new String(out);
  }

  @Override
  public int compareTo(KeyId other) {
    int byHigh = Long.compareUnsigned(high, other.high);
    return byHigh != 0 ? byHigh : Long.compareUnsigned(low, other.low);
  }
}
