package dev.keyedge.quotes.adapters.auth;

import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/** OAuth2 client credentials: {@code POST grant_type=client_credentials} with Basic client auth. */
public final class HttpTokenEndpoint implements SingleFlightTokenProvider.TokenEndpoint {
  private final HttpClient http;
  private final URI uri;
  private final String basic;
  private final Duration timeout;
  private final JsonMapper json;

  public HttpTokenEndpoint(
      HttpClient http,
      URI uri,
      String clientId,
      String clientSecret,
      Duration timeout,
      JsonMapper json) {
    this.http = http;
    this.uri = uri;
    this.basic =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    this.timeout = timeout;
    this.json = json;
  }

  @Override
  public SingleFlightTokenProvider.Issued fetch() throws IOException, InterruptedException {
    var r =
        http.send(
            HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    if (r.statusCode() != 200) {
      throw new IOException("token endpoint answered HTTP " + r.statusCode());
    }
    Map<String, Object> body = json.readValue(r.body(), Argument.mapOf(String.class, Object.class));
    if (body == null
        || !(body.get("access_token") instanceof String token)
        || !(body.get("expires_in") instanceof Number seconds)) {
      throw new IOException("token response without access_token/expires_in");
    }
    return new SingleFlightTokenProvider.Issued(token, Duration.ofSeconds(seconds.longValue()));
  }
}
