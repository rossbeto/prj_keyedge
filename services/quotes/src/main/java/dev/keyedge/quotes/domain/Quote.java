package dev.keyedge.quotes.domain;

import java.util.List;

/** A priced quote. {@code warnings} say what is degraded (e.g. price not converted). */
public record Quote(
    String sku,
    int quantity,
    long unitPriceCents,
    long totalCents,
    String currency,
    int available,
    List<Warning> warnings) {
  public Quote {
    warnings = List.copyOf(warnings);
  }

  /** What a degraded answer lacks. */
  public enum Warning {
    /** fx did not answer: the price is in the upstream's currency, not the requested one. */
    FX_UNAVAILABLE,
    /** Fewer units available than requested. */
    LIMITED_STOCK
  }
}
