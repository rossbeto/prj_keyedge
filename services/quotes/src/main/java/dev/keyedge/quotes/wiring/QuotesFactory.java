package dev.keyedge.quotes.wiring;

import dev.keyedge.awstarget.AwsTargetConfigurer;
import dev.keyedge.awstarget.AwsTargets;
import dev.keyedge.quotes.api.PrincipalFilter;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.InstantSource;
import java.util.Base64;
import java.util.Map;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/** Composition root of quotes (Phase 007 part). */
@Factory
public class QuotesFactory {
  @Singleton
  @Primary
  InstantSource clock() {
    return InstantSource.system();
  }

  @Singleton
  PrincipalFilter.PrincipalKey principalKey(
      JsonMapper json,
      @Value("${keyedge.quotes.principal-secret:keyedge/principal}") String secretId) {
    try (var secrets =
        AwsTargetConfigurer.configure(SecretsManagerClient.builder(), AwsTargets.fromEnvironment())
            .build()) {
      Map<String, Object> doc =
          json.readValue(
              secrets.getSecretValue(b -> b.secretId(secretId)).secretString(),
              Argument.mapOf(String.class, Object.class));
      if (doc == null || !(doc.get("key") instanceof String b64)) {
        throw new IllegalStateException("secret " + secretId + " has no 'key'");
      }
      return new PrincipalFilter.PrincipalKey(Base64.getDecoder().decode(b64));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
