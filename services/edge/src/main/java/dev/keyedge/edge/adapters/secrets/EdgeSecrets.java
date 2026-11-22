package dev.keyedge.edge.adapters.secrets;

import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Map;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Reads the sidecar's two secrets once at start: its caller credential and the principal-signing
 * key.
 */
public final class EdgeSecrets implements dev.keyedge.edge.ports.SecretBytes {
  private final SecretsManagerClient secrets;
  private final JsonMapper json;

  public EdgeSecrets(SecretsManagerClient secrets, JsonMapper json) {
    this.secrets = secrets;
    this.json = json;
  }

  @Override
  public byte[] bytes(String secretId, String entry) {
    try {
      Map<String, Object> doc =
          json.readValue(
              secrets.getSecretValue(b -> b.secretId(secretId)).secretString(),
              Argument.mapOf(String.class, Object.class));
      if (doc == null || !(doc.get(entry) instanceof String b64)) {
        throw new IllegalStateException("secret " + secretId + " has no entry '" + entry + "'");
      }
      return Base64.getDecoder().decode(b64);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
