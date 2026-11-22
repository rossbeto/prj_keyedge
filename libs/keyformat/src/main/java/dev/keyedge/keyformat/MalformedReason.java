package dev.keyedge.keyformat;

/** Why a presented key string is not a keyedge key. Checked in this order, cheapest first. */
public enum MalformedReason {
  /** Wrong total length (checked first, so huge inputs are rejected without work). */
  LENGTH,
  /** Does not start with {@code ke_}. */
  PREFIX,
  /** Unknown environment label. */
  ENVIRONMENT,
  /** A separator or a character outside its part's alphabet. */
  CHARSET,
  /** Well-formed, but the checksum does not match: a typo or a made-up key. */
  CHECKSUM
}
