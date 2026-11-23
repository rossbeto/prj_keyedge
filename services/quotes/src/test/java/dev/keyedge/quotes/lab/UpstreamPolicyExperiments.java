package dev.keyedge.quotes.lab;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.fanout.CallException;
import dev.keyedge.fanout.Deadline;
import dev.keyedge.quotes.FakeUpstreams;
import dev.keyedge.quotes.adapters.reactor.ReactorQuoteFlow;
import dev.keyedge.quotes.domain.Quantity;
import dev.keyedge.quotes.domain.Sku;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Phase 009 experiments on retries and the shared token. */
@Tag("experiment")
class UpstreamPolicyExperiments {
  private static Deadline in(Duration d) {
    return Deadline.after(d, System::nanoTime);
  }

  @Test
  void retryOnlyIdempotent() throws Exception {
    try (var up = new FakeUpstreams();
        var vt = Executors.newVirtualThreadPerTaskExecutor()) {
      up.stockStatus = 503;
      up.reserveStatus = 503;
      var http = LabQuotes.http(vt);
      var tokens = LabQuotes.tokens(http, up.url() + "/oauth/token", vt, 0.8);
      var stockProtection = LabQuotes.protection("stock", 2, 1000);
      var p =
          new ReactorQuoteFlow.Protections(
              LabQuotes.protection("pricing", 3, 1000),
              stockProtection,
              LabQuotes.protection("fx", 1, 1000));
      var guarded =
          LabQuotes.guarded(
              LabQuotes.raw(
                  http,
                  LabQuotes.endpoints(up.url(), up.url(), up.url(), Duration.ofSeconds(1)),
                  tokens),
              p);
      for (int i = 0; i < 100; i++) {
        try {
          guarded.stock(new Sku("sku-" + i), in(Duration.ofSeconds(2)));
        } catch (CallException expected) {
          // 503
        }
        try {
          guarded.reserve(
              new Sku("sku-" + i), new Quantity(1), "req-" + i, in(Duration.ofSeconds(2)));
        } catch (CallException expected) {
          // 503
        }
      }
      long gets = up.calls("stock");
      long posts = up.calls("reserve");
      long budget = 10 + (long) (stockProtection.budget().calls() * 0.1);
      boolean confirmed = posts == 100 && gets <= 200 && gets - 100 <= budget;
      var result = new LinkedHashMap<String, Object>();
      result.put("experiment", "retry-only-idempotent");
      result.put(
          "hypothesis",
          "with stock answering 503: 100 reservations (POST) reach it exactly 100 times; 100 GETs are"
              + " retried, at most once each (2 attempts) and within the retry budget (10 + 10 % of calls)");
      result.put("get_attempts_seen_by_upstream", gets);
      result.put("post_attempts_seen_by_upstream", posts);
      result.put("retries_granted_by_budget", stockProtection.budget().retries());
      result.put("budget_limit", budget);
      result.put("verdict", confirmed ? "confirmed" : "refuted");
      LabQuotes.write("retry-only-idempotent", result);
      assertThat(confirmed).as(result.toString()).isTrue();
    }
  }

  private static long burst(
      FakeUpstreams up, dev.keyedge.quotes.adapters.auth.SingleFlightTokenProvider tokens)
      throws Exception {
    int before = up.tokensIssued.get();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 200; i++) {
        pool.submit(() -> {
          start.await();
          return tokens.token(in(Duration.ofSeconds(5)));
        });
      }
      start.countDown();
    }
    Thread.sleep(500); // let a refresh-ahead finish
    return up.tokensIssued.get() - before;
  }

  @Test
  void tokenStampede() throws Exception {
    try (var up = new FakeUpstreams();
        var vt = Executors.newVirtualThreadPerTaskExecutor()) {
      up.tokenLifetimeSeconds = 5;
      up.tokenDelayMs = 200;
      var tokens = LabQuotes.tokens(LabQuotes.http(vt), up.url() + "/oauth/token", vt, 0.8);
      long t0 = System.nanoTime();
      tokens.token(in(Duration.ofSeconds(5)));
      Thread.sleep(Duration.ofNanos(Math.max(0, t0 + 4_900_000_000L - System.nanoTime())));
      long nearExpiry = burst(up, tokens); // t = 4.9 s: due for refresh, still valid
      Thread.sleep(Duration.ofSeconds(5).plusMillis(300)); // let the refreshed token expire too
      long afterExpiry = burst(up, tokens); // hard-expired: everyone waits for one fetch
      boolean confirmed = nearExpiry == 1 && afterExpiry == 1;
      var result = new LinkedHashMap<String, Object>();
      result.put("experiment", "token-stampede");
      result.put(
          "hypothesis",
          "200 concurrent calls at token expiry cause exactly 1 token request (server-side count),"
              + " both just before expiry (refresh-ahead) and after it");
      result.put(
          "setup", "token lifetime 5 s, issue latency 200 ms, refresh at 80 % of the lifetime");
      result.put("tokens_issued_burst_at_4_9s", nearExpiry);
      result.put("tokens_issued_burst_after_expiry", afterExpiry);
      result.put("verdict", confirmed ? "confirmed" : "refuted");
      LabQuotes.write("token-stampede", result);
      assertThat(confirmed).as(result.toString()).isTrue();
    }
  }
}
