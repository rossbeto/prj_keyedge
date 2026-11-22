package dev.keyedge.edge.wiring;

import dev.keyedge.awstarget.AwsTarget;
import dev.keyedge.awstarget.AwsTargetConfigurer;
import dev.keyedge.awstarget.AwsTargets;
import dev.keyedge.edge.adapters.cache.CaffeineVerdicts;
import dev.keyedge.edge.adapters.http.HttpUsageSink;
import dev.keyedge.edge.adapters.http.HttpVerifyClient;
import dev.keyedge.edge.adapters.ratelimit.GcraKeyLimits;
import dev.keyedge.edge.adapters.resilience.ResilientVerifyClient;
import dev.keyedge.edge.adapters.secrets.EdgeSecrets;
import dev.keyedge.edge.api.EdgeDrain;
import dev.keyedge.edge.api.EdgeFilter;
import dev.keyedge.edge.api.Gate;
import dev.keyedge.edge.app.AdmitRequest;
import dev.keyedge.edge.app.MeterUsage;
import dev.keyedge.edge.domain.RouteScopes;
import dev.keyedge.edge.ports.KeyLimits;
import dev.keyedge.edge.ports.SecretBytes;
import dev.keyedge.edge.ports.UsageSink;
import dev.keyedge.edge.ports.Verdicts;
import dev.keyedge.edge.ports.VerifyClient;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.ratelimit.Ticker;
import dev.keyedge.ratelimit.concurrency.AimdLimiter;
import dev.keyedge.ratelimit.concurrency.ConcurrencyLimiter;
import dev.keyedge.ratelimit.concurrency.FixedLimiter;
import dev.keyedge.ratelimit.concurrency.GradientLimiter;
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
      EdgeConfig cfg,
      ResilienceConfig r,
      InstantSource clock,
      JsonMapper json) {
    VerifyClient http1 =
        new HttpVerifyClient(
            http,
            URI.create(cfg.keysUrl()),
            cfg.callerId(),
            secrets.bytes(cfg.callerSecret(), cfg.callerId()),
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
          new GradientLimiter(c.initial(), c.min(), c.max(), c.gradientWindow(), c.smoothing());
    };
  }

  @Singleton
  CaffeineVerdicts verdicts(
      VerifyClient client,
      InstantSource clock,
      @Named(TaskExecutors.VIRTUAL) ExecutorService vt,
      EdgeConfig cfg,
      ResilienceConfig r) {
    return new CaffeineVerdicts(
        client,
        clock,
        vt,
        cfg.maxCachedVerdicts(),
        cfg.verifyWait(),
        cfg.negativeTtl(),
        cfg.refreshRatio(),
        new CaffeineVerdicts.Staleness(r.failurePolicy(), r.staleWindow()));
  }

  @Singleton
  KeyLimits keyLimits(EdgeConfig cfg) {
    return new GcraKeyLimits(cfg.maxRateLimitedKeys(), Duration.ofMinutes(10), Ticker.system());
  }

  @Singleton
  UsageSink usageSink(
      HttpClient http, SecretBytes secrets, EdgeConfig cfg, InstantSource clock, JsonMapper json) {
    return new HttpUsageSink(
        http,
        URI.create(cfg.keysUrl()),
        cfg.callerId(),
        secrets.bytes(cfg.callerSecret(), cfg.callerId()),
        clock,
        cfg.verifyTimeout(),
        json);
  }

  @Singleton
  MeterUsage meterUsage(InstantSource clock, UsageSink sink, EdgeConfig cfg) {
    return new MeterUsage(clock, sink, cfg.maxPendingUsageWindows());
  }

  @Singleton
  AdmitRequest admitRequest(Verdicts verdicts, KeyLimits limits, EdgeConfig cfg) {
    return new AdmitRequest(routes(cfg), verdicts, limits);
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
      AdmitRequest admit,
      MeterUsage usage,
      Gate gate,
      SecretBytes secrets,
      EdgeConfig cfg,
      InstantSource clock,
      ConcurrencyLimiter limiter,
      @Named(TaskExecutors.VIRTUAL) ExecutorService vt)
      throws MalformedURLException {
    URI app = URI.create(cfg.appUrl());
    Scheduler decisions = Schedulers.fromExecutorService(vt, "edge-decisions");
    return new EdgeFilter.EdgeRuntime(
        admit,
        usage,
        ProxyHttpClient.create(app.toURL()),
        decisions,
        gate,
        app,
        secrets.bytes(cfg.principalSecret(), "key"),
        clock,
        cfg.drainTimeout(),
        limiter,
        Ticker.system());
  }

  @Singleton
  EdgeDrain.DrainSettings drainSettings(EdgeConfig cfg) {
    return new EdgeDrain.DrainSettings(cfg.drainGrace(), cfg.drainTimeout());
  }
}
