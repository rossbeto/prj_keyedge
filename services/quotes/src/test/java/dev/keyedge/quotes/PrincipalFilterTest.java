package dev.keyedge.quotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.PrincipalHeader;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.keyformat.TenantId;
import dev.keyedge.quotes.api.PrincipalFilter;
import dev.keyedge.quotes.wiring.QuotesFactory;
import dev.keyedge.testkit.KeyedgeArchitectureRules;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

@MicronautTest
class PrincipalFilterTest {
  static final byte[] KEY = testKey();

  /** A fixed 32-byte test key (a pattern, not a secret). */
  private static byte[] testKey() {
    byte[] k = new byte[32];
    java.util.Arrays.fill(k, (byte) 7);
    return k;
  }

  @Factory
  static class TestKey {
    @Singleton
    @Replaces(value = PrincipalFilter.PrincipalKey.class, factory = QuotesFactory.class)
    PrincipalFilter.PrincipalKey key() {
      return new PrincipalFilter.PrincipalKey(KEY);
    }
  }

  @Inject
  @Client("/")
  HttpClient client;

  private final PrincipalHeader.Principal principal =
      new PrincipalHeader.Principal(
          new TenantId("acme"), new KeyId(1, 2), Set.of(new Scope("quotes:read")));

  @Test
  void signedRequestsReachTheControllerWithThePrincipal() {
    var s = PrincipalHeader.sign(KEY, principal, Instant.now(), "GET", "/v1/quotes/sku-1");
    var body =
        client
            .toBlocking()
            .retrieve(
                HttpRequest.GET("/v1/quotes/sku-1")
                    .header(PrincipalHeader.PRINCIPAL, s.principal())
                    .header(PrincipalHeader.SIGNATURE, s.signature()),
                Argument.mapOf(String.class, Object.class));
    assertThat(body).containsEntry("tenant", "acme").containsEntry("sku", "sku-1");
  }

  @Test
  void unsignedOrReplayedOnAnotherPathIsRejected() {
    assertThat(
            assertThrows(
                    HttpClientResponseException.class,
                    () -> client.toBlocking().exchange("/v1/quotes/sku-1"))
                .getStatus()
                .getCode())
        .isEqualTo(401);
    var s = PrincipalHeader.sign(KEY, principal, Instant.now(), "GET", "/v1/quotes/other");
    var e =
        assertThrows(
            HttpClientResponseException.class,
            () ->
                client
                    .toBlocking()
                    .exchange(
                        HttpRequest.GET("/v1/quotes/sku-1")
                            .header(PrincipalHeader.PRINCIPAL, s.principal())
                            .header(PrincipalHeader.SIGNATURE, s.signature())));
    assertThat(e.getStatus().getCode()).isEqualTo(401);
    assertThat(e.getResponse().getBody(Map.class)).isPresent();
  }

  @Test
  void followsTheLayeringRules() {
    KeyedgeArchitectureRules.checkAll("dev.keyedge.quotes");
  }
}
