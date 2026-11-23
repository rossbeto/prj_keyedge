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
import dev.keyedge.quotes.adapters.observe.OtelUpstreamTelemetry;
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
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import io.micronaut.scheduling.TaskExecutors;
import io.opentelemetry.api.OpenTelemetry;
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
      @Value("${keyedge.quotes.principal-secret:keyedge/principal}") String configured,
      @Value("${keyedge.contract.path:}") String contractPath) {
    String secretId = configured;
    if (!contractPath
        .isBlank()) { // the name from the SSM contract written by the CDK stacks (D-34)
      try (var ssm =
          AwsTargetConfigurer.configure(
                  software.amazon.awssdk.services.ssm.SsmClient.builder(),
                  AwsTargets.fromEnvironment())
              .build()) {
        secretId =
            dev.keyedge.awstarget.ServiceContract.load(ssm, contractPath)
                .name(dev.keyedge.awstarget.ServiceContract.PRINCIPAL_SECRET, configured);
      }
    }
    String id = secretId;
    try (var secrets =
        AwsTargetConfigurer.configure(SecretsManagerClient.builder(), AwsTargets.fromEnvironment())
            .build()) {
      Map<String, Object> doc =
          json.readValue(
              secrets.getSecretValue(b -> b.secretId(id)).secretString(),
              Argument.mapOf(String.class, Object.class));
      if (doc == null || !(doc.get("key") instanceof String b64)) {
        throw new IllegalStateException("secret " + id + " has no 'key'");
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
      Protections p,
      OpenTelemetry otel,
      jakarta.inject.Provider<MeterRegistry> registry,
      QuotesConfig cfg) {
    var telemetry =
        new OtelUpstreamTelemetry(
            otel, registry::get, cfg.fanout().name().toLowerCase(java.util.Locale.ROOT));
    var raw =
        new HttpUpstreams(new JsonHttp(http, json, telemetry), endpoints, tokens, System::nanoTime);
    return new GuardedUpstreams(
        raw,
        raw,
        raw,
        guard("pricing", p.pricing()),
        guard("stock", p.stock()),
        guard("fx", p.fx()));
  }

  /**
   * Carries the request into fan-out tasks: {@link RequestContext#CURRENT} for executor tasks
   * (forks inherit scoped values anyway) and OpenTelemetry's ThreadLocal-based context, which
   * neither executor tasks nor structured forks inherit (Phase 011).
   */
  static ContextCarrier requestContextCarrier() {
    return () -> {
      RequestContext ctx = RequestContext.CURRENT.isBound() ? RequestContext.CURRENT.get() : null;
      io.opentelemetry.context.Context otel = io.opentelemetry.context.Context.current();
      return new ContextCarrier.Binder() {
        @Override
        public <T> Callable<T> wrap(Callable<T> task) {
          Callable<T> traced = otel.wrap(task);
          return ctx == null
              ? traced
              : () -> ScopedValue.where(RequestContext.CURRENT, ctx).call(traced::call);
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
      QuotesConfig cfg,
      OpenTelemetry otel,
      jakarta.inject.Provider<MeterRegistry> registry) {
    return new ReactorQuoteFlow(
        http,
        json,
        endpoints,
        tokens,
        new ReactorQuoteFlow.Protections(p.pricing(), p.stock(), p.fx()),
        cfg.baseCurrency(),
        new ReactorQuoteFlow.Runtime(
            System::nanoTime,
            RandomGenerator.getDefault(),
            new OtelUpstreamTelemetry(otel, registry::get, "reactor")));
  }

  /** Retries granted per upstream (each retry is also an extra call in keyedge_upstream_calls). */
  @Singleton
  MeterBinder upstreamMeters(Protections p) {
    return registry -> {
      for (var e :
          java.util.Map.of("pricing", p.pricing(), "stock", p.stock(), "fx", p.fx()).entrySet()) {
        FunctionCounter.builder(
                "keyedge.upstream.retries", e.getValue().budget(), RetryBudget::retries)
            .tag("upstream", e.getKey())
            .register(registry);
      }
    };
  }
}
