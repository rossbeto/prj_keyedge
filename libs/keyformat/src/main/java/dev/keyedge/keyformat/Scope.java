package dev.keyedge.keyformat;

import java.util.regex.Pattern;

/** A permission carried by a key, e.g. {@code quotes:read}. */
public record Scope(String value) {
  private static final Pattern VALID = Pattern.compile("[a-z][a-z0-9-]*:[a-z][a-z0-9-]*");

  public Scope {
    if (!VALID.matcher(value).matches()) {
      throw new IllegalArgumentException("invalid scope");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
