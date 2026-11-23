package dev.keyedge.quotes.domain;

/** How many units a quote is for: 1..1000. */
public record Quantity(int value) {
  public Quantity {
    if (value < 1 || value > 1000) {
      throw new IllegalArgumentException("qty: 1..1000");
    }
  }
}
