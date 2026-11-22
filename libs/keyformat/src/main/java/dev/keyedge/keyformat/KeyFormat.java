package dev.keyedge.keyformat;

/**
 * Formats and parses {@code ke_<env>_<keyId>_<secret>_<crc>} (85 characters). The parser checks
 * length first and never throws, so it is safe on any untrusted input.
 */
public final class KeyFormat {
  /** Prefix shared by every key; secret scanners match {@code ke_(live|test)_}. */
  public static final String PREFIX = "ke_";

  /** Total length of a key. */
  public static final int LENGTH =
      3 + 4 + 1 + KeyId.LENGTH + 1 + KeySecret.TEXT_LENGTH + 1 + Base62.WIDTH;

  private static final int ENV_START = 3;
  private static final int ID_START = ENV_START + 5;
  private static final int SECRET_START = ID_START + KeyId.LENGTH + 1;
  private static final int CRC_START = SECRET_START + KeySecret.TEXT_LENGTH + 1;

  private KeyFormat() {}

  /** The full key text. Shown to the user exactly once, at creation or rotation. */
  public static String format(KeyEnvironment env, KeyId keyId, KeySecret secret) {
    String body = PREFIX + env.label() + "_" + keyId + "_" + secret.text();
    return body + "_" + Crc32Checksum.of(body);
  }

  /** Parses an untrusted string. */
  public static ParseResult parse(String raw) {
    if (raw.length() != LENGTH) {
      return malformed(MalformedReason.LENGTH);
    }
    if (!raw.startsWith(PREFIX)) {
      return malformed(MalformedReason.PREFIX);
    }
    var env = KeyEnvironment.ofLabel(raw.subSequence(ENV_START, ENV_START + 4));
    if (env.isEmpty()) {
      return malformed(MalformedReason.ENVIRONMENT);
    }
    if (raw.charAt(ID_START - 1) != '_'
        || raw.charAt(SECRET_START - 1) != '_'
        || raw.charAt(CRC_START - 1) != '_') {
      return malformed(MalformedReason.CHARSET);
    }
    var keyId = KeyId.parse(raw.subSequence(ID_START, ID_START + KeyId.LENGTH));
    var secret =
        KeySecret.parse(raw.subSequence(SECRET_START, SECRET_START + KeySecret.TEXT_LENGTH));
    String crc = raw.substring(CRC_START);
    if (keyId.isEmpty() || secret.isEmpty() || !Base62.isValid(crc)) {
      return malformed(MalformedReason.CHARSET);
    }
    if (!Crc32Checksum.of(raw.substring(0, CRC_START - 1)).equals(crc)) {
      return malformed(MalformedReason.CHECKSUM);
    }
    return new ParseResult.Parsed(env.get(), keyId.get(), secret.get());
  }

  private static ParseResult malformed(MalformedReason reason) {
    return new ParseResult.Malformed(reason);
  }
}
