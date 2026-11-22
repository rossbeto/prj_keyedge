package dev.keyedge.edge.lab;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.keyedge.edge.adapters.cache.CaffeineVerdicts;
import dev.keyedge.edge.adapters.http.HttpVerifyClient;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.KeySecret;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Phase 007 notebook experiments, in-process with the real cache and HTTP verify client against a
 * counting fake keys server (its request counter is the independent count). Each asserts its
 * verdict and writes {@code results/labs/<id>.json}; assertions are ratios, not absolute
 * milliseconds.
 */
@Tag("experiment")
class EdgeExperiments {
  private static final JsonMapper JSON = JsonMapper.createDefault();

  /** A keys stand-in that answers VALID after {@code delayMs}; counts requests. */
  private static final class SlowKeys implements AutoCloseable {
    final HttpServer server;
    final AtomicInteger calls = new AtomicInteger();

    SlowKeys(long delayMs, long ttlSeconds) throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.createContext(
          "/internal/v1/verify",
          ex -> {
            calls.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            try {
              Thread.sleep(delayMs);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            byte[] out =
                ("{\"verdict\":\"VALID\",\"tenant\":\"acme\",\"scopes\":[\"quotes:read\"],"
                        + "\"rateLimit\":{\"perSecond\":1000,\"burst\":1000},\"ttlSeconds\":"
                        + ttlSeconds
                        + "}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
          });
      server.start();
    }

    CaffeineVerdicts cache(ExecutorService vt, double refreshRatio) {
      var client =
          new HttpVerifyClient(
              HttpClient.newBuilder().executor(vt).build(),
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              "edge",
              new byte[32],
              Instant::now,
              Duration.ofSeconds(5),
              JSON);
      return new CaffeineVerdicts(
          client,
          Instant::now,
          vt,
          10_000,
          Duration.ofSeconds(5),
          Duration.ofSeconds(5),
          refreshRatio);
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  private static void write(String id, Map<String, Object> result) throws IOException {
    Path dir = Path.of("../../results/labs");
    Files.createDirectories(dir);
    result.put("recorded_at", Instant.now().toString());
    Files.writeString(dir.resolve(id + ".json"), JSON.writeValueAsString(result) + "\n");
  }

  @Test
  void singleFlight() throws Exception {
    try (var keys = new SlowKeys(100, 60);
        var vt = Executors.newVirtualThreadPerTaskExecutor()) {
      var cache = keys.cache(vt, 0.7);
      var id = new KeyId(1, 1);
      var secret = KeySecret.ofBytes(new byte[32]);
      var start = new CountDownLatch(1);
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 500; i++) {
        futures.add(
            vt.submit(() -> {
              start.await();
              return cache.verdict(id, secret);
            }));
      }
      start.countDown();
      for (var f : futures) {
        f.get();
      }
      var result = new LinkedHashMap<String, Object>();
      result.put("experiment", "single-flight");
      result.put(
          "hypothesis", "500 concurrent requests with one cold key cause exactly 1 verify call");
      result.put("requests", 500);
      result.put("verify_calls_seen_by_keys", keys.calls.get());
      result.put("verdict", keys.calls.get() == 1 ? "confirmed" : "refuted");
      write("single-flight", result);
      assertThat(keys.calls.get()).isEqualTo(1);
    }
  }

  /** p99 of verdict() latency at a steady arrival rate across several TTL expiries. */
  private static Map<String, Object> steadyLoad(double refreshRatio) throws Exception {
    try (var keys = new SlowKeys(300, 2);
        var vt = Executors.newVirtualThreadPerTaskExecutor()) {
      var cache = keys.cache(vt, refreshRatio);
      var id = new KeyId(2, 2);
      var secret = KeySecret.ofBytes(new byte[32]);
      cache.verdict(id, secret); // warm
      int rate = 200;
      int seconds = 8;
      long[] latencyMicros = new long[rate * seconds];
      long t0 = System.nanoTime();
      var done = new CountDownLatch(latencyMicros.length);
      for (int i = 0; i < latencyMicros.length; i++) {
        long due = t0 + i * (1_000_000_000L / rate);
        long wait = due - System.nanoTime();
        if (wait > 0) {
          Thread.sleep(Duration.ofNanos(wait));
        }
        int n = i;
        vt.submit(() -> {
          long s = System.nanoTime();
          cache.verdict(id, secret);
          latencyMicros[n] = (System.nanoTime() - s) / 1000;
          done.countDown();
        });
      }
      done.await();
      Arrays.sort(latencyMicros);
      var r = new LinkedHashMap<String, Object>();
      r.put("refresh_ratio", refreshRatio);
      r.put("p50_ms", latencyMicros[latencyMicros.length / 2] / 1000.0);
      r.put("p99_ms", latencyMicros[(int) (latencyMicros.length * 0.99)] / 1000.0);
      r.put("max_ms", latencyMicros[latencyMicros.length - 1] / 1000.0);
      r.put("verify_calls", keys.calls.get());
      return r;
    }
  }

  @Test
  void refreshAhead() throws Exception {
    var ahead = steadyLoad(0.7);
    var expireOnly = steadyLoad(1.0);
    double ratio =
        (double) expireOnly.get("p99_ms") / Math.max(0.001, (double) ahead.get("p99_ms"));
    var result = new LinkedHashMap<String, Object>();
    result.put("experiment", "refresh-ahead");
    result.put(
        "hypothesis",
        "with refresh at 70 % of the TTL, p99 does not spike when entries expire; expire-only makes callers wait for the reload (300 ms)");
    result.put("setup", "200 req/s for 8 s, one hot key, TTL hint 2 s, verify latency 300 ms");
    result.put("refresh_ahead", ahead);
    result.put("expire_only", expireOnly);
    result.put("p99_ratio_expire_only_over_refresh_ahead", Math.round(ratio * 10) / 10.0);
    result.put("verdict", ratio >= 3 ? "confirmed" : "refuted");
    write("refresh-ahead", result);
    assertThat(ratio).isGreaterThanOrEqualTo(3);
  }

  @Test
  void vtHandoff() throws Exception {
    var vtScheduler =
        Schedulers.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor(), "lab-vt");
    int n = 20_000;
    long[] direct = new long[n];
    long[] handoff = new long[n];
    for (int round = 0; round < 5; round++) { // warm-up rounds (the last one is measured)
      for (int i = 0; i < n; i++) {
        long s = System.nanoTime();
        Mono.fromCallable(() -> 42).block();
        direct[i] = System.nanoTime() - s;
        s = System.nanoTime();
        Mono.fromCallable(() -> 42).subscribeOn(vtScheduler).block();
        handoff[i] = System.nanoTime() - s;
      }
    }
    Arrays.sort(direct);
    Arrays.sort(handoff);
    double medianDeltaMs = (handoff[n / 2] - direct[n / 2]) / 1e6;
    double p99DeltaMs = (handoff[(int) (n * 0.99)] - direct[(int) (n * 0.99)]) / 1e6;
    var result = new LinkedHashMap<String, Object>();
    result.put("experiment", "vt-handoff");
    result.put(
        "hypothesis",
        "moving the decision from the calling thread to a virtual thread and back costs < 0.1 ms median");
    result.put("iterations", n);
    result.put("median_delta_ms", Math.round(medianDeltaMs * 10_000) / 10_000.0);
    result.put("p99_delta_ms", Math.round(p99DeltaMs * 10_000) / 10_000.0);
    result.put("verdict", medianDeltaMs < 0.1 ? "confirmed" : "refuted");
    write("vt-handoff", result);
    vtScheduler.dispose();
    assertThat(medianDeltaMs).isLessThan(0.1);
  }
}
