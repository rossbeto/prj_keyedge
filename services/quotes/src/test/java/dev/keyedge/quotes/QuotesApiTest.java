package dev.keyedge.quotes;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.PrincipalHeader;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.keyformat.TenantId;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The real quotes app against fake upstreams: the blocking endpoint and its Reactor twin must
 * answer the same status and body in every scenario (parity), plus problem bodies, the trust
 * boundary and the never-retried reservation.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuotesApiTest implements TestPropertyProvider {
  static final byte[] KEY = PrincipalFilterTest.KEY;
  final FakeUpstreams up = new FakeUpstreams();

  // the principal key comes from PrincipalFilterTest.TestKey (same KEY)

  @Inject
  @Client("/")
  HttpClient client;

  @Override
  public Map<String, String> getProperties() {
    return Map.of(
        "keyedge.quotes.pricing-url",
        up.url(),
        "keyedge.quotes.stock-url",
        up.url(),
        "keyedge.quotes.fx-url",
        up.url(),
        "keyedge.quotes.token-url",
        up.url() + "/oauth/token",
        "keyedge.quotes.breaker-minimum-calls",
        "1000");
  }

  @AfterAll
  void stop() {
    up.close();
  }

  @AfterEach
  void heal() {
    up.heal();
  }

  static io.micronaut.http.MutableHttpRequest<Object> signed(String method, String path) {
    var principal =
        new PrincipalHeader.Principal(
            new TenantId("acme"), new KeyId(1, 2), Set.of(new Scope("quotes:read")));
    var s =
        PrincipalHeader.sign(KEY, principal, Instant.now(), method, path.replaceAll("\\?.*", ""));
    return HttpRequest.create(io.micronaut.http.HttpMethod.valueOf(method), path)
        .header(PrincipalHeader.PRINCIPAL, s.principal())
        .header(PrincipalHeader.SIGNATURE, s.signature());
  }

  record Answer(int status, String body) {}

  Answer call(String path) {
    try {
      HttpResponse<String> r = client.toBlocking().exchange(signed("GET", path), String.class);
      return new Answer(r.code(), r.body());
    } catch (HttpClientResponseException e) {
      return new Answer(e.getStatus().getCode(), e.getResponse().getBody(String.class).orElse(""));
    }
  }

  static Stream<Arguments> scenarios() {
    return Stream.of(
        Arguments.of("normal", (Consumer<FakeUpstreams>) u -> {}, 200, "\"currency\":\"USD\""),
        Arguments.of(
            "fx-down", (Consumer<FakeUpstreams>) u -> u.fxStatus = 503, 200, "FX_UNAVAILABLE"),
        Arguments.of(
            "pricing-500",
            (Consumer<FakeUpstreams>) u -> u.pricingStatus = 500,
            503,
            "pricing failed"),
        Arguments.of(
            "stock-slow",
            (Consumer<FakeUpstreams>) u -> u.stockDelayMs = 2_000,
            503,
            "stock timed out"),
        Arguments.of(
            "stock-404", (Consumer<FakeUpstreams>) u -> u.stockStatus = 404, 503, "stock failed"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scenarios")
  void blockingAndReactiveAnswerTheSame(
      String name, Consumer<FakeUpstreams> scenario, int status, String contains) {
    scenario.accept(up);
    var blocking = call("/v1/quotes/sku-1?qty=3&currency=USD");
    var reactive = call("/v1/quotes-reactive/sku-1?qty=3&currency=USD");
    assertThat(blocking.status()).as(blocking.body()).isEqualTo(status);
    assertThat(blocking.body()).contains(contains);
    assertThat(reactive).isEqualTo(blocking);
  }

  @Test
  void aPricedQuote() {
    var a = call("/v1/quotes/sku-9?qty=10&currency=USD");
    assertThat(a.body())
        .contains("\"unitPriceCents\":1357", "\"totalCents\":13570", "\"available\":7")
        .contains("LIMITED_STOCK", "\"tenant\":\"acme\"");
  }

  @Test
  void tokenExpiryIsInvisibleToClients() throws Exception {
    up.tokenLifetimeSeconds = 1;
    call("/v1/quotes/sku-1");
    Thread.sleep(1_100);
    assertThat(call("/v1/quotes/sku-1").status()).isEqualTo(200);
    assertThat(call("/v1/quotes-reactive/sku-1").status()).isEqualTo(200);
  }

  @Test
  void invalidInputIs400() {
    assertThat(call("/v1/quotes/sku-1?qty=0").body()).contains("validation");
    assertThat(call("/v1/quotes-reactive/sku-1?currency=usd").status()).isEqualTo(400);
    assertThat(call("/v1/quotes/bad%20sku").status()).isIn(400, 404);
  }

  @Test
  void reservationsAreNeverRetried() {
    up.reserveStatus = 503;
    long before = up.calls("reserve");
    HttpClientResponseException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            HttpClientResponseException.class,
            () ->
                client
                    .toBlocking()
                    .exchange(
                        signed("POST", "/v1/quotes/sku-1/reserve?qty=2").body(""), String.class));
    assertThat(e.getStatus().getCode()).isEqualTo(503);
    assertThat(up.calls("reserve") - before).isEqualTo(1);
    up.reserveStatus = 201;
    var ok =
        client
            .toBlocking()
            .exchange(signed("POST", "/v1/quotes/sku-1/reserve?qty=2").body(""), String.class);
    assertThat(ok.code()).isEqualTo(201);
  }

  @Test
  void idempotentReadsAreRetried() {
    up.stockStatus = 503;
    long before = up.calls("stock");
    call("/v1/quotes/sku-1");
    assertThat(up.calls("stock") - before).isEqualTo(2); // stock: 2 attempts
  }
}
