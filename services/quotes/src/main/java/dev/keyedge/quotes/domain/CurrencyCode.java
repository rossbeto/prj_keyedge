package dev.keyedge.quotes.domain;

import java.util.regex.Pattern;

/** ISO-4217-shaped code (three capital letters). */
public record CurrencyCode(String value) {
  private static final Pattern VALID = Pattern.compile("[A-Z]{3}");

  public CurrencyCode {
    if (!VALID.matcher(value).matches()) {
      throw new IllegalArgumentException("currency: three capital letters");
    }
  }
}
