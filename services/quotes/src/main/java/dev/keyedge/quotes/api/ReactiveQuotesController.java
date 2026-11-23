package dev.keyedge.quotes.api;

import dev.keyedge.quotes.ports.ReactiveQuotes;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import reactor.core.publisher.Mono;

/**
 * The Reactor twin, on the event loop (no {@code @ExecuteOn}): same parameters, same answers as
 * {@link QuotesController#quote}; the parity test holds them to it.
 */
@Controller("/v1/quotes-reactive")
public class ReactiveQuotesController {
  private final ReactiveQuotes quotes;
  private final QuoteHttp.Settings settings;

  ReactiveQuotesController(ReactiveQuotes quotes, QuoteHttp.Settings settings) {
    this.quotes = quotes;
    this.settings = settings;
  }

  @Get("/{sku}")
  public Mono<HttpResponse<?>> quote(
      HttpRequest<?> request,
      @PathVariable String sku,
      @QueryValue(defaultValue = "1") String qty,
      @QueryValue(defaultValue = "EUR") String currency) {
    try {
      var q = QuoteHttp.parse(sku, qty, currency);
      var ctx = QuoteHttp.context(request, settings);
      return Mono.fromCompletionStage(() -> quotes.quote(q, ctx))
          .map(outcome -> QuoteHttp.respond(outcome, ctx));
    } catch (IllegalArgumentException e) {
      return Mono.just(QuoteHttp.invalid(e));
    }
  }
}
