package dev.keyedge.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@MicronautTest
class SimApiTest {
  @Inject
  @Client("/")
  HttpClient client;

  @BeforeEach
  void reset() {
    client.toBlocking().exchange(HttpRequest.PUT("/__chaos?name=healthy", ""));
    client.toBlocking().exchange(HttpRequest.POST("/__reset", ""));
  }

  private String token() {
    var r =
        client
            .toBlocking()
            .retrieve(
                HttpRequest.POST("/oauth/token", Map.of("grant_type", "client_credentials"))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
                    .basicAuth("quotes", "local-only-not-a-secret"),
                Argument.mapOf(String.class, Object.class));
    return (String) r.get("access_token");
  }

  @Test
  void pricingNeedsAValidToken() {
    var e =
        assertThrows(
            HttpClientResponseException.class,
            () -> client.toBlocking().exchange("/pricing/sku-1"));
    assertThat(e.getStatus().getCode()).isEqualTo(401);
    var price =
        client
            .toBlocking()
            .retrieve(
                HttpRequest.GET("/pricing/sku-1").bearerAuth(token()),
                Argument.mapOf(String.class, Object.class));
    assertThat(price).containsEntry("sku", "sku-1").containsEntry("currency", "EUR");
    var wrong =
        assertThrows(
            HttpClientResponseException.class,
            () ->
                client
                    .toBlocking()
                    .exchange(
                        HttpRequest.POST("/oauth/token", Map.of("grant_type", "client_credentials"))
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
                            .basicAuth("quotes", "wrong")));
    assertThat(wrong.getStatus().getCode()).isEqualTo(401);
  }

  @Test
  void scenariosChangeBehaviourAndStatsCountIndependently() {
    client.toBlocking().exchange(HttpRequest.PUT("/__chaos?name=rate-limited-fx", ""));
    int limited = 0;
    for (int i = 0; i < 40; i++) {
      try {
        client.toBlocking().exchange("/fx/EUR/USD");
      } catch (HttpClientResponseException e) {
        assertThat(e.getStatus().getCode()).isEqualTo(429);
        assertThat(e.getResponse().header("Retry-After")).isEqualTo("2");
        limited++;
      }
    }
    assertThat(limited).isBetween(8, 32);
    var stats =
        client
            .toBlocking()
            .retrieve(HttpRequest.GET("/__stats"), Argument.mapOf(String.class, Object.class));
    assertThat(stats).containsEntry("scenario", "rate-limited-fx");
    assertThat(stats.toString()).contains("calls=40").contains("429=" + limited);
    client
        .toBlocking()
        .exchange(HttpRequest.POST("/pricing/reservations", "").header("X-Request-Id", "same"));
    client
        .toBlocking()
        .exchange(HttpRequest.POST("/pricing/reservations", "").header("X-Request-Id", "same"));
    assertThat(
            client
                .toBlocking()
                .retrieve(HttpRequest.GET("/__stats"), Argument.mapOf(String.class, Object.class)))
        .containsEntry("repeatedNonIdempotentPosts", 1);
  }

  @Test
  void customYamlAndSlowClose() {
    var applied =
        client
            .toBlocking()
            .retrieve(
                HttpRequest.PUT(
                        "/__chaos",
                        "name: one-off\nseed: 5\nendpoints:\n  stock: {latency: {kind: fixed, ms: 1}, slowClose: {rate: 1.0, pauseMs: 300}}\n")
                    .contentType(MediaType.APPLICATION_YAML_TYPE),
                Argument.mapOf(String.class, Object.class));
    assertThat(applied).containsEntry("scenario", "one-off");
    long t0 = System.nanoTime();
    String body = client.toBlocking().retrieve("/stock/sku-9");
    assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(280);
    assertThat(body).contains("\"slow\":true").endsWith("}");
    assertThat(
            client.toBlocking().retrieve(HttpRequest.PUT("/__chaos?name=nope", ""), String.class))
        .contains("error");
  }
}
