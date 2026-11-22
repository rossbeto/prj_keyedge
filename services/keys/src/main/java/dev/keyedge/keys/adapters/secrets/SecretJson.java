package dev.keyedge.keys.adapters.secrets;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Reads a JSON secret, cached; a refresh failure keeps serving the last good value until expiry.
 */
final class SecretJson {
  private final LoadingCache<String, Map<String, Object>> cache;

  SecretJson(SecretsManagerClient secrets, JsonMapper json, Duration refresh) {
    this.cache =
        Caffeine.newBuilder()
            .refreshAfterWrite(refresh)
            .expireAfterWrite(refresh.multipliedBy(6))
            .build(id -> parse(json, secrets.getSecretValue(b -> b.secretId(id)).secretString()));
  }

  private static Map<String, Object> parse(JsonMapper json, String text) {
    try {
      Map<String, Object> doc = json.readValue(text, Argument.mapOf(String.class, Object.class));
      if (doc == null) {
        throw new IllegalStateException("secret is empty");
      }
      return doc;
    } catch (IOException e) {
      throw new UncheckedIOException("secret is not valid JSON", e);
    }
  }

  Map<String, Object> get(String secretId) {
    return cache.get(secretId);
  }
}
