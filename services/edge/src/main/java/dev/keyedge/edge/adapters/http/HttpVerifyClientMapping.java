package dev.keyedge.edge.adapters.http;

import dev.keyedge.edge.ports.Verdicts;
import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.MalformedReason;
import dev.keyedge.keyformat.RatePlan;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.keyformat.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Maps keys' verify response JSON to the shared {@link KeyCheck} (public for tests). */
public final class HttpVerifyClientMapping {
  private HttpVerifyClientMapping() {}

  @SuppressWarnings("unchecked")
  public static KeyCheck toCheck(KeyId keyId, Map<String, Object> v) {
    String verdict = String.valueOf(v.get("verdict"));
    Instant at = v.get("at") instanceof String s ? Instant.parse(s) : Instant.EPOCH;
    return switch (verdict) {
      case "VALID" -> {
        var limit = (Map<String, Object>) v.getOrDefault("rateLimit", Map.of());
        List<String> scopes = (List<String>) v.getOrDefault("scopes", List.of());
        yield new KeyCheck.Valid(
            new TenantId(String.valueOf(v.get("tenant"))),
            keyId,
            scopes.stream().map(Scope::new).collect(Collectors.toSet()),
            new RatePlan(num(limit.get("perSecond")), num(limit.get("burst"))),
            Duration.ofSeconds(num(v.get("ttlSeconds"))));
      }
      case "REVOKED" -> new KeyCheck.Revoked(keyId, at);
      case "EXPIRED" -> new KeyCheck.Expired(keyId, at);
      case "ROTATED_OUT" -> new KeyCheck.RotatedOut(keyId, at);
      case "MALFORMED" -> new KeyCheck.Malformed(MalformedReason.CHARSET);
      default -> new KeyCheck.Unknown(keyId);
    };
  }

  private static long num(@org.jspecify.annotations.Nullable Object o) {
    if (o instanceof Number n) {
      return n.longValue();
    }
    throw new Verdicts.Unavailable("verify response missing a number", null);
  }
}
