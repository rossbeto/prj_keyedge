package dev.keyedge.keyformat;

/** Result of parsing a presented key: a value, never an exception. */
public sealed interface ParseResult {
  /** A well-formed key with a valid checksum. */
  record Parsed(KeyEnvironment environment, KeyId keyId, KeySecret secret) implements ParseResult {
    @Override
    public String toString() {
      return "Parsed[" + environment + ", " + keyId + ", secret=***]";
    }
  }

  /** Not a keyedge key. */
  record Malformed(MalformedReason reason) implements ParseResult {}
}
