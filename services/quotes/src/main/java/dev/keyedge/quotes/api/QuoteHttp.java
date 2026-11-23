package dev.keyedge.quotes.api;

import dev.keyedge.fanout.CallResult;
import dev.keyedge.fanout.Deadline;
import dev.keyedge.keyformat.PrincipalHeader;
import dev.keyedge.problem.Problem;
import dev.keyedge.problem.ProblemType;
import dev.keyedge.problem.micronaut.ProblemResponses;
import dev.keyedge.quotes.domain.CurrencyCode;
import dev.keyedge.quotes.domain.Quantity;
import dev.keyedge.quotes.domain.QuoteOutcome;
import dev.keyedge.quotes.domain.QuoteRequest;
import dev.keyedge.quotes.domain.Sku;
import dev.keyedge.quotes.domain.Upstream;
import dev.keyedge.quotes.ports.RequestContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * HTTP mapping shared by the blocking controller and its Reactor twin, so both answer byte for byte
 * the same (parity test): request parsing, request context, outcome → response.
 */
public final class QuoteHttp {
  private QuoteHttp() {}

  /** The request budget and the monotonic clock deadlines are measured on (from wiring). */
  public record Settings(Duration budget, LongSupplier nanos) {}

  static QuoteRequest parse(String sku, String qty, String currency) {
    try {
      return new QuoteRequest(
          new Sku(sku), new Quantity(Integer.parseInt(qty)), new CurrencyCode(currency));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("qty: 1..1000");
    }
  }

  static RequestContext context(HttpRequest<?> request, Settings settings) {
    var principal =
        request
            .getAttribute(PrincipalFilter.ATTRIBUTE, PrincipalHeader.Principal.class)
            .orElseThrow();
    String requestId =
        request
            .getHeaders()
            .get("X-Request-Id", String.class)
            .filter(id -> id.length() <= 64)
            .orElseGet(() -> UUID.randomUUID().toString());
    return new RequestContext(
        requestId, principal.tenant().value(), Deadline.after(settings.budget(), settings.nanos()));
  }

  static MutableHttpResponse<?> respond(QuoteOutcome outcome, RequestContext ctx) {
    return switch (outcome) {
      case QuoteOutcome.Priced(var q) -> {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", q.sku());
        body.put("quantity", q.quantity());
        body.put("unitPriceCents", q.unitPriceCents());
        body.put("totalCents", q.totalCents());
        body.put("currency", q.currency());
        body.put("available", q.available());
        body.put("warnings", q.warnings().stream().map(Enum::name).toList());
        body.put("tenant", ctx.tenant());
        yield HttpResponse.ok(body);
      }
      case QuoteOutcome.Unavailable(var upstream, var reason) ->
          ProblemResponses.of(
              Problem.of(
                      ProblemType.UPSTREAM_UNAVAILABLE,
                      upstream + " " + reason.name().toLowerCase(Locale.ROOT).replace('_', ' '))
                  .with(Problem.RETRY_AFTER_SECONDS, 1L));
    };
  }

  static MutableHttpResponse<?> reserved(CallResult<Upstream.Reservation> r) {
    return switch (r) {
      case CallResult.Ok<Upstream.Reservation>(var reservation) ->
          HttpResponse.created(Map.of("reservation", reservation.id()));
      case CallResult.Failed<?> f ->
          ProblemResponses.of(Problem.of(ProblemType.UPSTREAM_UNAVAILABLE, "reserve failed"));
      case CallResult.TimedOut<?> t ->
          ProblemResponses.of(Problem.of(ProblemType.UPSTREAM_UNAVAILABLE, "reserve timed out"));
      case CallResult.Skipped<?> s ->
          ProblemResponses.of(Problem.of(ProblemType.UPSTREAM_UNAVAILABLE, "reserve cancelled"));
    };
  }

  static MutableHttpResponse<?> invalid(IllegalArgumentException e) {
    return ProblemResponses.of(Problem.of(ProblemType.VALIDATION, e.getMessage()));
  }
}
