package dev.keyedge.sim.api;

import dev.keyedge.sim.app.Counters;
import dev.keyedge.sim.app.Respond;
import dev.keyedge.sim.app.Tokens;
import dev.keyedge.sim.domain.Behaviour;
import dev.keyedge.sim.ports.Sleeper;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The three upstreams quotes needs. Every call: count, decide (seeded), wait on a virtual thread,
 * answer. {@code pricing} needs a client-credentials token. Answers are deterministic per SKU.
 */
@Controller
@ExecuteOn(TaskExecutors.VIRTUAL)
public class SimController {
  private final Respond respond;
  private final Counters counters;
  private final Tokens tokens;
  private final Sleeper sleeper;

  SimController(Respond respond, Counters counters, Tokens tokens, Sleeper sleeper) {
    this.respond = respond;
    this.counters = counters;
    this.tokens = tokens;
    this.sleeper = sleeper;
  }

  @Get("/pricing/{sku}")
  public HttpResponse<?> pricing(HttpRequest<?> request, @PathVariable String sku) {
    Optional<String> token =
        request
            .getHeaders()
            .getAuthorization()
            .filter(a -> a.startsWith("Bearer "))
            .map(a -> a.substring(7));
    switch (tokens.check(token)) {
      case VALID -> {}
      case EXPIRED -> {
        counters.expiredToken();
        return unauthorized();
      }
      case UNKNOWN, MISSING -> {
        return unauthorized();
      }
    }
    return serve(
        "pricing",
        Map.of(
            "sku",
            sku,
            "unitPriceCents",
            100 + Math.floorMod(sku.hashCode(), 9_900),
            "currency",
            "EUR"));
  }

  @Post("/pricing/reservations")
  public HttpResponse<?> reserve(HttpRequest<?> request) {
    counters.post(request.getHeaders().get("X-Request-Id", String.class).orElse("none"));
    return serve("reservations", Map.of("reservation", "r-" + System.nanoTime()));
  }

  @Get("/stock/{sku}")
  public HttpResponse<?> stock(@PathVariable String sku) {
    return serve("stock", Map.of("sku", sku, "available", Math.floorMod(sku.hashCode(), 500)));
  }

  @Get("/fx/{from}/{to}")
  public HttpResponse<?> fx(@PathVariable String from, @PathVariable String to) {
    double rate =
        from.equals(to) ? 1.0 : 0.5 + Math.floorMod((from + to).hashCode(), 1000) / 1000.0;
    return serve("fx", Map.of("from", from, "to", to, "rate", rate));
  }

  private static MutableHttpResponse<?> unauthorized() {
    return HttpResponse.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_token"));
  }

  private HttpResponse<?> serve(String endpoint, Map<String, Object> body) {
    counters.start(endpoint);
    int status = 200;
    try {
      Behaviour b = respond.decide(endpoint);
      sleeper.sleep(b.latency());
      return switch (b) {
        case Behaviour.Ok ok -> HttpResponse.ok(body);
        case Behaviour.Fail(var latency, int code, var retryAfter) -> {
          status = code;
          var r =
              HttpResponse.status(HttpStatus.valueOf(code))
                  .body(Map.of("error", "simulated", "status", code));
          retryAfter.ifPresent(s -> r.header("Retry-After", Integer.toString(s)));
          yield r;
        }
        case Behaviour.SlowClose(var latency, Duration pause) ->
            // Streamed as raw bytes (Micronaut would wrap a JSON publisher in an array); clients
            // parse it as JSON.
            HttpResponse.ok(slowBody(body, pause))
                .contentType(io.micronaut.http.MediaType.APPLICATION_OCTET_STREAM);
      };
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      status = 503;
      return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE);
    } finally {
      counters.end(endpoint, status);
    }
  }

  /**
   * The first half of the JSON, a pause, then the rest: the stream stays open while nothing
   * arrives.
   */
  private static Publisher<byte[]> slowBody(Map<String, Object> body, Duration pause) {
    String json =
        "{\"slow\":true,\"sku\":\""
            + body.getOrDefault("sku", "")
            + "\",\"note\":\"paused "
            + pause.toMillis()
            + " ms\"}";
    int half = json.length() / 2;
    byte[] head = json.substring(0, half).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] tail = json.substring(half).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return Flux.concat(Flux.just(head), Mono.delay(pause).thenMany(Flux.just(tail)));
  }
}
