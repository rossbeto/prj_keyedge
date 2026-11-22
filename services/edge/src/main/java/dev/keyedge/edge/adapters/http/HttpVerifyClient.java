package dev.keyedge.edge.adapters.http;

import dev.keyedge.edge.ports.Verdicts;
import dev.keyedge.edge.ports.VerifyClient;
import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.KeySecret;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Map;

/**
 * {@code POST /internal/v1/verify {keyId, secret}} → {@link KeyCheck}. The secret is never logged.
 */
public final class HttpVerifyClient implements VerifyClient {
  private final SignedJsonClient client;
  private final JsonMapper json;

  public HttpVerifyClient(
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
  public KeyCheck verify(KeyId keyId, KeySecret secret) {
    try {
      String body =
          json.writeValueAsString(Map.of("keyId", keyId.toString(), "secret", secret.text()));
      var response = client.post("/internal/v1/verify", body);
      int status = response.statusCode();
      if (status != 200) {
        // a 4xx is our bug (signature, contract), not "keys is down": the breaker ignores it
        throw new Verdicts.Unavailable(
            "verify answered HTTP " + status,
            null,
            status >= 400 && status < 500
                ? Verdicts.Unavailable.Reason.CONTRACT
                : Verdicts.Unavailable.Reason.DOWN,
            null);
      }
      Map<String, Object> v =
          json.readValue(response.body(), Argument.mapOf(String.class, Object.class));
      if (v == null) {
        throw new Verdicts.Unavailable("empty verify response", null);
      }
      return HttpVerifyClientMapping.toCheck(keyId, v);
    } catch (IOException e) {
      throw new Verdicts.Unavailable("verify call failed: " + e.getClass().getSimpleName(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Verdicts.Unavailable("interrupted", e);
    }
  }
}
