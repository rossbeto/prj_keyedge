package dev.keyedge.quotes.lab;

import static dev.keyedge.quotes.lab.LabQuotes.pct;
import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.fanout.Deadline;
import dev.keyedge.fanout.FanOut;
import dev.keyedge.quotes.FakeUpstreams;
import dev.keyedge.quotes.adapters.reactor.ReactorQuoteFlow;
import dev.keyedge.quotes.app.GetQuote;
import dev.keyedge.quotes.domain.CurrencyCode;
import dev.keyedge.quotes.domain.Quantity;
import dev.keyedge.quotes.domain.QuoteOutcome;
import dev.keyedge.quotes.domain.QuoteRequest;
import dev.keyedge.quotes.domain.Sku;
import dev.keyedge.quotes.lab.pitfalls.NaiveCompletableFanOut;
import dev.keyedge.quotes.ports.RequestContext;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Phase 009 experiments on the three concurrency models (functional size; curves: Phase 015). */
@Tag("experiment")
class FanoutExperiments {
  private static final QuoteRequest REQ =
      new QuoteRequest(new Sku("sku-1"), new Quantity(2), new CurrencyCode("USD"));

  static RequestContext ctx(Duration budget) {
    return new RequestContext("lab", "acme", Deadline.after(budget, System::nanoTime));
  }

  /** Open model: {@code rps} arrivals per second, each on its own virtual thread. */
  static long[] openLoad(
      ExecutorService vt, int rps, double seconds, IntFunction<Boolean> call, AtomicInteger errors)
      throws InterruptedException {
    int n = (int) (rps * seconds);
    var latencies = new ConcurrentLinkedQueue<Long>();
    var done = new CountDownLatch(n);
    long t0 = System.nanoTime();
    for (int i = 0; i < n; i++) {
      long wait = t0 + i * (1_000_000_000L / rps) - System.nanoTime();
      if (wait > 0) {
        Thread.sleep(Duration.ofNanos(wait));
      }
      int k = i;
      vt.submit(() -> {
        long s = System.nanoTime();
        try {
          if (!call.apply(k)) {
            errors.incrementAndGet();
          }
        } finally {
          latencies.add(System.nanoTime() - s);
          done.countDown();
        }
      });
    }
    done.await();
    return latencies.stream().mapToLong(Long::longValue).sorted().toArray();
  }

  private static IntFunction<Boolean> model(String name, FakeUpstreams up, ExecutorService vt) {
    var http = LabQuotes.http(vt);
    var e = LabQuotes.endpoints(up.url(), up.url(), up.url(), Duration.ofMillis(400));
    var tokens = LabQuotes.tokens(http, up.url() + "/oauth/token", vt, 0.8);
    var p =
        new ReactorQuoteFlow.Protections(
            LabQuotes.protection("pricing", 3, 1000),
            LabQuotes.protection("stock", 2, 1000),
            LabQuotes.protection("fx", 1, 1000));
    if (name.equals("reactor")) {
      var flow =
          new ReactorQuoteFlow(
              http, LabQuotes.JSON, e, tokens, p, "EUR", System::nanoTime, new SplittableRandom(1));
      return n ->
          flow.quote(REQ, ctx(Duration.ofSeconds(2))).toCompletableFuture().join()
              instanceof QuoteOutcome.Priced;
    }
    var guarded = LabQuotes.guarded(LabQuotes.raw(http, e, tokens), p);
    var get =
        new GetQuote(
            LabQuotes.fanOut(name, vt), guarded, guarded, guarded, new CurrencyCode("EUR"));
    return n -> get.handle(REQ, ctx(Duration.ofSeconds(2))) instanceof QuoteOutcome.Priced;
  }

