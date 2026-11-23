package dev.keyedge.bench.app;

import java.util.List;

/**
 * Credentials for the load: API keys for the edge, and the principal the edge would assert for the
 * direct path. Never written to results or logs ({@link #toString()} is redacted).
 *
 * @param principalId {@code tenant|keyId|scopes}
 * @param principalKey base64 HMAC key shared by the edge and the app
 */
public record Auth(List<String> keys, String principalId, String principalKey) {
  public Auth {
    keys = List.copyOf(keys);
  }

  @Override
  public String toString() {
    return "Auth[" + keys.size() + " keys, principal " + principalId + ", key redacted]";
  }
}
