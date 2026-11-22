package dev.keyedge.edge.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.keyedge.edge.adapters.http.HttpVerifyClient;
import dev.keyedge.edge.ports.Verdicts;
import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.KeySecret;
import io.micronaut.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpVerifyClientTest {
  private static final KeyId ID = new KeyId(1, 1);

  @Test
  void mapsEveryVerdict() {
    var at = "2026-11-23T10:00:00Z";
    assertThat(
            HttpVerifyClientAccess.toCheck(
                ID,
                Map.of(
                    "verdict",
                    "VALID",
                    "tenant",
                    "acme",
                    "scopes",
                    List.of("quotes:read"),
                    "rateLimit",
                    Map.of("perSecond", 5, "burst", 10),
                    "ttlSeconds",
                    60)))
        .isInstanceOfSatisfying(
            KeyCheck.Valid.class,
            v -> {
              assertThat(v.plan().burst()).isEqualTo(10);
              assertThat(v.ttlHint()).isEqualTo(Duration.ofSeconds(60));
            });
    assertThat(HttpVerifyClientAccess.toCheck(ID, Map.of("verdict", "REVOKED", "at", at)))
        .isEqualTo(new KeyCheck.Revoked(ID, Instant.parse(at)));
    assertThat(HttpVerifyClientAccess.toCheck(ID, Map.of("verdict", "EXPIRED", "at", at)))
        .isInstanceOf(KeyCheck.Expired.class);
    assertThat(HttpVerifyClientAccess.toCheck(ID, Map.of("verdict", "ROTATED_OUT", "at", at)))
        .isInstanceOf(KeyCheck.RotatedOut.class);
    assertThat(HttpVerifyClientAccess.toCheck(ID, Map.of("verdict", "MALFORMED")))
        .isInstanceOf(KeyCheck.Malformed.class);
    assertThat(HttpVerifyClientAccess.toCheck(ID, Map.of("verdict", "UNKNOWN")))
        .isEqualTo(new KeyCheck.Unknown(ID));
    assertThatThrownBy(
            () -> HttpVerifyClientAccess.toCheck(ID, Map.of("verdict", "VALID", "tenant", "acme")))
        .isInstanceOf(Verdicts.Unavailable.class);
  }

  @Test
  void connectionFailuresAreUnavailable() {
    var client =
        new HttpVerifyClient(
            HttpClient.newHttpClient(),
            URI.create("http://127.0.0.1:1"),
            "edge",
            new byte[32],
            Instant::now,
            Duration.ofMillis(500),
            JsonMapper.createDefault());
    assertThatThrownBy(() -> client.verify(ID, KeySecret.ofBytes(new byte[32])))
        .isInstanceOf(Verdicts.Unavailable.class);
  }
}
