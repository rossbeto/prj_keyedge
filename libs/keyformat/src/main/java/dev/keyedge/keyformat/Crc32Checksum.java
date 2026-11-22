package dev.keyedge.keyformat;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * CRC32 over the key body, as 6 base62 characters. Lets the sidecar reject typos and random strings
 * without a network call, and lets secret scanners confirm a match before alerting.
 */
public final class Crc32Checksum {
  private Crc32Checksum() {}

  /** Checksum text for a key body ({@code ke_<env>_<keyId>_<secret>}). */
  public static String of(String body) {
    CRC32 crc = new CRC32();
    crc.update(body.getBytes(StandardCharsets.US_ASCII));
    return Base62.encode(crc.getValue());
  }
}
