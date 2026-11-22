package dev.keyedge.edge.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.edge.adapters.secrets.EdgeSecrets;
import dev.keyedge.edge.ports.SecretBytes;
import dev.keyedge.keyformat.KeyEnvironment;
import dev.keyedge.keyformat.KeyFormat;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.KeySecret;
import dev.keyedge.ratelimit.concurrency.ConcurrencyLimiter;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** The concurrency gate sheds with 503 overloaded before any work; permits are always released. */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdgeOverloadTest implements TestPropertyProvider {
  private final FakeKeys keys = new FakeKeys();
  private final FakeApp app = new FakeApp();
  private final KeySecret secret = KeySecret.ofBytes(new byte[32]);
  private final String apiKey = KeyFormat.format(KeyEnvironment.TEST, new KeyId(4, 4), secret);

  @Factory
  @Requires(property = "keyedge.test.overload", value = "true")
  static class Secrets {
    @Singleton
    @Replaces(EdgeSecrets.class)
    SecretBytes secrets() {
      return (secretId, entry) ->
          "test-credential-32-bytes-xxxxxxx".getBytes(StandardCharsets.UTF_8);
    }
  }

  @Inject
  @Client("/")
  HttpClient client;

  @Inject ConcurrencyLimiter limiter;

  @Override
  public Map<String, String> getProperties() {
    keys.goodSecret = secret.text();
    return Map.of(
        "keyedge.test.overload", "true",
        "keyedge.edge.keys-url", keys.url(),
        "keyedge.edge.app-url", app.url(),
        "keyedge.edge.concurrency.mode", "FIXED",
        "keyedge.edge.concurrency.fixed-limit", "1",
        "keyedge.edge.drain-grace", "0s",
        "micronaut.lifecycle.graceful-shutdown.enabled", "false");
  }

  @AfterAll
  void stop() {
    keys.close();
    app.close();
  }

  @Test
  void overTheLimitIs503OverloadedAndPermitsAreReleased() {
    var held = limiter.tryAcquire(); // occupy the only slot
    assertThat(held).isPresent();
    try {
      var e =
          org.junit.jupiter.api.Assertions.assertThrows(
              HttpClientResponseException.class,
              () ->
                  client
                      .toBlocking()
                      .exchange(HttpRequest.GET("/v1/quotes/x").bearerAuth(apiKey), String.class));
      assertThat(e.getStatus().getCode()).isEqualTo(503);
      assertThat(e.getResponse().header("Retry-After")).isEqualTo("1");
      assertThat(e.getResponse().getBody(String.class))
          .hasValueSatisfying(b -> assertThat(b).contains("overloaded"));
    } finally {
      held.ifPresent(p -> p.onIgnore());
    }
    for (int i = 0; i < 5; i++) { // admitted and rejected requests both release their permit
      client
          .toBlocking()
          .exchange(HttpRequest.GET("/v1/quotes/x").bearerAuth(apiKey), String.class);
      org.junit.jupiter.api.Assertions.assertThrows(
          HttpClientResponseException.class,
          () -> client.toBlocking().exchange(HttpRequest.GET("/v1/quotes/x"), String.class));
    }
    assertThat(limiter.inFlight()).isZero();
    assertThat(limiter.doubleReleases()).isZero();
  }
}
