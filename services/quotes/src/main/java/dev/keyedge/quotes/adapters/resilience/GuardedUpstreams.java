package dev.keyedge.quotes.adapters.resilience;

import dev.keyedge.fanout.CallException;
import dev.keyedge.fanout.Deadline;
import dev.keyedge.quotes.domain.CurrencyCode;
import dev.keyedge.quotes.domain.Quantity;
import dev.keyedge.quotes.domain.Sku;
import dev.keyedge.quotes.domain.Upstream;
import dev.keyedge.quotes.ports.FxClient;
import dev.keyedge.quotes.ports.PricingClient;
import dev.keyedge.quotes.ports.StockClient;

/** The upstream ports behind their guards; reservations are the one non-idempotent call. */
public final class GuardedUpstreams implements PricingClient, StockClient, FxClient {
  private final PricingClient pricing;
  private final StockClient stock;
  private final FxClient fx;
  private final Guard pricingGuard;
  private final Guard stockGuard;
  private final Guard fxGuard;

  public GuardedUpstreams(
      PricingClient pricing,
      StockClient stock,
      FxClient fx,
      Guard pricingGuard,
      Guard stockGuard,
      Guard fxGuard) {
    this.pricing = pricing;
    this.stock = stock;
    this.fx = fx;
    this.pricingGuard = pricingGuard;
    this.stockGuard = stockGuard;
    this.fxGuard = fxGuard;
  }

  @Override
  public Upstream.Price price(Sku sku, Deadline d) throws CallException, InterruptedException {
    return pricingGuard.call(true, d, () -> pricing.price(sku, d));
  }

  @Override
  public Upstream.Stock stock(Sku sku, Deadline d) throws CallException, InterruptedException {
    return stockGuard.call(true, d, () -> stock.stock(sku, d));
  }

  @Override
  public Upstream.Reservation reserve(Sku sku, Quantity qty, String requestId, Deadline d)
      throws CallException, InterruptedException {
    return stockGuard.call(false, d, () -> stock.reserve(sku, qty, requestId, d));
  }

  @Override
  public Upstream.FxRate rate(CurrencyCode from, CurrencyCode to, Deadline d)
      throws CallException, InterruptedException {
    return fxGuard.call(true, d, () -> fx.rate(from, to, d));
  }
}
