package dev.keyedge.keys.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.keyedge.keyformat.InternalSignature;
import dev.keyedge.keyformat.KeyFormat;
import dev.keyedge.keyformat.ParseResult;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InternalApiTest implements TestPropertyProvider {
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

  private MutableHttpRequest<String> signed(String path, String body, String caller, Instant at) {
    String nonce = java.util.UUID.randomUUID().toString();
    String sig =
        InternalSignature.sign(
            FakesFactory.EDGE_CREDENTIAL,
            "POST",
            path,
            at,
            nonce,
            body.getBytes(StandardCharsets.UTF_8));
    return HttpRequest.POST(path, body)
        .contentType(MediaType.APPLICATION_JSON_TYPE)
        .header(InternalSignature.CALLER, caller)
        .header(InternalSignature.TIMESTAMP, Long.toString(at.getEpochSecond()))
        .header(InternalSignature.SIGNATURE, sig)
        .header(InternalSignature.NONCE, nonce);
  }

  private String fullKey() {
    var r =
        client
            .toBlocking()
            .exchange(
                HttpRequest.POST(
                        "/v1/keys", Map.of("name", "edge", "scopes", List.of("quotes:read")))
                    .header("Idempotency-Key", "k-" + System.nanoTime())
                    .bearerAuth(tokens.token("acme", "keys:write")),
                Argument.mapOf(String.class, Object.class));
    return (String) r.body().get("key");
  }

  private static String verifyBody(String fullKey) {
    var p = (ParseResult.Parsed) KeyFormat.parse(fullKey);
    return "{\"keyId\":\"" + p.keyId() + "\",\"secret\":\"" + p.secret().text() + "\"}";
  }

  @Test
  void signedVerifyReturnsTheVerdict() {
    var verdict =
        client
            .toBlocking()
            .retrieve(
                signed("/internal/v1/verify", verifyBody(fullKey()), "edge-test", Instant.now()),
                Argument.mapOf(String.class, Object.class));
    assertThat(verdict)
        .containsEntry("verdict", "VALID")
        .containsEntry("tenant", "acme")
        .containsEntry("ttlSeconds", 60);
    assertThat(String.valueOf(verdict.get("scopes"))).isEqualTo("[quotes:read]");
  }

  @Test
  void wrongSecretIsUnknownWithAShortTtl() {
    String body =
        verifyBody(fullKey())
            .replaceAll("\"secret\":\"[^\"]+\"", "\"secret\":\"" + "A".repeat(43) + "\"");
    var verdict =
        client
            .toBlocking()
            .retrieve(
                signed("/internal/v1/verify", body, "edge-test", Instant.now()),
                Argument.mapOf(String.class, Object.class));
    assertThat(verdict)
        .containsEntry("verdict", "UNKNOWN")
        .containsEntry("ttlSeconds", 5)
        .doesNotContainKey("tenant");
  }

  @Test
  void unsignedTamperedReplayedOrStaleRequestsAreRejected() {
    String body = verifyBody(fullKey());
    assertThat(
            status(
                HttpRequest.POST("/internal/v1/verify", body)
                    .contentType(MediaType.APPLICATION_JSON_TYPE)))
        .isEqualTo(401);
    var tampered = signed("/internal/v1/verify", body, "edge-test", Instant.now());
    var forged =
        HttpRequest.POST("/internal/v1/verify", body.replace("\"}", "x\"}"))
            .contentType(MediaType.APPLICATION_JSON_TYPE);
    tampered.getHeaders().forEach((name, values) -> values.forEach(v -> forged.header(name, v)));
    assertThat(status(forged)).as("body changed after signing").isEqualTo(401);
    var once = signed("/internal/v1/verify", body, "edge-test", Instant.now().minusSeconds(1));
    client.toBlocking().exchange(once);
    assertThat(status(once)).as("replay").isEqualTo(401);
    assertThat(
            status(
                signed("/internal/v1/verify", body, "edge-test", Instant.now().minusSeconds(120))))
        .as("stale")
        .isEqualTo(401);
    assertThat(status(signed("/internal/v1/verify", body, "stranger", Instant.now())))
        .as("unknown caller")
        .isEqualTo(401);
  }

  @Test
  void usageBatchesAreAcceptedAndQueryable() {
    String key = fullKey();
    var p = (ParseResult.Parsed) KeyFormat.parse(key);
    var minute = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    String batch =
        "[{\"tenant\":\"acme\",\"keyId\":\""
            + p.keyId()
            + "\",\"minute\":\""
            + minute
            + "\",\"allowed\":7,\"rejected\":2}]";
    assertThat(
            client
                .toBlocking()
                .exchange(signed("/internal/v1/usage", batch, "edge-test", Instant.now()))
                .getStatus()
                .getCode())
        .isEqualTo(202);
    var rows =
        client
            .toBlocking()
            .retrieve(
                HttpRequest.GET(
                        "/v1/usage?keyId="
                            + p.keyId()
                            + "&from="
                            + minute.minusSeconds(60)
                            + "&to="
                            + minute.plusSeconds(60))
                    .bearerAuth(tokens.token("acme", "keys:read")),
                Argument.listOf(Argument.mapOf(String.class, Object.class)));
    assertThat(rows)
        .singleElement()
        .satisfies(r -> assertThat(r).containsEntry("allowed", 7).containsEntry("rejected", 2));
  }

  @Test
  void malformedBodiesAreValidationProblems() {
    assertThat(
            status(
                signed(
                    "/internal/v1/verify",
                    "{\"keyId\":\"x\",\"secret\":\"y\"}",
                    "edge-test",
                    Instant.now())))
        .isEqualTo(400);
    assertThat(status(signed("/internal/v1/verify", "not json", "edge-test", Instant.now())))
        .isEqualTo(400);
  }

  private int status(MutableHttpRequest<?> r) {
    return assertThrows(HttpClientResponseException.class, () -> client.toBlocking().exchange(r))
        .getStatus()
        .getCode();
  }
}
