package dev.keyedge.quotes.adapters.http;

import dev.keyedge.fanout.CallException;
import dev.keyedge.fanout.Deadline;
import dev.keyedge.quotes.domain.CurrencyCode;
import dev.keyedge.quotes.domain.Quantity;
import dev.keyedge.quotes.domain.Sku;
import dev.keyedge.quotes.domain.Upstream;
import dev.keyedge.quotes.ports.FxClient;
import dev.keyedge.quotes.ports.PricingClient;
import dev.keyedge.quotes.ports.StockClient;
import dev.keyedge.quotes.ports.TokenProvider;
import dev.keyedge.quotes.ports.UpstreamEndpoints;
import java.net.URI;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The three upstreams over HTTP. Each call's timeout = min(its own, what is left of the deadline).
 * pricing: a 401 means the token was revoked or expired early → invalidate it and try once more
 * with a fresh one (the only retry in this layer; idempotent GET).
 */
public final class HttpUpstreams implements PricingClient, StockClient, FxClient {
  private final JsonHttp http;
  private final UpstreamEndpoints endpoints;
  private final TokenProvider tokens;
  private final LongSupplier nanos;

  public HttpUpstreams(
      JsonHttp http, UpstreamEndpoints endpoints, TokenProvider tokens, LongSupplier nanos) {
    this.http = http;
    this.endpoints = endpoints;
    this.tokens = tokens;
    this.nanos = nanos;
  }

  @Override
  public Upstream.Price price(Sku sku, Deadline d) throws CallException, InterruptedException {
    URI uri = endpoints.pricing().resolve("/pricing/" + sku.value());
    String token = tokens.token(d);
    try {
      return Upstream.price(
          http.get("pricing", uri, d.cap(endpoints.pricingTimeout(), nanos), bearer(token)));
    } catch (CallException e) {
      if (e.status().filter(s -> s == 401).isEmpty()) {
        throw e;
      }
      tokens.invalidate(token);
      return Upstream.price(
          http.get(
              "pricing", uri, d.cap(endpoints.pricingTimeout(), nanos), bearer(tokens.token(d))));
    }
  }

  @Override
  public Upstream.Stock stock(Sku sku, Deadline d) throws CallException, InterruptedException {
    return Upstream.stock(
        http.get(
            "stock",
            endpoints.stock().resolve("/stock/" + sku.value()),
            d.cap(endpoints.stockTimeout(), nanos),
            Map.of()));
  }

  @Override
  public Upstream.Reservation reserve(Sku sku, Quantity qty, String requestId, Deadline d)
      throws CallException, InterruptedException {
    return Upstream.reservation(
        http.post(
            "reserve",
            endpoints.pricing().resolve("/pricing/reservations"),
            d.cap(endpoints.stockTimeout(), nanos),
            Map.of("X-Request-Id", requestId),
            Map.of("sku", sku.value(), "qty", qty.value())));
  }

  @Override
  public Upstream.FxRate rate(CurrencyCode from, CurrencyCode to, Deadline d)
      throws CallException, InterruptedException {
    return Upstream.fx(
        http.get(
            "fx",
            endpoints.fx().resolve("/fx/" + from.value() + "/" + to.value()),
            d.cap(endpoints.fxTimeout(), nanos),
            Map.of()));
  }

  private static Map<String, String> bearer(String token) {
    return Map.of("Authorization", "Bearer " + token);
  }
}
