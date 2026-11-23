package dev.keyedge.quotes.lab;

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
import dev.keyedge.quotes.domain.RetryBudget;
import dev.keyedge.quotes.domain.RetryPolicy;
import dev.keyedge.quotes.ports.UpstreamEndpoints;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;

/**
 * The quotes object graph for experiments, built by hand like the factory does (no Micronaut), so
 * each experiment can swap one piece. The HTTP client is the JDK's, named in every result.
 */
final class LabQuotes {
  static final JsonMapper JSON = JsonMapper.createDefault();
  static final String CLIENT = "java.net.http.HttpClient (HTTP/1.1, virtual-thread executor)";

  private LabQuotes() {}

  static HttpClient http(ExecutorService vt) {
    return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).executor(vt).build();
  }

  static UpstreamEndpoints endpoints(String pricing, String stock, String fx, Duration timeout) {
    return new UpstreamEndpoints(
        URI.create(pricing), timeout, URI.create(stock), timeout, URI.create(fx), timeout);
  }

  static SingleFlightTokenProvider tokens(
      HttpClient http, String tokenUrl, ExecutorService vt, double refreshAt) {
    return new SingleFlightTokenProvider(
        new HttpTokenEndpoint(
            http, URI.create(tokenUrl), "quotes", "lab", Duration.ofSeconds(5), JSON),
        vt,
        System::nanoTime,
        refreshAt);
  }

  static ReactorQuoteFlow.Protection protection(
      String name, int attempts, int breakerMinimumCalls) {
    return new ReactorQuoteFlow.Protection(
        Bulkhead.of(
            name,
            BulkheadConfig.custom()
                .maxConcurrentCalls(1000)
                .maxWaitDuration(Duration.ZERO)
                .build()),
        CircuitBreaker.of(
            name,
            CircuitBreakerConfig.custom()
                .slidingWindowSize(Math.max(50, breakerMinimumCalls))
                .minimumNumberOfCalls(breakerMinimumCalls)
                .build()),
        attempts <= 1
            ? RetryPolicy.none()
            : new RetryPolicy(
                attempts,
                Duration.ofMillis(20),
                Duration.ofMillis(100),
                RetryPolicy.Jitter.DECORRELATED),
        new RetryBudget(0.1, 10));
  }

  static Guard guard(String name, ReactorQuoteFlow.Protection p) {
    return new Guard(
        name,
        p.bulkhead(),
        p.breaker(),
        p.policy(),
        p.budget(),
        System::nanoTime,
        new SplittableRandom(1),
        d -> Thread.sleep(d));
  }

  static GuardedUpstreams guarded(HttpUpstreams raw, ReactorQuoteFlow.Protections p) {
    return new GuardedUpstreams(
        raw,
        raw,
        raw,
        guard("pricing", p.pricing()),
        guard("stock", p.stock()),
        guard("fx", p.fx()));
  }

  static HttpUpstreams raw(HttpClient http, UpstreamEndpoints e, SingleFlightTokenProvider tokens) {
    return new HttpUpstreams(new JsonHttp(http, JSON), e, tokens, System::nanoTime);
  }

  static FanOut fanOut(String name, ExecutorService vt) {
    return FanOutProvider.load(
        name, new FanOutProvider.Resources(System::nanoTime, vt, ContextCarrier.none()));
  }

  static void write(String id, Map<String, Object> result) throws IOException {
    Path dir = Path.of("../../results/labs");
    Files.createDirectories(dir);
    result.put("recorded_at", Instant.now().toString());
    Files.writeString(dir.resolve(id + ".json"), JSON.writeValueAsString(result) + "\n");
  }

  static double pct(long[] sortedNanos, double p) {
    if (sortedNanos.length == 0) {
      return 0;
    }
    return Math.round(
            sortedNanos[Math.min(sortedNanos.length - 1, (int) (sortedNanos.length * p))]
                / 10_000.0)
        / 100.0;
  }
}
