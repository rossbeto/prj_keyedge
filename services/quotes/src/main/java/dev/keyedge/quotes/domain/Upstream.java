package dev.keyedge.quotes.domain;

import java.util.Map;

/**
 * What the three upstreams answer, parsed from their JSON bodies (shared by the blocking and the
 * reactive adapters). A body without the expected fields is a contract failure.
 */
public final class Upstream {
  private Upstream() {}

  /** Unit price in minor units. */
  public record Price(long unitCents, CurrencyCode currency) {}

  /** Units available now. */
  public record Stock(int available) {}

  /** Conversion rate {@code from → to}. */
  public record FxRate(CurrencyCode from, CurrencyCode to, double rate) {}

  /** A reservation id. */
  public record Reservation(String id) {}

  public static Price price(Map<String, Object> body) {
    return new Price(
        number(body, "unitPriceCents").longValue(), new CurrencyCode(text(body, "currency")));
  }

  public static Stock stock(Map<String, Object> body) {
    return new Stock(number(body, "available").intValue());
  }

  public static FxRate fx(Map<String, Object> body) {
    return new FxRate(
        new CurrencyCode(text(body, "from")),
        new CurrencyCode(text(body, "to")),
        number(body, "rate").doubleValue());
  }

  public static Reservation reservation(Map<String, Object> body) {
    return new Reservation(text(body, "reservation"));
  }

  private static Number number(Map<String, Object> body, String field) {
    if (body.get(field) instanceof Number n) {
      return n;
    }
    throw new IllegalArgumentException("upstream body without number '" + field + "'");
  }

  private static String text(Map<String, Object> body, String field) {
    if (body.get(field) instanceof String s) {
      return s;
    }
    throw new IllegalArgumentException("upstream body without text '" + field + "'");
  }
}
