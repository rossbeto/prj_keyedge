package dev.keyedge.edge.wiring;

import dev.keyedge.awstarget.AwsTarget;
import dev.keyedge.awstarget.AwsTargetConfigurer;
import dev.keyedge.awstarget.AwsTargets;
import dev.keyedge.awstarget.ServiceContract;
import dev.keyedge.edge.adapters.cache.CaffeineVerdicts;
import dev.keyedge.edge.adapters.http.HttpUsageSink;
import dev.keyedge.edge.adapters.http.HttpVerifyClient;
import dev.keyedge.edge.adapters.observe.JfrEdgeObserver;
import dev.keyedge.edge.adapters.observe.MicrometerEdgeObserver;
import dev.keyedge.edge.adapters.ratelimit.GcraKeyLimits;
import dev.keyedge.edge.adapters.resilience.ResilientVerifyClient;
import dev.keyedge.edge.adapters.secrets.EdgeSecrets;
import dev.keyedge.edge.api.EdgeDrain;
import dev.keyedge.edge.api.EdgeFilter;
import dev.keyedge.edge.api.Gate;
import dev.keyedge.edge.app.AdmitRequest;
import dev.keyedge.edge.app.MeterUsage;
import dev.keyedge.edge.domain.RouteScopes;
import dev.keyedge.edge.ports.AdmissionObserver;
import dev.keyedge.edge.ports.KeyLimits;
import dev.keyedge.edge.ports.SecretBytes;
import dev.keyedge.edge.ports.UsageSink;
import dev.keyedge.edge.ports.Verdicts;
import dev.keyedge.edge.ports.VerifyClient;
import dev.keyedge.edge.ports.VerifyObserver;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.ratelimit.Ticker;
import dev.keyedge.ratelimit.concurrency.AimdLimiter;
import dev.keyedge.ratelimit.concurrency.ConcurrencyLimiter;
import dev.keyedge.ratelimit.concurrency.FixedLimiter;
import dev.keyedge.ratelimit.concurrency.GradientLimiter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.http.client.ProxyHttpClient;
import io.micronaut.json.JsonMapper;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.ExecutorService;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/** Composition root of the sidecar. */
@Factory
public class EdgeFactory {
  @Singleton
  @Primary
  InstantSource clock() {
    return InstantSource.system();
  }

  @Singleton
  Gate gate() {
    return new Gate();
  }

  @Singleton
  AwsTarget awsTarget() {
    return AwsTargets.fromEnvironment();
  }

  /**
   * The SSM contract written by the CDK stacks (D-34); none without KEYEDGE_CONTRACT_PATH (tests).
   */
  @Singleton
  ServiceContract contract(
      AwsTarget target,
      @io.micronaut.context.annotation.Value("${keyedge.contract.path:}") String path) {
    if (path.isBlank()) {
      return ServiceContract.none();
    }
    try (var ssm = AwsTargetConfigurer.configure(SsmClient.builder(), target).build()) {
      var contract = ServiceContract.load(ssm, path);
      org.slf4j.LoggerFactory.getLogger("dev.keyedge.contract")
          .info("names from {}", contract.source());
      return contract;
    }
  }

  @Singleton
  @Bean(preDestroy = "close")
  SecretsManagerClient secrets(AwsTarget target) {
    return AwsTargetConfigurer.configure(SecretsManagerClient.builder(), target).build();
  }

  @Singleton
  EdgeSecrets edgeSecrets(SecretsManagerClient secrets, JsonMapper json) {
    return new EdgeSecrets(secrets, json);
  }

