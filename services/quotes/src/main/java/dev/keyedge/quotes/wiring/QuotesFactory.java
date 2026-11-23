package dev.keyedge.quotes.wiring;

import dev.keyedge.awstarget.AwsTargetConfigurer;
import dev.keyedge.awstarget.AwsTargets;
import dev.keyedge.fanout.ContextCarrier;
import dev.keyedge.fanout.FanOut;
import dev.keyedge.fanout.FanOutProvider;
import dev.keyedge.quotes.adapters.auth.HttpTokenEndpoint;
import dev.keyedge.quotes.adapters.auth.SingleFlightTokenProvider;
import dev.keyedge.quotes.adapters.http.HttpUpstreams;
import dev.keyedge.quotes.adapters.http.JsonHttp;
import dev.keyedge.quotes.adapters.reactor.ReactorQuoteFlow;
import dev.keyedge.quotes.adapters.resilience.Guard;
import dev.keyedge.quotes.adapters.resilience.GuardedUpstreams;
import dev.keyedge.quotes.api.PrincipalFilter;
import dev.keyedge.quotes.api.QuoteHttp;
import dev.keyedge.quotes.app.GetQuote;
import dev.keyedge.quotes.app.ReserveStock;
import dev.keyedge.quotes.domain.CurrencyCode;
import dev.keyedge.quotes.domain.RetryBudget;
import dev.keyedge.quotes.domain.RetryPolicy;
import dev.keyedge.quotes.ports.ReactiveQuotes;
import dev.keyedge.quotes.ports.RequestContext;
import dev.keyedge.quotes.ports.TokenProvider;
import dev.keyedge.quotes.ports.UpstreamEndpoints;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.random.RandomGenerator;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/** Composition root of quotes. */
@Factory
public class QuotesFactory {
  @Singleton
  @Primary
  InstantSource clock() {
    return InstantSource.system();
  }

  @Singleton
  PrincipalFilter.PrincipalKey principalKey(
      JsonMapper json,
      @Value("${keyedge.quotes.principal-secret:keyedge/principal}") String secretId) {
    try (var secrets =
        AwsTargetConfigurer.configure(SecretsManagerClient.builder(), AwsTargets.fromEnvironment())
            .build()) {
      Map<String, Object> doc =
          json.readValue(
              secrets.getSecretValue(b -> b.secretId(secretId)).secretString(),
              Argument.mapOf(String.class, Object.class));
      if (doc == null || !(doc.get("key") instanceof String b64)) {
        throw new IllegalStateException("secret " + secretId + " has no 'key'");
      }
      return new PrincipalFilter.PrincipalKey(Base64.getDecoder().decode(b64));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---- the quote use case (Phase 009) ----------------------------------------------------------

  /** Upstream calls' protection, shared by the blocking guards and the Reactor twin. */
  record Protections(
      ReactorQuoteFlow.Protection pricing,
      ReactorQuoteFlow.Protection stock,
      ReactorQuoteFlow.Protection fx) {}

  @Singleton
  QuoteHttp.Settings quoteSettings(QuotesConfig cfg) {
    return new QuoteHttp.Settings(cfg.budget(), System::nanoTime);
  }

  @Singleton
  @Named("upstreams")
  @Bean(preDestroy = "close")
  HttpClient upstreamHttp(@Named(TaskExecutors.VIRTUAL) ExecutorService vt) {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(1))
        .executor(vt)
        .build();
  }

  @Singleton
  TokenProvider tokens(
      @Named("upstreams") HttpClient http,
      QuotesConfig cfg,
      JsonMapper json,
      @Named(TaskExecutors.VIRTUAL) ExecutorService vt) {
    return new SingleFlightTokenProvider(
        new HttpTokenEndpoint(
            http,
            URI.create(cfg.tokenUrl()),
            cfg.clientId(),
            cfg.clientSecret(),
            Duration.ofSeconds(2),
            json),
        vt,
        System::nanoTime,
        cfg.tokenRefreshAt());
  }

  @Singleton
  UpstreamEndpoints endpoints(QuotesConfig cfg) {
    return new UpstreamEndpoints(
        URI.create(cfg.pricingUrl()),
        cfg.pricingTimeout(),
        URI.create(cfg.stockUrl()),
        cfg.stockTimeout(),
        URI.create(cfg.fxUrl()),
        cfg.fxTimeout());
  }

  private static ReactorQuoteFlow.Protection protection(
      String name, int bulkhead, int attempts, QuotesConfig cfg) {
    return new ReactorQuoteFlow.Protection(
        Bulkhead.of(
            name,
            BulkheadConfig.custom()
                .maxConcurrentCalls(bulkhead)
                .maxWaitDuration(Duration.ZERO)
                .build()),
        CircuitBreaker.of(
            name,
            CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cfg.breakerWindow())
                .minimumNumberOfCalls(cfg.breakerMinimumCalls())
                .failureRateThreshold(cfg.breakerFailureRate())
                .waitDurationInOpenState(cfg.breakerOpenFor())
                .recordException(
                    e -> !(e instanceof dev.keyedge.fanout.CallException c && c.clientError()))
                .build()),
        attempts <= 1
            ? RetryPolicy.none()
            : new RetryPolicy(attempts, cfg.retryBase(), cfg.retryCap(), cfg.jitter()),
        new RetryBudget(cfg.retryBudgetRatio(), cfg.retryBudgetAllowance()));
  }

