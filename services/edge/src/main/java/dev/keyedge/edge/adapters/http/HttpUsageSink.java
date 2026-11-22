package dev.keyedge.edge.adapters.http;

import dev.keyedge.edge.domain.UsageCount;
import dev.keyedge.edge.ports.UsageSink;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;

/** {@code POST /internal/v1/usage} with the aggregated counts (signed like verify). */
public final class HttpUsageSink implements UsageSink {
  private final SignedJsonClient client;
  private final JsonMapper json;

  public HttpUsageSink(
      HttpClient http,
      URI keys,
      String caller,
      byte[] credential,
      InstantSource clock,
      Duration timeout,
      JsonMapper json) {
    this.client = new SignedJsonClient(http, keys, caller, credential, clock, timeout);
    this.json = json;
  }

  @Override
  public void send(List<UsageCount> counts) {
    try {
      var rows =
          counts.stream()
              .map(c ->
                  Map.<String, Object>of(
                      "tenant",
                      c.tenant().value(),
                      "keyId",
                      c.keyId().toString(),
                      "minute",
                      c.minute().toString(),
                      "allowed",
                      c.allowed(),
                      "rejected",
                      c.rejected()))
              .toList();
      var response = client.post("/internal/v1/usage", json.writeValueAsString(rows));
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException("usage answered HTTP " + response.statusCode());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }
}
