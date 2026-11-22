package dev.keyedge.keys.adapters.secrets;

import dev.keyedge.keys.ports.CallerCredentials;
import io.micronaut.json.JsonMapper;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/** Internal callers' signing credentials: {@code {"<callerId>": "<base64>"}} in one secret. */
public final class SecretsManagerCallers implements CallerCredentials {
  private final SecretJson secrets;
  private final String secretId;

  public SecretsManagerCallers(
      SecretsManagerClient client, JsonMapper json, String secretId, Duration refresh) {
    this.secrets = new SecretJson(client, json, refresh);
    this.secretId = secretId;
  }

  @Override
  public Optional<byte[]> credential(String callerId) {
    return Optional.ofNullable(secrets.get(secretId).get(callerId))
        .map(b64 -> Base64.getDecoder().decode((String) b64));
  }
}
