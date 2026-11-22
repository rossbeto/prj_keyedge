package dev.keyedge.keyformat;

import java.util.regex.Pattern;

/** A tenant (the owner of keys). Lower-case slug, 3–40 characters. */
public record TenantId(String value) {
  private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9-]{2,39}");

  public TenantId {
    if (!VALID.matcher(value).matches()) {
      throw new IllegalArgumentException("invalid tenant id");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
