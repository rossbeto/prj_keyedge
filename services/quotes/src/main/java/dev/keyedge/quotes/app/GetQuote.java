package dev.keyedge.quotes.app;

import dev.keyedge.fanout.Call;
import dev.keyedge.fanout.FanOut;
import dev.keyedge.fanout.Tri;
import dev.keyedge.quotes.domain.CurrencyCode;
import dev.keyedge.quotes.domain.QuoteAssembler;
import dev.keyedge.quotes.domain.QuoteOutcome;
import dev.keyedge.quotes.domain.QuoteRequest;
import dev.keyedge.quotes.domain.Upstream;
import dev.keyedge.quotes.ports.FxClient;
import dev.keyedge.quotes.ports.PricingClient;
import dev.keyedge.quotes.ports.RequestContext;
import dev.keyedge.quotes.ports.StockClient;

/**
 * The quote use case: pricing (required), stock (required) and fx (optional) concurrently under the
 * request's deadline, then the pure assembler. Which concurrency model runs the calls is the {@link
 * FanOut} adapter's business (structured or futures, chosen in wiring).
 */
public final class GetQuote {
  private final FanOut fanOut;
  private final PricingClient pricing;
  private final StockClient stock;
  private final FxClient fx;
  private final CurrencyCode base;

  public GetQuote(
      FanOut fanOut, PricingClient pricing, StockClient stock, FxClient fx, CurrencyCode base) {
    this.fanOut = fanOut;
    this.pricing = pricing;
    this.stock = stock;
    this.fx = fx;
    this.base = base;
  }

  public QuoteOutcome handle(QuoteRequest q, RequestContext ctx) {
    var d = ctx.deadline();
    Tri<Upstream.Price, Upstream.Stock, Upstream.FxRate> r =
        ScopedValue.where(RequestContext.CURRENT, ctx)
            .call(() ->
                fanOut.all(
                    Call.required("pricing", () -> pricing.price(q.sku(), d)),
                    Call.required("stock", () -> stock.stock(q.sku(), d)),
                    Call.optional("fx", () -> fx.rate(base, q.currency(), d)),
                    d));
    return QuoteAssembler.assemble(
        q.sku(), q.quantity(), q.currency(), r.first(), r.second(), r.third());
  }
}
