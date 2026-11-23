package dev.keyedge.quotes.domain;

import java.util.regex.Pattern;

/** A product id as the upstreams know it. */
public record Sku(String value) {
  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,39}");

  public Sku {
    if (!VALID.matcher(value).matches()) {
      throw new IllegalArgumentException("sku: 1-40 letters, digits or '-'");
    }
  }
}
