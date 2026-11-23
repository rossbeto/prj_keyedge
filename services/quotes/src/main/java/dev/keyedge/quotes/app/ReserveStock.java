package dev.keyedge.quotes.app;

import dev.keyedge.fanout.Call;
import dev.keyedge.fanout.CallResult;
import dev.keyedge.quotes.domain.Quantity;
import dev.keyedge.quotes.domain.Sku;
import dev.keyedge.quotes.domain.Upstream;
import dev.keyedge.quotes.ports.RequestContext;
import dev.keyedge.quotes.ports.StockClient;

/**
 * Reserve units: a non-idempotent POST. The request id travels to the upstream so duplicates are
 * visible there; the resilience layer never retries it (D-20).
 */
public final class ReserveStock {
  private final StockClient stock;

  public ReserveStock(StockClient stock) {
    this.stock = stock;
  }

  public CallResult<Upstream.Reservation> handle(Sku sku, Quantity qty, RequestContext ctx) {
    return ScopedValue.where(RequestContext.CURRENT, ctx)
        .call(() ->
            CallResult.of(
                Call.required(
                    "reserve", () -> stock.reserve(sku, qty, ctx.requestId(), ctx.deadline()))));
  }
}
