package dev.keyedge.quotes.adapters.reactor;

import dev.keyedge.fanout.CallException;
import dev.keyedge.fanout.CallResult;
import dev.keyedge.quotes.domain.QuoteAssembler;
import dev.keyedge.quotes.domain.QuoteOutcome;
import dev.keyedge.quotes.domain.QuoteRequest;
import dev.keyedge.quotes.domain.RetryBudget;
import dev.keyedge.quotes.domain.RetryPolicy;
import dev.keyedge.quotes.domain.Upstream;
import dev.keyedge.quotes.ports.ReactiveQuotes;
import dev.keyedge.quotes.ports.RequestContext;
import dev.keyedge.quotes.ports.TokenProvider;
import dev.keyedge.quotes.ports.UpstreamEndpoints;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * The Reactor twin of GetQuote (D-11): the same three calls as {@code Mono}s over the JDK client's
 * {@code sendAsync} (no thread per call), zipped. The ordering rules are the blocking path's:
 * attempt → breaker → retry (idempotent only, budget, deadline) → bulkhead outermost. A required
 * failure errors the zip, which cancels its siblings; the optional fx call is turned into a value
 * <b>before</b> the zip, otherwise its failure would cancel the whole quote (the classic Mono.zip
 * bug, covered by the parity test).
 */
public final class ReactorQuoteFlow implements ReactiveQuotes {
  private final HttpClient http;
  private final JsonMapper json;
  private final UpstreamEndpoints endpoints;
  private final TokenProvider tokens;
  private final Protections protections;
  private final LongSupplier nanos;
  private final RandomGenerator random;
  private final String base;

  /** One upstream's shared protection (same instances as the blocking path's guards). */
  public record Protection(
      Bulkhead bulkhead, CircuitBreaker breaker, RetryPolicy policy, RetryBudget budget) {}

  /** Per upstream. */
  public record Protections(Protection pricing, Protection stock, Protection fx) {}

  private static final class Failure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final String upstream;

