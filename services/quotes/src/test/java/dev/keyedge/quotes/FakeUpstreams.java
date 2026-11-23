package dev.keyedge.quotes;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * pricing, stock, fx, the token endpoint and reservations in one switchable server: each field is a
 * scenario knob; the counters are the independent measurement.
 */
public final class FakeUpstreams implements AutoCloseable {
  final HttpServer server;
  public final Map<String, LongAdder> calls = new ConcurrentHashMap<>();
  public final AtomicInteger tokensIssued = new AtomicInteger();

  /** The last W3C traceparent each endpoint received (Phase 011 propagation tests). */
  public final Map<String, String> traceparents = new ConcurrentHashMap<>();

  private final Map<String, Long> tokens = new ConcurrentHashMap<>();
  public volatile int pricingStatus = 200;
  public volatile int stockStatus = 200;
  public volatile int fxStatus = 200;
  public volatile int reserveStatus = 201;
  public volatile long pricingDelayMs;
  public volatile long stockDelayMs;
  public volatile long fxDelayMs;
  public volatile long tokenDelayMs;
  public volatile long tokenLifetimeSeconds = 300;
  public volatile String retryAfter = "";

  public FakeUpstreams() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/oauth/token",
        ex -> {
          count("token");
          sleep(tokenDelayMs);
          ex.getRequestBody().readAllBytes();
          String token = "t-" + tokensIssued.incrementAndGet();
          tokens.put(token, System.nanoTime() + tokenLifetimeSeconds * 1_000_000_000L);
          reply(
              ex,
              200,
              "{\"access_token\":\""
                  + token
                  + "\",\"token_type\":\"Bearer\",\"expires_in\":"
                  + tokenLifetimeSeconds
                  + "}");
        });
    server.createContext("/pricing/", this::pricing);
    server.createContext(
        "/stock/",
        ex -> {
          count("stock");
          trace("stock", ex);
          sleep(stockDelayMs);
          reply(ex, stockStatus, "{\"sku\":\"" + last(ex) + "\",\"available\":7}");
        });
    server.createContext(
        "/fx/",
        ex -> {
          count("fx");
          trace("fx", ex);
          sleep(fxDelayMs);
          String[] p = ex.getRequestURI().getPath().split("/");
          reply(ex, fxStatus, "{\"from\":\"" + p[2] + "\",\"to\":\"" + p[3] + "\",\"rate\":1.1}");
        });
    server.start();
  }

  private void pricing(HttpExchange ex) throws IOException {
    if (ex.getRequestMethod().equals("POST")) {
      count("reserve");
      ex.getRequestBody().readAllBytes();
      reply(ex, reserveStatus, reserveStatus / 100 == 2 ? "{\"reservation\":\"r-1\"}" : "{}");
      return;
    }
    count("pricing");
    trace("pricing", ex);
    String auth = ex.getRequestHeaders().getFirst("Authorization");
    Long expires = auth == null ? null : tokens.get(auth.replace("Bearer ", ""));
    if (expires == null || System.nanoTime() - expires >= 0) {
      reply(ex, 401, "{\"error\":\"invalid_token\"}");
      return;
    }
    sleep(pricingDelayMs);
    String sku = last(ex);
    reply(
        ex,
        pricingStatus,
        "{\"sku\":\"" + sku + "\",\"unitPriceCents\":1234,\"currency\":\"EUR\"}");
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public long calls(String endpoint) {
    LongAdder n = calls.get(endpoint);
    return n == null ? 0 : n.sum();
  }

  /** Healthy again, counters kept. */
  public void heal() {
    pricingStatus = 200;
    stockStatus = 200;
    fxStatus = 200;
    reserveStatus = 201;
    pricingDelayMs = 0;
    stockDelayMs = 0;
    fxDelayMs = 0;
    tokenDelayMs = 0;
    tokenLifetimeSeconds = 300;
    retryAfter = "";
  }

  private void count(String endpoint) {
    calls.computeIfAbsent(endpoint, e -> new LongAdder()).increment();
  }

  private void trace(String endpoint, HttpExchange ex) {
    String tp = ex.getRequestHeaders().getFirst("traceparent");
    if (tp != null) {
      traceparents.put(endpoint, tp);
    }
  }

  private static String last(HttpExchange ex) {
    String path = ex.getRequestURI().getPath();
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private static void sleep(long ms) {
    if (ms > 0) {
      try {
        Thread.sleep(ms);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void reply(HttpExchange ex, int status, String json) throws IOException {
    byte[] out = json.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    if (!retryAfter.isEmpty() && status >= 400) {
      ex.getResponseHeaders().add("Retry-After", retryAfter);
    }
    try {
      ex.sendResponseHeaders(status, out.length);
      ex.getResponseBody().write(out);
    } catch (IOException e) {
      // the client went away (cancelled or timed out)
    } finally {
      ex.close();
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