  private static Map<String, Object> measure(String name, int rps, double seconds)
      throws Exception {
    try (var up = new FakeUpstreams();
        var vt = Executors.newVirtualThreadPerTaskExecutor()) {
      up.pricingDelayMs = 20;
      up.stockDelayMs = 10;
      up.fxDelayMs = 5;
      var call = model(name, up, vt);
      var errors = new AtomicInteger();
      openLoad(vt, 50, 1, call, errors); // warm-up
      var os =
          (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
      long cpu0 = os.getProcessCpuTime();
      errors.set(0);
      long[] l = openLoad(vt, rps, seconds, call, errors);
      long cpu = os.getProcessCpuTime() - cpu0;
      var r = new LinkedHashMap<String, Object>();
      r.put("model", name);
      r.put("rps", rps);
      r.put("p50_ms", pct(l, 0.5));
      r.put("p99_ms", pct(l, 0.99));
      r.put("cpu_us_per_request_process", Math.round(cpu / 1000.0 / l.length));
      r.put("errors", errors.get());
      return r;
    }
  }

  @Test
  void fanoutModels() throws Exception {
    var rows = new java.util.ArrayList<Map<String, Object>>();
    for (int rps : new int[] {10, 300}) {
      for (String m : List.of("structured", "future", "reactor")) {
        rows.add(measure(m, rps, 5));
      }
    }
    double[] lowP50 =
        rows.stream()
            .filter(r -> (int) r.get("rps") == 10)
            .mapToDouble(r -> (double) r.get("p50_ms"))
            .toArray();
    double min = java.util.Arrays.stream(lowP50).min().orElseThrow();
    double max = java.util.Arrays.stream(lowP50).max().orElseThrow();
    double spread = (max - min) / min;
    var result = new LinkedHashMap<String, Object>();
    result.put("experiment", "fanout-models");
    result.put(
        "hypothesis",
        "at 10 req/s the three models have the same p50 (within 10 %, ~ the slowest upstream, 20 ms)");
    result.put(
        "setup",
        "pricing 20 ms, stock 10 ms, fx 5 ms (fake upstreams); open load 5 s per row; "
            + LabQuotes.CLIENT);
    result.put("rows", rows);
    result.put("p50_spread_at_10rps", Math.round(spread * 1000) / 1000.0);
    result.put("verdict", spread <= 0.10 ? "confirmed" : "refuted");
    LabQuotes.write("fanout-models", result);
    assertThat(rows).allSatisfy(r -> assertThat((int) r.get("errors")).isZero());
  }

  private static Map<String, Object> cancel(String name) throws Exception {
    try (var up = new FakeUpstreams();
        var slow = new SlowSocketServer(2_000, "{\"sku\":\"sku-1\",\"available\":1}");
        var vt = Executors.newVirtualThreadPerTaskExecutor()) {
      up.pricingStatus = 500;
      up.pricingDelayMs = 50;
      var http = LabQuotes.http(vt);
      var tokens = LabQuotes.tokens(http, up.url() + "/oauth/token", vt, 0.8);
      var raw =
          LabQuotes.raw(
              http,
              LabQuotes.endpoints(up.url(), slow.url(), up.url(), Duration.ofSeconds(5)),
              tokens);
      FanOut fanOut =
          name.equals("naive-completable-future")
              ? new NaiveCompletableFanOut(vt)
              : LabQuotes.fanOut(name, vt);
      var get = new GetQuote(fanOut, raw, raw, raw, new CurrencyCode("EUR"));
      tokens.token(
          Deadline.after(Duration.ofSeconds(5), System::nanoTime)); // fetch the token first
      long[] wall = new long[5];
      for (int i = 0; i < 5; i++) {
        long s = System.nanoTime();
        assertThat(get.handle(REQ, ctx(Duration.ofSeconds(5))))
            .isInstanceOf(QuoteOutcome.Unavailable.class);
        wall[i] = System.nanoTime() - s;
      }
      Thread.sleep(2_500); // let un-cancelled calls finish
      java.util.Arrays.sort(wall);
      var r = new LinkedHashMap<String, Object>();
      r.put("adapter", name);
      r.put("wall_p50_ms", pct(wall, 0.5));
      r.put("wall_max_ms", pct(wall, 1.0));
      r.put("stock_calls_cancelled_at_server", slow.cancelled.get());
      r.put("stock_calls_completed_at_server", slow.completed.get());
      return r;
    }
  }

  @Test
  void cancelOnFailure() throws Exception {
    var structured = cancel("structured");
    var future = cancel("future");
    var naive = cancel("naive-completable-future");
    boolean confirmed =
        (double) structured.get("wall_max_ms") < 150
            && (int) structured.get("stock_calls_cancelled_at_server") == 5;
    var result = new LinkedHashMap<String, Object>();
    result.put("experiment", "cancel-on-failure");
    result.put(
        "hypothesis",
        "pricing fails at 50 ms while stock takes 2 s: the structured fan-out returns in < 150 ms and"
            + " the stock calls are cancelled (the server sees the connection close)");
    result.put(
        "setup",
        "5 sequential quotes per adapter; stock = raw socket server that detects client hang-ups; "
            + LabQuotes.CLIENT);
    result.put("structured", structured);
    result.put("future_with_submit_cancel", future);
    result.put("naive_completable_future", naive);
    result.put("verdict", confirmed ? "confirmed" : "refuted");
    LabQuotes.write("cancel-on-failure", result);
    assertThat(confirmed).as(result.toString()).isTrue();
  }
}
