package dev.keyedge.quotes.api;

import dev.keyedge.quotes.app.GetQuote;
import dev.keyedge.quotes.app.ReserveStock;
import dev.keyedge.quotes.domain.Quantity;
import dev.keyedge.quotes.domain.Sku;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

/**
 * The blocking path on virtual threads: {@code GET /v1/quotes/{sku}?qty=&currency=} and the
 * non-idempotent {@code POST /v1/quotes/{sku}/reserve?qty=}.
 */
@Controller("/v1/quotes")
@ExecuteOn(TaskExecutors.VIRTUAL)
public class QuotesController {
  private final GetQuote getQuote;
  private final ReserveStock reserveStock;
  private final QuoteHttp.Settings settings;

  QuotesController(GetQuote getQuote, ReserveStock reserveStock, QuoteHttp.Settings settings) {
    this.getQuote = getQuote;
    this.reserveStock = reserveStock;
    this.settings = settings;
  }

  @Get("/{sku}")
  public HttpResponse<?> quote(
      HttpRequest<?> request,
      @PathVariable String sku,
      @QueryValue(defaultValue = "1") String qty,
      @QueryValue(defaultValue = "EUR") String currency) {
    try {
      var q = QuoteHttp.parse(sku, qty, currency);
      var ctx = QuoteHttp.context(request, settings);
      return QuoteHttp.respond(getQuote.handle(q, ctx), ctx);
    } catch (IllegalArgumentException e) {
      return QuoteHttp.invalid(e);
    }
  }

  @Post("/{sku}/reserve")
  public HttpResponse<?> reserve(
      HttpRequest<?> request,
      @PathVariable String sku,
      @QueryValue(defaultValue = "1") String qty) {
    try {
      var q = QuoteHttp.parse(sku, qty, "EUR");
      var ctx = QuoteHttp.context(request, settings);
      return QuoteHttp.reserved(
          reserveStock.handle(new Sku(sku), new Quantity(q.quantity().value()), ctx));
    } catch (IllegalArgumentException e) {
      return QuoteHttp.invalid(e);
    }
  }
}
