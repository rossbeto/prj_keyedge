package dev.keyedge.keys.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeysApiTest implements TestPropertyProvider {
  private final TestTokens tokens = new TestTokens();

  @Inject
  @Client("/")
  HttpClient client;

  @Override
  public Map<String, String> getProperties() {
    return Map.of(
        "keyedge.test.fakes", "true",
        "micronaut.security.token.jwt.signatures.jwks-static.test.path", "file:" + tokens.jwks,
        "micronaut.security.token.jwt.claims-validators.issuer", "keyedge-issuer");
  }

  private <T> MutableHttpRequest<T> auth(MutableHttpRequest<T> r, String tenant, String scope) {
    return r.bearerAuth(tokens.token(tenant, scope));
  }

  private HttpResponse<Map<String, Object>> exchange(MutableHttpRequest<?> r) {
    return client.toBlocking().exchange(r, Argument.mapOf(String.class, Object.class));
  }

  private HttpClientResponseException problem(MutableHttpRequest<?> r) {
    var e = assertThrows(HttpClientResponseException.class, () -> exchange(r));
    assertThat(e.getResponse().getContentType().map(Object::toString))
        .contains("application/problem+json");
    return e;
  }

  private static String type(HttpClientResponseException e) {
    @SuppressWarnings("unchecked")
    Map<String, Object> body = e.getResponse().getBody(Map.class).orElseThrow();
    return String.valueOf(body.get("type"));
  }

  private HttpResponse<Map<String, Object>> create(String tenant, String idem, String name) {
    return exchange(
        auth(
            HttpRequest.POST("/v1/keys", Map.of("name", name, "scopes", List.of("quotes:read")))
                .header("Idempotency-Key", idem),
            tenant,
            "keys:write keys:read"));
  }

  @Test
  void createReturnsTheKeyOnceWithLocationAndEtag() {
    var r = create("acme", "c-1", "ci");
    assertThat(r.getStatus().getCode()).isEqualTo(201);
    assertThat(r.header("Location")).startsWith("/v1/keys/");
    assertThat(r.header("ETag")).isEqualTo("\"v1\"");
    var body = r.body();
    assertThat((String) body.get("key")).startsWith("ke_test_").hasSize(85);
    @SuppressWarnings("unchecked")
    var meta = (Map<String, Object>) body.get("metadata");
    assertThat(meta)
        .containsEntry("status", "ACTIVE")
        .containsEntry("name", "ci")
        .doesNotContainKey("hash");
  }

  @Test
  void replayAndMismatch() {
    var first = create("acme", "c-2", "a");
    var replay = create("acme", "c-2", "a");
    assertThat(replay.getStatus().getCode()).isEqualTo(201);
    assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.body())
        .containsEntry("keyId", first.body().get("keyId"))
        .doesNotContainKey("key"); // shown once only
    var mismatch =
        problem(
            auth(
                HttpRequest.POST("/v1/keys", Map.of("name", "b")).header("Idempotency-Key", "c-2"),
                "acme",
                "keys:write"));
    assertThat(mismatch.getStatus().getCode()).isEqualTo(422);
    assertThat(type(mismatch)).isEqualTo("urn:keyedge:problem:idempotency-mismatch");
  }

  @Test
  void authenticationAndScopes() {
    assertThat(
            assertThrows(
                    HttpClientResponseException.class, () -> exchange(HttpRequest.GET("/v1/keys")))
                .getStatus()
                .getCode())
        .isEqualTo(401);
    var noScope = problem(auth(HttpRequest.GET("/v1/keys"), "acme", "quotes:read"));
    assertThat(noScope.getStatus().getCode()).isEqualTo(403);
    assertThat(type(noScope)).isEqualTo("urn:keyedge:problem:missing-scope");
    var wrongIssuer =
        assertThrows(
            HttpClientResponseException.class,
            () ->
                exchange(
                    HttpRequest.GET("/v1/keys")
                        .bearerAuth(tokens.token("evil", "acme", "keys:read"))));
    assertThat(wrongIssuer.getStatus().getCode()).isEqualTo(401);
  }

  @Test
  void validation() {
    var noIdem =
        problem(auth(HttpRequest.POST("/v1/keys", Map.of("name", "x")), "acme", "keys:write"));
    assertThat(noIdem.getStatus().getCode()).isEqualTo(400);
    var badScope =
        problem(
            auth(
                HttpRequest.POST("/v1/keys", Map.of("name", "x", "scopes", List.of("nope")))
                    .header("Idempotency-Key", "v-1"),
                "acme",
                "keys:write"));
    assertThat(type(badScope)).isEqualTo("urn:keyedge:problem:validation");
    var past =
        problem(
            auth(
                HttpRequest.POST(
                        "/v1/keys", Map.of("name", "x", "expiresAt", Instant.EPOCH.toString()))
                    .header("Idempotency-Key", "v-2"),
                "acme",
                "keys:write"));
    assertThat(past.getStatus().getCode()).isEqualTo(400);
  }

  @Test
  void getWithEtagAndTenantIsolation() {
    String id = (String) create("acme", "g-1", "g").body().get("keyId");
    var get = exchange(auth(HttpRequest.GET("/v1/keys/" + id), "acme", "keys:read"));
    assertThat(get.header("ETag")).isEqualTo("\"v1\"");
    var notModified =
        exchange(
            auth(
                HttpRequest.GET("/v1/keys/" + id).header("If-None-Match", "\"v1\""),
                "acme",
                "keys:read"));
    assertThat(notModified.getStatus().getCode()).isEqualTo(304);
    assertThat(
            problem(auth(HttpRequest.GET("/v1/keys/" + id), "globex", "keys:read"))
                .getStatus()
                .getCode())
        .isEqualTo(404);
    assertThat(
            problem(auth(HttpRequest.GET("/v1/keys/not-an-id"), "acme", "keys:read"))
                .getStatus()
                .getCode())
        .isEqualTo(404);
  }

  @Test
  void rotateThenRevoke() {
    var created = create("acme", "r-1", "r");
    String id = (String) created.body().get("keyId");
    var rotated =
        exchange(
            auth(
                HttpRequest.POST("/v1/keys/" + id + "/rotate", Map.of("graceSeconds", 3600)),
                "acme",
                "keys:write"));
    assertThat((String) rotated.body().get("key"))
        .startsWith("ke_test_")
        .isNotEqualTo(created.body().get("key"));
    @SuppressWarnings("unchecked")
    var meta = (Map<String, Object>) rotated.body().get("metadata");
    assertThat(meta).containsEntry("status", "ROTATING").containsKey("previousSecretRetiresAt");
    var revoked =
        exchange(auth(HttpRequest.POST("/v1/keys/" + id + "/revoke", ""), "acme", "keys:write"));
    assertThat(revoked.body()).containsEntry("status", "REVOKED");
    assertThat(revoked.header("ETag")).isEqualTo("\"v3\"");
    var again =
        problem(
            auth(HttpRequest.POST("/v1/keys/" + id + "/rotate", Map.of()), "acme", "keys:write"));
    assertThat(again.getStatus().getCode()).isEqualTo(400);
    var tooLong =
        problem(
            auth(
                HttpRequest.POST(
                    "/v1/keys/" + created.body().get("keyId") + "/rotate",
                    Map.of("graceSeconds", 999_999_999)),
                "acme",
                "keys:write"));
    assertThat(tooLong.getStatus().getCode()).isEqualTo(400);
  }

  @Test
  void listPages() {
    for (int i = 0; i < 3; i++) {
      create("listing", "l-" + i, "k" + i);
    }
    var page = exchange(auth(HttpRequest.GET("/v1/keys?limit=2"), "listing", "keys:read"));
    assertThat((List<?>) page.body().get("items")).hasSize(2);
    var next =
        exchange(
            auth(
                HttpRequest.GET("/v1/keys?limit=2&cursor=" + page.body().get("next")),
                "listing",
                "keys:read"));
    assertThat((List<?>) next.body().get("items")).hasSize(1);
    assertThat(next.body().get("next")).isNull();
  }

  @Test
  void usageQueryValidatesRangeAndOwnership() {
    String id = (String) create("acme", "u-1", "u").body().get("keyId");
    var from = Instant.now().minusSeconds(3600);
    var ok =
        client
            .toBlocking()
            .exchange(
                auth(
                    HttpRequest.GET(
                        "/v1/usage?keyId=" + id + "&from=" + from + "&to=" + Instant.now()),
                    "acme",
                    "keys:read"),
                Argument.listOf(Map.class));
    assertThat(ok.getStatus().getCode()).isEqualTo(200);
    assertThat(
            problem(
                    auth(
                        HttpRequest.GET(
                            "/v1/usage?keyId=" + id + "&from=" + Instant.now() + "&to=" + from),
                        "acme",
                        "keys:read"))
                .getStatus()
                .getCode())
        .isEqualTo(400);
    assertThat(
            problem(
                    auth(
                        HttpRequest.GET(
                            "/v1/usage?keyId=" + id + "&from=" + from + "&to=" + Instant.now()),
                        "globex",
                        "keys:read"))
                .getStatus()
                .getCode())
        .isEqualTo(404);
  }
}