    Failure(String upstream, Throwable cause) {
      super(upstream, cause, false, false);
      this.upstream = upstream;
    }
  }

  public ReactorQuoteFlow(
      HttpClient http,
      JsonMapper json,
      UpstreamEndpoints endpoints,
      TokenProvider tokens,
      Protections protections,
      String baseCurrency,
      LongSupplier nanos,
      RandomGenerator random) {
    this.http = http;
    this.json = json;
    this.endpoints = endpoints;
    this.tokens = tokens;
    this.protections = protections;
    this.base = baseCurrency;
    this.nanos = nanos;
    this.random = random;
  }

  @Override
  @SuppressWarnings(
      "NullAway") // Mono.toFuture() is typed @Nullable (empty Mono); zip().map() is never empty
  public CompletionStage<QuoteOutcome> quote(QuoteRequest q, RequestContext ctx) {
    String sku = q.sku().value();
    Mono<Upstream.Price> price =
        guarded(
                "pricing",
                pricing(sku, ctx),
                protections.pricing(),
                true,
                ctx,
                endpoints.pricingTimeout())
            .map(Upstream::price);
    Mono<Upstream.Stock> stock =
        guarded(
                "stock",
                get("stock", endpoints.stock().resolve("/stock/" + sku), Map.of(), ctx),
                protections.stock(),
                true,
                ctx,
                endpoints.stockTimeout())
            .map(Upstream::stock);
    Mono<CallResult<Upstream.FxRate>> fx =
        guarded(
                "fx",
                get(
                    "fx",
                    endpoints.fx().resolve("/fx/" + base + "/" + q.currency().value()),
                    Map.of(),
                    ctx),
                protections.fx(),
                true,
                ctx,
                endpoints.fxTimeout())
            .map(Upstream::fx)
            .<CallResult<Upstream.FxRate>>map(CallResult.Ok::new)
            .onErrorResume(
                e -> Mono.just(new CallResult.Failed<>("fx", java.util.Optional.empty())));
    return Mono.zip(
            price.onErrorMap(e -> new Failure("pricing", e)),
            stock.onErrorMap(e -> new Failure("stock", e)),
            fx)
        .map(t ->
            QuoteAssembler.assemble(
                q.sku(),
                q.quantity(),
                q.currency(),
                new CallResult.Ok<>(t.getT1()),
                new CallResult.Ok<>(t.getT2()),
                t.getT3()))
        .onErrorResume(Failure.class, f -> Mono.just(unavailable(f)))
        .toFuture();
  }

  private static QuoteOutcome unavailable(Failure f) {
    Throwable cause = f.getCause();
    boolean timedOut =
        cause instanceof TimeoutException || cause instanceof CallException c && c.timedOut();
    return new QuoteOutcome.Unavailable(
        f.upstream, timedOut ? QuoteOutcome.Reason.TIMED_OUT : QuoteOutcome.Reason.FAILED);
  }

  /** pricing with the shared token; a 401 invalidates it and tries once more with a fresh one. */
  private Mono<Map<String, Object>> pricing(String sku, RequestContext ctx) {
    URI uri = endpoints.pricing().resolve("/pricing/" + sku);
    return Mono.fromFuture(tokens::tokenAsync)
        .flatMap(token ->
            get("pricing", uri, Map.of("Authorization", "Bearer " + token), ctx)
                .onErrorResume(
                    e ->
                        e instanceof CallException c
                            && c.status().filter(s -> s == 401).isPresent(),
                    e -> {
                      tokens.invalidate(token);
                      return Mono.fromFuture(tokens::tokenAsync)
                          .flatMap(fresh ->
                              get("pricing", uri, Map.of("Authorization", "Bearer " + fresh), ctx));
                    }));
  }

  private <T> Mono<T> guarded(
      String upstream,
      Mono<T> attempt,
      Protection p,
      boolean idempotent,
      RequestContext ctx,
      Duration timeout) {
    var previous = new AtomicReference<>(p.policy().base());
    Retry retry =
        Retry.from(signals ->
            signals.concatMap(s -> {
              Throwable e = s.failure();
              int attemptNo = (int) s.totalRetries() + 1;
              if (!idempotent
                  || !(e instanceof CallException c)
                  || c.clientError()
                  || attemptNo >= p.policy().maxAttempts()) {
                return Mono.error(e);
              }
              Duration wait = p.policy().waitFor(attemptNo, previous.get(), c.retryAfter(), random);
              if (wait.compareTo(ctx.deadline().remaining(nanos)) >= 0 || !p.budget().tryRetry()) {
                return Mono.error(e);
              }
              previous.set(wait);
              return Mono.delay(wait);
            }));
    // per attempt, like the blocking path: each attempt gets min(timeout, what is left); the
    // request deadline bounds the retries
    Mono<T> timed =
        Mono.defer(() -> attempt.timeout(ctx.deadline().cap(timeout, nanos)))
            .onErrorMap(TimeoutException.class, e -> CallException.timeout(upstream));
    return Mono.defer(
            () -> {
              p.budget().onCall();
              return timed
                  .transformDeferred(CircuitBreakerOperator.of(p.breaker()))
                  .retryWhen(retry);
            })
        .transformDeferred(BulkheadOperator.of(p.bulkhead()))
        .timeout(ctx.deadline().remaining(nanos));
  }

  private Mono<Map<String, Object>> get(
      String upstream, URI uri, Map<String, String> headers, RequestContext ctx) {
    return Mono.defer(() -> {
      var b =
          HttpRequest.newBuilder(uri)
              .timeout(nonZero(ctx.deadline().remaining(nanos)))
              .header("X-Request-Id", ctx.requestId());
      headers.forEach(b::header);
      HttpRequest request = b.GET().build();
      return Mono.<HttpResponse<byte[]>>create(
              sink -> {
                var future = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
                sink.onCancel(
                    () -> future.cancel(true)); // aborts the exchange: frees the connection
                var unused =
                    future.whenComplete((r, e) -> {
                      if (e == null) {
                        sink.success(r);
                      } else if (!future.isCancelled()) { // our own cancellation is not an error
                        Throwable cause = e.getCause();
                        sink.error(
                            e instanceof java.util.concurrent.CompletionException && cause != null
                                ? cause
                                : e);
                      }
                    });
              })
          .onErrorMap(HttpTimeoutException.class, e -> CallException.timeout(upstream))
          .onErrorMap(
              IOException.class, e -> CallException.io(upstream, e.getClass().getSimpleName()))
          .flatMap(r -> body(upstream, r));
    });
  }

  private Mono<Map<String, Object>> body(String upstream, HttpResponse<byte[]> r) {
    if (r.statusCode() / 100 != 2) {
      Duration retryAfter =
          r.headers().firstValue("Retry-After").flatMap(ReactorQuoteFlow::seconds).orElse(null);
      return Mono.error(CallException.status(upstream, r.statusCode(), retryAfter));
    }
    try {
      Map<String, Object> body =
          json.readValue(r.body(), Argument.mapOf(String.class, Object.class));
      return body == null ? Mono.error(CallException.io(upstream, "empty body")) : Mono.just(body);
    } catch (IOException e) {
      return Mono.error(CallException.io(upstream, "unreadable body"));
    }
  }

  private static Duration nonZero(Duration d) {
    return d.isZero() ? Duration.ofMillis(1) : d;
  }

  private static java.util.Optional<Duration> seconds(String header) {
    try {
      return java.util.Optional.of(Duration.ofSeconds(Long.parseLong(header.trim())));
    } catch (NumberFormatException e) {
      return java.util.Optional.empty();
    }
  }
}
