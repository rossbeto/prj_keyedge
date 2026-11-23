package dev.keyedge.edge.lab;

import static dev.keyedge.edge.lab.LabKit.openLoad;
import static dev.keyedge.edge.lab.LabKit.pct;
import static dev.keyedge.edge.lab.LabKit.write;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.keyedge.ratelimit.concurrency.AimdLimiter;
import dev.keyedge.ratelimit.concurrency.ConcurrencyLimiter;
import dev.keyedge.ratelimit.concurrency.FixedLimiter;
import dev.keyedge.ratelimit.concurrency.GradientLimiter;
import dev.keyedge.ratelimit.concurrency.Permit;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Phase 008 notebook experiment on the sidecar's concurrency gate, in-process: the limiters from
 * libs/ratelimit in front of a capacity-limited app that browns out. Verdict written to
 * results/labs/adaptive-vs-fixed.json.
 */
@Tag("experiment")
class AdaptiveLimitExperiments {
  // ---- adaptive-vs-fixed -----------------------------------------------------------------------

  /** An app with 16 workers; service time 20 ms, then 60 ms during a brown-out. */
  private static final class App implements AutoCloseable {
    final HttpServer server;
    volatile long serviceMs = 20;
    private final ExecutorService workers = Executors.newFixedThreadPool(16);

    App() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1024);
      server.setExecutor(workers);
      server.createContext(
          "/",
          ex -> {
            try {
              Thread.sleep(serviceMs);
              ex.sendResponseHeaders(200, -1);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              ex.close();
            }
          });
      server.start();
    }

    @Override
    public void close() {
      server.stop(0);
      workers.shutdownNow();
    }
  }

  /** Counters for one brown-out run (requests that arrived during the brown-out). */
  private static final class Run {
    final AtomicLong good = new AtomicLong();
    final AtomicLong shed = new AtomicLong();
    final AtomicLong slow = new AtomicLong();
    final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
    final List<Integer> limits = new ArrayList<>();

    void sampleLimits(ConcurrencyLimiter limiter, Thread self) {
      while (!self.isInterrupted()) {
        synchronized (limits) {
          limits.add(limiter.limit());
        }
        try {
          Thread.sleep(250);
        } catch (InterruptedException e) {
          return;
        }
      }
    }

    List<Integer> limitRange() {
      synchronized (limits) {
        int[] ls = limits.stream().mapToInt(Integer::intValue).toArray();
        return List.of(Arrays.stream(ls).min().orElse(0), Arrays.stream(ls).max().orElse(0));
      }
    }
  }

  private static final long SLO_NANOS = Duration.ofMillis(500).toNanos();

  /** One request through the limiter to the app; feeds the permit and the run's counters. */
  private static void request(
      ConcurrencyLimiter limiter, HttpClient http, URI uri, Run run, boolean counted) {
    long s = System.nanoTime();
    Optional<Permit> p = limiter.tryAcquire();
    if (p.isEmpty()) {
      run.shed.addAndGet(counted ? 1 : 0);
      return;
    }
    try {
      http.send(
          HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).build(),
          HttpResponse.BodyHandlers.discarding());
      long d = System.nanoTime() - s;
      p.get().onSuccess(d);
      if (counted) {
        run.latencies.add(d);
        (d <= SLO_NANOS ? run.good : run.slow).incrementAndGet();
      }
    } catch (IOException e) {
      p.get().onDropped();
    } catch (InterruptedException e) {
      p.get().onIgnore();
      Thread.currentThread().interrupt();
    }
  }

  private static Map<String, Object> brownout(String mode, ConcurrencyLimiter limiter)
      throws Exception {
    try (var app = new App();
        var vt = Executors.newVirtualThreadPerTaskExecutor()) {
      var http = HttpClient.newBuilder().executor(vt).version(HttpClient.Version.HTTP_1_1).build();
      var uri = URI.create("http://127.0.0.1:" + app.server.getAddress().getPort() + "/q");
      long brownAt = System.nanoTime() + Duration.ofSeconds(3).toNanos();
      var run = new Run();
      var sampler =
          Thread.ofVirtual().start(() -> run.sampleLimits(limiter, Thread.currentThread()));
      openLoad(
          vt,
          600,
          11,
          n -> {
            boolean browning = System.nanoTime() >= brownAt;
            if (browning) {
              app.serviceMs = 60;
            }
            request(limiter, http, uri, run, browning);
          });
      sampler.interrupt();
      long[] l = run.latencies.stream().mapToLong(Long::longValue).sorted().toArray();
      var r = new LinkedHashMap<String, Object>();
      r.put("mode", mode);
      r.put("goodput_per_s_in_brownout", Math.round(run.good.get() / 8.0));
      r.put("over_slo", run.slow.get());
      r.put("shed", run.shed.get());
      r.put("p99_ms_in_brownout", pct(l, 0.99));
      r.put("limit_min_max", run.limitRange());
      r.put("double_releases", limiter.doubleReleases());
      return r;
    }
  }

  @Test
  void adaptiveVsFixed() throws Exception {
    var fixed = brownout("fixed-200", new FixedLimiter(200));
    var gradient = brownout("gradient", new GradientLimiter(50, 10, 500, 100, 0.2));
    var gradientTolerant =
        brownout("gradient-tolerance-2", new GradientLimiter(50, 10, 500, 100, 0.2, 2.0));
    var aimd =
        brownout("aimd", new AimdLimiter(50, 10, 500, Duration.ofMillis(250).toNanos(), 0.9));
    double ratio =
        (long) gradient.get("goodput_per_s_in_brownout")
            / Math.max(1.0, (long) fixed.get("goodput_per_s_in_brownout"));
    var result = new LinkedHashMap<String, Object>();
    result.put("experiment", "adaptive-vs-fixed");
    result.put(
        "hypothesis",
        "in a brown-out (capacity 800 -> 267 req/s) under 600 req/s offered, an adaptive limit"
            + " sheds the excess early and keeps goodput (2xx within 500 ms) >= 1.2x a fixed limit of"
            + " 200, which lets the queue (and latency) grow past the SLO");
    result.put(
        "setup",
        "app: 16 workers, 20 ms service time, 60 ms from t=3 s; open load 600 req/s for 11 s;"
            + " goodput counted over the 8 s brown-out");
    result.put("fixed", fixed);
    result.put("gradient", gradient);
    result.put("aimd", aimd);
    result.put("gradient_tolerance_2", gradientTolerant);
    result.put("goodput_ratio_gradient_over_fixed", Math.round(ratio * 100) / 100.0);
    result.put("verdict", ratio >= 1.2 ? "confirmed" : "refuted");
    write("adaptive-vs-fixed", result);
    assertThat((long) fixed.get("double_releases")).isZero();
  }
}
