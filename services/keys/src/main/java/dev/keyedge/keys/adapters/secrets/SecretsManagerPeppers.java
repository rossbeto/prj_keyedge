package dev.keyedge.keys.adapters.secrets;

import dev.keyedge.keyformat.Pepper;
import dev.keyedge.keyformat.PepperVersion;
import dev.keyedge.keys.ports.PepperProvider;
import io.micronaut.json.JsonMapper;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Peppers from one Secrets Manager secret: {@code {"current": 2, "peppers": {"1": "<base64>", "2":
 * "<base64>"}}}, or flat, {@code {"current": 1, "1": "<base64>"}} — the shape CloudFormation can
 * generate (one random field per template, Phase 012). Rotation = add a version and move {@code
 * current}; an old version is removed only when no stored hash uses it (runbook).
 */
public final class SecretsManagerPeppers implements PepperProvider {
  private final SecretJson secrets;
  private final String secretId;

  public SecretsManagerPeppers(
      SecretsManagerClient client, JsonMapper json, String secretId, Duration refresh) {
    this.secrets = new SecretJson(client, json, refresh);
    this.secretId = secretId;
  }

  @Override
  public Pepper current() {
    Map<String, Object> doc = secrets.get(secretId);
    if (!(doc.get("current") instanceof Number number)) {
      throw new IllegalStateException("pepper secret has no numeric 'current'");
    }
    int current = number.intValue();
    return byVersion(new PepperVersion(current))
        .orElseThrow(
            () -> new IllegalStateException("current pepper version " + current + " is missing"));
  }

  @Override
  public Optional<Pepper> byVersion(PepperVersion version) {
    Map<String, Object> doc = secrets.get(secretId);
    @SuppressWarnings("unchecked")
    Map<String, Object> peppers =
        doc.get("peppers") instanceof Map<?, ?> nested
            ? (Map<String, Object>) nested
            : doc; // or flat
    return Optional.ofNullable(peppers.get(Integer.toString(version.value())))
        .filter(String.class::isInstance)
        .map(b64 -> new Pepper(version, Base64.getDecoder().decode((String) b64)));
  }
}
