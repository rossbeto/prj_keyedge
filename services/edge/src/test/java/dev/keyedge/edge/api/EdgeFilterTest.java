package dev.keyedge.edge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.keyedge.edge.adapters.secrets.EdgeSecrets;
import dev.keyedge.edge.ports.SecretBytes;
import dev.keyedge.keyformat.KeyEnvironment;
import dev.keyedge.keyformat.KeyFormat;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.KeySecret;
import dev.keyedge.keyformat.PrincipalHeader;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** The real sidecar (filter, cache, limits, signing, proxy) between a fake keys and a fake app. */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdgeFilterTest implements TestPropertyProvider {
  static final byte[] PRINCIPAL_KEY =
      "principal-test-key-32-bytes-xxxx".getBytes(StandardCharsets.UTF_8);
  private final FakeKeys keys = new FakeKeys();
  private final FakeApp app = new FakeApp();
  private final KeyId id = new KeyId(3, 3);
  private final KeySecret secret = KeySecret.ofBytes(new byte[32]);
  private final String apiKey = KeyFormat.format(KeyEnvironment.TEST, id, secret);

  @Factory
  @Requires(property = "keyedge.test.edge", value = "true")
  static class Secrets {
    @Singleton
    @Replaces(EdgeSecrets.class)
    SecretBytes secrets() {
      return (secretId, entry) ->
          secretId.equals("keyedge/principal")
              ? PRINCIPAL_KEY
              : "caller-credential-32-bytes-xxxxxx".getBytes(StandardCharsets.UTF_8);
    }
  }

  @Inject
  @Client("/")
  HttpClient client;

  @Override
  public Map<String, String> getProperties() {
    keys.goodSecret = secret.text();
    return Map.of(
        "keyedge.test.edge", "true",
        "keyedge.edge.keys-url", keys.url(),
        "keyedge.edge.app-url", app.url(),
        "keyedge.edge.drain-grace", "0s",
        "micronaut.lifecycle.graceful-shutdown.enabled", "false");
  }

  @AfterAll
  void stop() {
    keys.close();
    app.close();
  }

  private HttpClientResponseException problem(HttpRequest<?> r, int status) {
    var e =
        assertThrows(
            HttpClientResponseException.class, () -> client.toBlocking().exchange(r, String.class));
    assertThat(e.getStatus().getCode()).isEqualTo(status);
    assertThat(e.getResponse().getContentType().map(Object::toString))
        .contains("application/problem+json");
    return e;
  }

  @Test
  void admittedRequestsReachTheAppWithASignedPrincipalAndWithoutTheKey() {
    var echo =
        client
            .toBlocking()
            .retrieve(
                HttpRequest.GET("/v1/quotes/sku-1")
                    .bearerAuth(apiKey)
                    .header("X-Keyedge-Principal", "spoofed"),
                Argument.mapOf(String.class, Object.class));
    assertThat(echo).containsEntry("path", "/v1/quotes/sku-1").doesNotContainKey("authorization");
    String principal = (String) echo.get("x-keyedge-principal");
    String signature = (String) echo.get("x-keyedge-principal-signature");
    assertThat(principal).isNotEqualTo("spoofed");
    assertThat(
            PrincipalHeader.verify(
                PRINCIPAL_KEY,
                principal,
                signature,
                "GET",
                "/v1/quotes/sku-1",
                Instant.now(),
                Duration.ofSeconds(30)))
        .hasValueSatisfying(p -> assertThat(p.tenant().value()).isEqualTo("acme"));
  }

  @Test
  void rejectionsNeverReachTheApp() {
    int before = app.calls.get();
    problem(HttpRequest.GET("/v1/quotes/sku-1"), 401);
    problem(HttpRequest.GET("/v1/quotes/sku-1").bearerAuth("ke_test_typo"), 401);
    byte[] other = new byte[32];
    other[3] = 1;
    problem(
        HttpRequest.GET("/v1/quotes/sku-1")
            .bearerAuth(KeyFormat.format(KeyEnvironment.TEST, id, KeySecret.ofBytes(other))),
        401);
    problem(HttpRequest.GET("/v1/not-exposed").bearerAuth(apiKey), 404);
    problem(HttpRequest.POST("/v1/quotes/sku-1", "{}").bearerAuth(apiKey), 404);
    assertThat(app.calls.get()).isEqualTo(before);
  }

  @Test
  void ownHealthIsNotProxied() {
    var health =
        client
            .toBlocking()
            .retrieve(
                HttpRequest.GET("/health/readiness"), Argument.mapOf(String.class, Object.class));
    assertThat(health).containsEntry("status", "UP");
  }

  @Test
  void cachedVerdictsServeRepeatedRequests() {
    client.toBlocking().exchange(HttpRequest.GET("/v1/quotes/a").bearerAuth(apiKey), String.class);
    int after = keys.verifies.get();
    for (int i = 0; i < 20; i++) {
      client
          .toBlocking()
          .exchange(HttpRequest.GET("/v1/quotes/a").bearerAuth(apiKey), String.class);
    }
    assertThat(keys.verifies.get()).isEqualTo(after);
  }

  @Inject dev.keyedge.edge.app.MeterUsage usage;

  @Test
  void rateLimitedKeysGet429WithRetryAfterAndUsageIsShipped() {
    keys.limitPerSecond = 1;
    var limited = KeyFormat.format(KeyEnvironment.TEST, new KeyId(9, 9), secret);
    client.toBlocking().exchange(HttpRequest.GET("/v1/quotes/r").bearerAuth(limited), String.class);
    var e = problem(HttpRequest.GET("/v1/quotes/r").bearerAuth(limited), 429);
    assertThat(e.getResponse().header("Retry-After")).isEqualTo("1");
    keys.limitPerSecond = 100;
    int before = keys.usages.get();
    usage.flush();
    assertThat(keys.usages.get()).isEqualTo(before + 1);
  }

  @Test
  void anUnreachableControlPlaneIs503WithRetryAfter() {
    keys.down = true;
    try {
      var cold = KeyFormat.format(KeyEnvironment.TEST, new KeyId(8, 8), secret);
      var e = problem(HttpRequest.GET("/v1/quotes/d").bearerAuth(cold), 503);
      assertThat(e.getResponse().header("Retry-After")).isEqualTo("1");
    } finally {
      keys.down = false;
    }
  }
}
