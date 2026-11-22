package dev.keyedge.keys.api;

import dev.keyedge.keys.adapters.dynamodb.DynamoKeys;
import dev.keyedge.keys.adapters.dynamodb.DynamoUsage;
import dev.keyedge.keys.adapters.secrets.SecretsManagerCallers;
import dev.keyedge.keys.adapters.secrets.SecretsManagerPeppers;
import dev.keyedge.keys.fakes.FixedPeppers;
import dev.keyedge.keys.fakes.InMemoryKeys;
import dev.keyedge.keys.fakes.InMemoryUsage;
import dev.keyedge.keys.ports.CallerCredentials;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/** API tests run the real controllers and use cases on fakes: no AWS, no network. */
@Factory
@Requires(env = "test")
@Requires(property = "keyedge.test.fakes", value = "true")
class FakesFactory {
  static final byte[] EDGE_CREDENTIAL =
      "edge-test-credential-32-bytes-xx".getBytes(StandardCharsets.UTF_8);

  @Singleton
  @Replaces(DynamoKeys.class)
  InMemoryKeys keys() {
    return new InMemoryKeys(Duration.ofSeconds(30));
  }

  @Singleton
  @Replaces(DynamoUsage.class)
  InMemoryUsage usage() {
    return new InMemoryUsage();
  }

  @Singleton
  @Replaces(SecretsManagerPeppers.class)
  FixedPeppers peppers() {
    return new FixedPeppers();
  }

  @Singleton
  @Replaces(SecretsManagerCallers.class)
  CallerCredentials callers() {
    return id -> "edge-test".equals(id) ? Optional.of(EDGE_CREDENTIAL) : Optional.empty();
  }
}
