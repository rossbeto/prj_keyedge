package dev.keyedge.edge.lab;

import com.sun.net.httpserver.HttpServer;
import dev.keyedge.edge.adapters.http.HttpVerifyClient;
import dev.keyedge.edge.adapters.resilience.ResilientVerifyClient;
import dev.keyedge.edge.ports.VerifyClient;
import dev.keyedge.keyformat.KeyId;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/** Shared pieces of the Phase 008 experiments: a fake keys server, load, percentiles, results. */
final class LabKit {
  static final JsonMapper JSON = JsonMapper.createDefault();

  private LabKit() {}

  /** A keys stand-in: VALID unless the key id's sequence is in the revoked range; switchable. */
  static final class Keys implements AutoCloseable {
    final HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger callsWhileDown = new AtomicInteger();
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger maxInFlight = new AtomicInteger();
    volatile boolean down;
    volatile long delayMs;

    Keys(long ttlSeconds) throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.createContext(
          "/internal/v1/verify",
          ex -> {
            calls.incrementAndGet();
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
              String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
              if (down) {
                callsWhileDown.incrementAndGet();
                ex.sendResponseHeaders(503, -1);
                return;
              }
              if (delayMs > 0) {
                Thread.sleep(delayMs);
              }
              boolean revoked =
                  IntStream.range(900, 905)
                      .anyMatch(seq -> body.contains("\"" + new KeyId(1, seq) + "\""));
              String json =
                  revoked
                      ? "{\"verdict\":\"REVOKED\",\"at\":\"2026-11-23T10:00:00Z\",\"ttlSeconds\":5}"
                      : "{\"verdict\":\"VALID\",\"tenant\":\"acme\",\"scopes\":[\"quotes:read\"],"
                          + "\"rateLimit\":{\"perSecond\":1000,\"burst\":1000},\"ttlSeconds\":"
                          + ttlSeconds
                          + "}";
              byte[] out = json.getBytes(StandardCharsets.UTF_8);
              ex.sendResponseHeaders(200, out.length);
              ex.getResponseBody().write(out);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } catch (IOException e) {
              // client gave up (timeout): nothing to answer
            } finally {
              inFlight.decrementAndGet();
              ex.close();
            }
          });
      server.start();
    }

    VerifyClient http(ExecutorService vt, Duration timeout) {
      return new HttpVerifyClient(
          HttpClient.newBuilder().executor(vt).version(HttpClient.Version.HTTP_1_1).build(),
          URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
          "edge",
          new byte[32],
          Instant::now,
          timeout,
          JSON);
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  static ResilientVerifyClient breaker(VerifyClient delegate) {
    return new ResilientVerifyClient(
        delegate,
        new ResilientVerifyClient.Settings(
            50, 20, 50f, Duration.ofMillis(250), 80f, Duration.ofSeconds(10), 5, 64),
        System::nanoTime);
  }

  static void write(String id, Map<String, Object> result) throws IOException {
    Path dir = Path.of("../../results/labs");
    Files.createDirectories(dir);
    result.put("recorded_at", Instant.now().toString());
    Files.writeString(dir.resolve(id + ".json"), JSON.writeValueAsString(result) + "\n");
  }

  /** Open-model load: {@code rate} arrivals per second for {@code seconds}, each on its own VT. */
  static void openLoad(ExecutorService vt, int rate, double seconds, IntConsumer request)
      throws InterruptedException {
    int n = (int) (rate * seconds);
    var done = new CountDownLatch(n);
    long t0 = System.nanoTime();
    for (int i = 0; i < n; i++) {
      long wait = t0 + i * (1_000_000_000L / rate) - System.nanoTime();
      if (wait > 0) {
        Thread.sleep(Duration.ofNanos(wait));
      }
      int k = i;
      vt.submit(() -> {
        try {
          request.accept(k);
        } finally {
          done.countDown();
        }
      });
    }
    done.await();
  }

  static double pct(long[] sorted, double p) {
    return sorted.length == 0
        ? 0
        : sorted[Math.min(sorted.length - 1, (int) (sorted.length * p))] / 1e6;
  }

  static double rate(long ok, long total) {
    return total == 0 ? 0 : Math.round(10_000.0 * ok / total) / 10_000.0;
  }
}
