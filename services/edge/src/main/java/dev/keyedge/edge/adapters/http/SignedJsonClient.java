package dev.keyedge.edge.adapters.http;

import dev.keyedge.keyformat.InternalSignature;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.InstantSource;
import java.util.UUID;

/**
 * POSTs JSON to the control plane's internal API, HMAC-signed with a nonce (D-25). Blocking on the
 * caller's virtual thread; the JDK client honours interrupts and its own request timeout.
 */
final class SignedJsonClient {
  private final HttpClient http;
  private final URI base;
  private final String caller;
  private final byte[] credential;
  private final InstantSource clock;
  private final Duration timeout;

  SignedJsonClient(
      HttpClient http,
      URI base,
      String caller,
      byte[] credential,
      InstantSource clock,
      Duration timeout) {
    this.http = http;
    this.base = base;
    this.caller = caller;
    this.credential = credential.clone();
    this.clock = clock;
    this.timeout = timeout;
  }

  /** Response status and body; IOExceptions and interrupts propagate. */
  HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    var at = clock.instant();
    String nonce = UUID.randomUUID().toString();
    String signature = InternalSignature.sign(credential, "POST", path, at, nonce, body);
    var request =
        HttpRequest.newBuilder(base.resolve(path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header(InternalSignature.CALLER, caller)
            .header(InternalSignature.TIMESTAMP, Long.toString(at.getEpochSecond()))
            .header(InternalSignature.NONCE, nonce)
            .header(InternalSignature.SIGNATURE, signature)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