  @Singleton
  @Bean(preDestroy = "close")
  HttpClient httpClient(@Named(TaskExecutors.VIRTUAL) ExecutorService vt) {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).executor(vt).build();
  }

  @Singleton
  VerifyClient verifyClient(
      HttpClient http,
      SecretBytes secrets,
      ServiceContract contract,
      EdgeConfig cfg,
      ResilienceConfig r,
      InstantSource clock,
      JsonMapper json) {
    VerifyClient http1 =
        new HttpVerifyClient(
            http,
            URI.create(cfg.keysUrl()),
            cfg.callerId(),
            secrets.bytes(
                contract.name(ServiceContract.CALLER_SECRET, cfg.callerSecret()), cfg.callerId()),
            clock,
            cfg.verifyTimeout(),
            json);
    if (!r.breakerEnabled()) {
      return http1;
    }
    return new ResilientVerifyClient(
        http1,
        new ResilientVerifyClient.Settings(
            r.slidingWindow(),
            r.minimumCalls(),
            r.failureRateThreshold(),
            r.slowCallDuration(),
            r.slowCallRateThreshold(),
            r.waitInOpen(),
            r.halfOpenCalls(),
            r.bulkheadMaxConcurrent()),
        Ticker.system());
  }

  @Singleton
  ConcurrencyLimiter concurrencyLimiter(ConcurrencyConfig c) {
    return switch (c.mode()) {
      case FIXED -> new FixedLimiter(c.fixedLimit());
      case AIMD ->
          new AimdLimiter(c.initial(), c.min(), c.max(), c.aimdSlow().toNanos(), c.aimdBackoff());
      case GRADIENT ->
          new GradientLimiter(
              c.initial(),
              c.min(),
              c.max(),
              c.gradientWindow(),
              c.smoothing(),
              c.gradientTolerance());
    };
  }

  // ---- observability (Phase 011): composites behind the ports --------------------------------

  @Singleton
  MicrometerEdgeObserver micrometerObserver(jakarta.inject.Provider<MeterRegistry> registry) {
    return new MicrometerEdgeObserver(registry::get);
  }

  @Singleton
  JfrEdgeObserver jfrObserver() {
    return new JfrEdgeObserver();
  }

  @Singleton
  AdmissionObserver admissionObserver(MicrometerEdgeObserver m, JfrEdgeObserver j) {
    return (a, took) -> {
      m.decided(a, took);
      j.decided(a, took);
    };
  }

  @Singleton
  VerifyObserver verifyObserver(MicrometerEdgeObserver m, JfrEdgeObserver j) {
    return (k, n, kind, o) -> {
      m.loaded(k, n, kind, o);
      j.loaded(k, n, kind, o);
    };
  }

  /** Gauges and function counters over state the components already keep. */
  @Singleton
  MeterBinder edgeMeters(
      CaffeineVerdicts verdicts,
      VerifyClient client,
      ConcurrencyLimiter limiter,
      MeterUsage usage,
      EdgeFilter filter) {
    return registry -> {
      FunctionCounter.builder(
              "keyedge.verify.cache.stale.served", verdicts, CaffeineVerdicts::staleServed)
          .description("Answers served from an expired Valid verdict (fail-static)")
          .register(registry);
      FunctionCounter.builder(
              "keyedge.verify.cache.refreshes", verdicts, CaffeineVerdicts::refreshes)
          .register(registry);
      FunctionCounter.builder("keyedge.edge.shed", filter, EdgeFilter::shed)
          .description("Requests shed by the concurrency gate (503 overloaded)")
          .register(registry);
      Gauge.builder("keyedge.edge.concurrency.limit", limiter, ConcurrencyLimiter::limit)
          .register(registry);
      Gauge.builder("keyedge.edge.inflight", limiter, ConcurrencyLimiter::inFlight)
          .register(registry);
      FunctionCounter.builder("keyedge.usage.dropped", usage, MeterUsage::dropped)
          .description("Usage windows dropped after keys was unreachable too long (D-28)")
          .register(registry);
      Gauge.builder("keyedge.usage.pending.windows", usage, MeterUsage::pendingWindows)
          .register(registry);
      if (client instanceof ResilientVerifyClient r) {
        for (String state : java.util.List.of("CLOSED", "OPEN", "HALF_OPEN")) {
          Gauge.builder("keyedge.verify.breaker.state", r, b -> b.state().equals(state) ? 1 : 0)
              .tag("state", state)
              .register(registry);
        }
      }
    };
  }

  @Singleton
  CaffeineVerdicts verdicts(
      VerifyClient client,
      InstantSource clock,
      @Named(TaskExecutors.VIRTUAL) ExecutorService vt,
      EdgeConfig cfg,
      ResilienceConfig r,
      VerifyObserver observer) {
    return observed(
        observer,
        new CaffeineVerdicts(
            client,
            clock,
            vt,
            cfg.maxCachedVerdicts(),
            cfg.verifyWait(),
            cfg.negativeTtl(),
            cfg.refreshRatio(),
            new CaffeineVerdicts.Staleness(r.failurePolicy(), r.staleWindow())));
  }

  private static CaffeineVerdicts observed(VerifyObserver observer, CaffeineVerdicts verdicts) {
    verdicts.observeWith(observer);
    return verdicts;
  }

  @Singleton
  KeyLimits keyLimits(EdgeConfig cfg) {
    return new GcraKeyLimits(cfg.maxRateLimitedKeys(), Duration.ofMinutes(10), Ticker.system());
  }

  @Singleton
  UsageSink usageSink(
      HttpClient http,
      SecretBytes secrets,
      ServiceContract contract,
      EdgeConfig cfg,
      InstantSource clock,
      JsonMapper json) {
    return new HttpUsageSink(
        http,
        URI.create(cfg.keysUrl()),
        cfg.callerId(),
        secrets.bytes(
            contract.name(ServiceContract.CALLER_SECRET, cfg.callerSecret()), cfg.callerId()),
        clock,
        cfg.verifyTimeout(),
        json);
  }

  @Singleton
  MeterUsage meterUsage(InstantSource clock, UsageSink sink, EdgeConfig cfg) {
    return new MeterUsage(clock, sink, cfg.maxPendingUsageWindows());
  }

  @Singleton
  AdmitRequest admitRequest(
      Verdicts verdicts, KeyLimits limits, EdgeConfig cfg, AdmissionObserver observer) {
    return new AdmitRequest(routes(cfg), verdicts, limits, observer, System::nanoTime);
  }

  static RouteScopes routes(EdgeConfig cfg) {
    return new RouteScopes(
        cfg.routes().stream()
            .map(line -> {
              String[] p = line.trim().split("\\s+");
              if (p.length != 3) {
                throw new IllegalArgumentException("route must be 'METHOD /prefix scope': " + line);
              }
              return new RouteScopes.Rule(p[0], p[1], new Scope(p[2]));
            })
            .toList());
  }

  @Singleton
  EdgeFilter.EdgeRuntime runtime(
      ServiceContract contract,
      AdmitRequest admit,
      MeterUsage usage,
      Gate gate,
      SecretBytes secrets,
      EdgeConfig cfg,
      InstantSource clock,
      ConcurrencyLimiter limiter,
      io.opentelemetry.api.OpenTelemetry otel,
      JsonMapper json,
      @Named(TaskExecutors.VIRTUAL) ExecutorService vt)
      throws MalformedURLException {
    var app =
        new AppAddress(
            cfg.appUrl(),
            System.getenv("ECS_CONTAINER_METADATA_URI_V4"),
            HttpClient.newHttpClient(),
            json);
    Scheduler decisions = Schedulers.fromExecutorService(vt, "edge-decisions");
    return new EdgeFilter.EdgeRuntime(
        admit,
        usage,
        // requests carry absolute URIs (EdgeFilter.toApp): the base URL is never used
        ProxyHttpClient.create(URI.create("http://127.0.0.1").toURL()),
        decisions,
        gate,
        app,
        secrets.bytes(
            contract.name(ServiceContract.PRINCIPAL_SECRET, cfg.principalSecret()), "key"),
        clock,
        cfg.drainTimeout(),
        limiter,
        Ticker.system(),
        otel);
  }

  @Singleton
  EdgeDrain.DrainSettings drainSettings(EdgeConfig cfg) {
    return new EdgeDrain.DrainSettings(cfg.drainGrace(), cfg.drainTimeout());
  }
}
