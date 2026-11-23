package dev.keyedge.quotes.adapters.http;

import dev.keyedge.fanout.CallException;
import dev.keyedge.quotes.ports.RequestContext;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/**
 * JSON over the JDK {@link HttpClient} (the client is named in every experiment result: client cost
 * dominates CPU per call). Blocking, interruptible: an interrupt aborts the exchange, which is what
 * makes fan-out cancellation free the upstream connection. Non-2xx → {@link CallException} with the
 * status and any {@code Retry-After}.
 */
public final class JsonHttp {
  private final HttpClient http;
  private final JsonMapper json;

  public JsonHttp(HttpClient http, JsonMapper json) {
    this.http = http;
    this.json = json;
  }

  public Map<String, Object> get(
      String upstream, URI uri, Duration timeout, Map<String, String> headers)
      throws CallException, InterruptedException {
    return send(upstream, request(uri, timeout, headers).GET().build());
  }

  public Map<String, Object> post(
      String upstream, URI uri, Duration timeout, Map<String, String> headers, Object body)
      throws CallException, InterruptedException {
    try {
      return send(
          upstream,
          request(uri, timeout, headers)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
              .build());
    } catch (IOException e) {
      throw CallException.io(upstream, "body: " + e.getClass().getSimpleName());
    }
  }

  private static HttpRequest.Builder request(
      URI uri, Duration timeout, Map<String, String> headers) {
    var b = HttpRequest.newBuilder(uri).timeout(timeout.isZero() ? Duration.ofMillis(1) : timeout);
    if (RequestContext.CURRENT.isBound()) {
      b.header("X-Request-Id", RequestContext.CURRENT.get().requestId());
    }
    headers.forEach(b::header);
    return b;
  }

  private Map<String, Object> send(String upstream, HttpRequest request)
      throws CallException, InterruptedException {
    HttpResponse<byte[]> r;
    try {
      r = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (HttpTimeoutException e) {
      throw CallException.timeout(upstream);
    } catch (IOException e) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("cancelled");
      }
      throw CallException.io(upstream, e.getClass().getSimpleName());
    }
    if (r.statusCode() / 100 != 2) {
      Duration retryAfter =
          r.headers().firstValue("Retry-After").flatMap(JsonHttp::seconds).orElse(null);
      throw CallException.status(upstream, r.statusCode(), retryAfter);
    }
    try {
      Map<String, Object> body =
          json.readValue(r.body(), Argument.mapOf(String.class, Object.class));
      if (body == null) {
        throw CallException.io(upstream, "empty body");
      }
      return body;
    } catch (IOException e) {
      throw CallException.io(upstream, "unreadable body");
    }
  }

  static java.util.Optional<Duration> seconds(String header) {
    try {
      return java.util.Optional.of(Duration.ofSeconds(Long.parseLong(header.trim())));
    } catch (NumberFormatException e) {
      return java.util.Optional.empty();
    }
  }
}