  @Singleton
  Protections protections(QuotesConfig cfg) {
    return new Protections(
        protection("pricing", cfg.pricingBulkhead(), cfg.pricingAttempts(), cfg),
        protection("stock", cfg.stockBulkhead(), cfg.stockAttempts(), cfg),
        protection("fx", cfg.fxBulkhead(), cfg.fxAttempts(), cfg));
  }

  private static Guard guard(String name, ReactorQuoteFlow.Protection p) {
    return new Guard(
        name,
        p.bulkhead(),
        p.breaker(),
        p.policy(),
        p.budget(),
        System::nanoTime,
        RandomGenerator.getDefault(),
        d -> Thread.sleep(d));
  }

  @Singleton
  GuardedUpstreams upstreams(
      @Named("upstreams") HttpClient http,
      JsonMapper json,
      UpstreamEndpoints endpoints,
      TokenProvider tokens,
      Protections p) {
    var raw = new HttpUpstreams(new JsonHttp(http, json), endpoints, tokens, System::nanoTime);
    return new GuardedUpstreams(
        raw,
        raw,
        raw,
        guard("pricing", p.pricing()),
        guard("stock", p.stock()),
        guard("fx", p.fx()));
  }

  /** Carries {@link RequestContext#CURRENT} into executor tasks (the futures adapter needs it). */
  static ContextCarrier requestContextCarrier() {
    return () -> {
      RequestContext ctx = RequestContext.CURRENT.isBound() ? RequestContext.CURRENT.get() : null;
      return new ContextCarrier.Binder() {
        @Override
        public <T> Callable<T> wrap(Callable<T> task) {
          return ctx == null
              ? task
              : () -> ScopedValue.where(RequestContext.CURRENT, ctx).call(task::call);
        }
      };
    };
  }

  @Singleton
  FanOut fanOut(QuotesConfig cfg, @Named(TaskExecutors.VIRTUAL) ExecutorService vt) {
    return FanOutProvider.load(
        cfg.fanout().name().toLowerCase(java.util.Locale.ROOT),
        new FanOutProvider.Resources(System::nanoTime, vt, requestContextCarrier()));
  }

  @Singleton
  GetQuote getQuote(FanOut fanOut, GuardedUpstreams upstreams, QuotesConfig cfg) {
    return new GetQuote(
        fanOut, upstreams, upstreams, upstreams, new CurrencyCode(cfg.baseCurrency()));
  }

  @Singleton
  ReserveStock reserveStock(GuardedUpstreams upstreams) {
    return new ReserveStock(upstreams);
  }

  @Singleton
  ReactiveQuotes reactiveQuotes(
      @Named("upstreams") HttpClient http,
      JsonMapper json,
      UpstreamEndpoints endpoints,
      TokenProvider tokens,
      Protections p,
      QuotesConfig cfg) {
    return new ReactorQuoteFlow(
        http,
        json,
        endpoints,
        tokens,
        new ReactorQuoteFlow.Protections(p.pricing(), p.stock(), p.fx()),
        cfg.baseCurrency(),
        System::nanoTime,
        RandomGenerator.getDefault());
  }
}
