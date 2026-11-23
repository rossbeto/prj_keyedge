package dev.keyedge.quotes.domain;

import dev.keyedge.fanout.CallResult;
import dev.keyedge.quotes.domain.Upstream.FxRate;
import dev.keyedge.quotes.domain.Upstream.Price;
import dev.keyedge.quotes.domain.Upstream.Stock;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure: three upstream results → a quote. pricing and stock are required; fx is optional (without
 * it the price stays in the upstream's currency, with a warning). Never throws.
 */
public final class QuoteAssembler {
  private QuoteAssembler() {}

  public static QuoteOutcome assemble(
      Sku sku,
      Quantity qty,
      CurrencyCode wanted,
      CallResult<Price> price,
      CallResult<Stock> stock,
      CallResult<FxRate> fx) {
    if (!(price instanceof CallResult.Ok<Price>(Price p))
        || !(stock instanceof CallResult.Ok<Stock>(Stock s))) {
      // report the upstream that actually failed, not the sibling it got cancelled
      boolean blamePricing =
          !price.ok() && !(price instanceof CallResult.Skipped<?> && !stock.ok());
      return blamePricing
          ? new QuoteOutcome.Unavailable("pricing", reason(price))
          : new QuoteOutcome.Unavailable("stock", reason(stock));
    }
    List<Quote.Warning> warnings = new ArrayList<>();
    long unit = p.unitCents();
    CurrencyCode currency = p.currency();
    if (!wanted.equals(p.currency())) {
      if (fx instanceof CallResult.Ok<FxRate>(FxRate r)
          && r.from().equals(p.currency())
          && r.to().equals(wanted)) {
        unit = Math.round(p.unitCents() * r.rate());
        currency = wanted;
      } else {
        warnings.add(Quote.Warning.FX_UNAVAILABLE);
      }
    }
    if (s.available() < qty.value()) {
      warnings.add(Quote.Warning.LIMITED_STOCK);
    }
    return new QuoteOutcome.Priced(
        new Quote(
            sku.value(),
            qty.value(),
            unit,
            Math.multiplyExact(unit, qty.value()),
            currency.value(),
            s.available(),
            warnings));
  }

  private static QuoteOutcome.Reason reason(CallResult<?> r) {
    return switch (r) {
      case CallResult.TimedOut<?> t -> QuoteOutcome.Reason.TIMED_OUT;
      case CallResult.Skipped<?> s -> QuoteOutcome.Reason.CANCELLED;
      case CallResult.Failed<?> f -> QuoteOutcome.Reason.FAILED;
      case CallResult.Ok<?> o -> throw new IllegalStateException("not a failure");
    };
  }
}
