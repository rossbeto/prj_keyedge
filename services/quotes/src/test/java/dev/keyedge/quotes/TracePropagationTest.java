package dev.keyedge.quotes;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.PrincipalHeader;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.keyformat.TenantId;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * One trace per request (Phase 011): the caller's {@code traceparent} reaches all three upstreams
 * with the same trace id and a new parent span (quotes' client spans), through the structured
 * fan-out (forks do not inherit OpenTelemetry's ThreadLocal context: the carrier does it) and the
 * Reactor twin. {@link TracePropagationFutureTest} covers the futures adapter.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracePropagationTest implements TestPropertyProvider {
  static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  static final String CALLER_SPAN = "00f067aa0ba902b7";
  final FakeUpstreams up = new FakeUpstreams();

  @Inject
  @Client("/")
  HttpClient client;

  String mode() {
    return "STRUCTURED";
  }

  @Override
  public Map<String, String> getProperties() {
    var p = new HashMap<String, String>();
    p.put("keyedge.quotes.pricing-url", up.url());
    p.put("keyedge.quotes.stock-url", up.url());
    p.put("keyedge.quotes.fx-url", up.url());
    p.put("keyedge.quotes.token-url", up.url() + "/oauth/token");
    p.put("keyedge.quotes.fanout", mode());
    return p;
  }

  @AfterAll
  void stop() {
    up.close();
  }

  void assertOneTrace(String path) {
    up.traceparents.clear();
    var principal =
        new PrincipalHeader.Principal(
            new TenantId("acme"), new KeyId(1, 2), Set.of(new Scope("quotes:read")));
    var s =
        PrincipalHeader.sign(
            PrincipalFilterTest.KEY, principal, Instant.now(), "GET", path.replaceAll("\\?.*", ""));
    client
        .toBlocking()
        .exchange(
            HttpRequest.GET(path)
                .header(PrincipalHeader.PRINCIPAL, s.principal())
                .header(PrincipalHeader.SIGNATURE, s.signature())
                .header("traceparent", "00-" + TRACE_ID + "-" + CALLER_SPAN + "-01"),
            String.class);
    assertThat(up.traceparents).containsOnlyKeys("pricing", "stock", "fx");
    for (String tp : List.copyOf(up.traceparents.values())) {
      String[] parts = tp.split("-");
      assertThat(parts[1]).as(tp).isEqualTo(TRACE_ID);
      assertThat(parts[2]).as("a child span, not the caller's").isNotEqualTo(CALLER_SPAN);
    }
  }

  @Test
  void blockingPathKeepsOneTrace() {
    assertOneTrace("/v1/quotes/sku-1?qty=1&currency=USD");
  }

  @Test
  void reactorTwinKeepsOneTrace() {
    assertOneTrace("/v1/quotes-reactive/sku-1?qty=1&currency=USD");
  }
}
