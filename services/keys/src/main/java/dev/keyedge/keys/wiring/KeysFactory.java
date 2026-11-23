package dev.keyedge.keys.wiring;

import dev.keyedge.awstarget.AwsTarget;
import dev.keyedge.awstarget.AwsTargetConfigurer;
import dev.keyedge.awstarget.AwsTargets;
import dev.keyedge.awstarget.ServiceContract;
import dev.keyedge.keyformat.KeyEnvironment;
import dev.keyedge.keyformat.KeyIds;
import dev.keyedge.keyformat.RatePlan;
import dev.keyedge.keyformat.SecretHasher;
import dev.keyedge.keys.adapters.dynamodb.DynamoKeys;
import dev.keyedge.keys.adapters.dynamodb.DynamoUsage;
import dev.keyedge.keys.adapters.secrets.SecretsManagerCallers;
import dev.keyedge.keys.adapters.secrets.SecretsManagerPeppers;
import dev.keyedge.keys.api.ApiSettings;
import dev.keyedge.keys.api.Callers;
import dev.keyedge.keys.api.InternalAuth;
import dev.keyedge.keys.app.IssueKey;
import dev.keyedge.keys.app.KeyMaterial;
import dev.keyedge.keys.app.ManageKeys;
import dev.keyedge.keys.app.RecordUsage;
import dev.keyedge.keys.app.VerifyKey;
import dev.keyedge.keys.domain.VerifyPolicy;
import dev.keyedge.keys.ports.CallerCredentials;
import dev.keyedge.keys.ports.Idempotency;
import dev.keyedge.keys.ports.KeyRepository;
import dev.keyedge.keys.ports.PepperProvider;
import dev.keyedge.keys.ports.UsageStore;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.security.SecureRandom;
import java.time.InstantSource;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * Composition root: the only class that knows concrete adapters. Use cases are plain classes built
 * here (no annotations), AWS clients go through {@link AwsTargetConfigurer} (KEYEDGE_AWS_TARGET).
 */
@Factory
public class KeysFactory {
  /**
   * Primary: Micronaut 5 also exposes a {@code Clock} bean, which is an {@code InstantSource} too.
   */
  @Singleton
  @io.micronaut.context.annotation.Primary
  InstantSource clock() {
    return InstantSource.system();
  }

  @Singleton
  AwsTarget awsTarget() {
    return AwsTargets.fromEnvironment();
  }

  /**
   * The SSM contract written by the CDK stacks (D-34); none without KEYEDGE_CONTRACT_PATH (tests).
   */
  @Singleton
  ServiceContract contract(
      AwsTarget target,
      @io.micronaut.context.annotation.Value("${keyedge.contract.path:}") String path) {
    if (path.isBlank()) {
      return ServiceContract.none();
    }
    try (var ssm = AwsTargetConfigurer.configure(SsmClient.builder(), target).build()) {
      var contract = ServiceContract.load(ssm, path);
      org.slf4j.LoggerFactory.getLogger("dev.keyedge.contract")
          .info("names from {}", contract.source());
      return contract;
    }
  }

  @Singleton
  @Bean(preDestroy = "close")
  DynamoDbClient dynamo(AwsTarget target) {
    return AwsTargetConfigurer.configure(DynamoDbClient.builder(), target).build();
  }

  @Singleton
  @Bean(preDestroy = "close")
  SecretsManagerClient secrets(AwsTarget target) {
    return AwsTargetConfigurer.configure(SecretsManagerClient.builder(), target).build();
  }

  @Singleton
  DynamoKeys dynamoKeys(DynamoDbClient dynamo, KeysConfig cfg, ServiceContract contract) {
    return new DynamoKeys(
        dynamo,
        contract.name(ServiceContract.TABLE_NAME, cfg.table()),
        cfg.idempotencyInFlight(),
        cfg.idempotencyRetention());
  }

  @Singleton
  DynamoUsage dynamoUsage(DynamoDbClient dynamo, KeysConfig cfg, ServiceContract contract) {
    return new DynamoUsage(
        dynamo, contract.name(ServiceContract.TABLE_NAME, cfg.table()), cfg.usageRetention());
  }

  @Singleton
  SecretsManagerPeppers peppers(
      SecretsManagerClient secrets, JsonMapper json, KeysConfig cfg, ServiceContract contract) {
    return new SecretsManagerPeppers(
        secrets,
        json,
        contract.name(ServiceContract.PEPPER_SECRET, cfg.pepperSecret()),
        cfg.secretRefresh());
  }

  @Singleton
  SecretsManagerCallers callers(
      SecretsManagerClient secrets, JsonMapper json, KeysConfig cfg, ServiceContract contract) {
    return new SecretsManagerCallers(
        secrets,
        json,
        contract.name(ServiceContract.CALLER_SECRET, cfg.callerSecret()),
        cfg.secretRefresh());
  }

  @Singleton
  KeyMaterial material(InstantSource clock, PepperProvider peppers, KeysConfig cfg) {
    var random = new SecureRandom();
    var env =
        KeyEnvironment.ofLabel(cfg.environment())
            .orElseThrow(() ->
                new IllegalArgumentException("keyedge.keys.environment must be live or test"));
    return new KeyMaterial(new KeyIds(clock, random), random, new SecretHasher(), peppers, env);
  }

  @Singleton
  IssueKey issueKey(
      Idempotency idempotency, KeyMaterial material, InstantSource clock, KeysConfig cfg) {
    return new IssueKey(
        idempotency, material, clock, new RatePlan(cfg.defaultRatePerSecond(), cfg.defaultBurst()));
  }

  @Singleton
  ManageKeys manageKeys(
      KeyRepository keys, KeyMaterial material, InstantSource clock, KeysConfig cfg) {
    return new ManageKeys(keys, material, clock, cfg.maxGrace());
  }

  @Singleton
  VerifyKey verifyKey(
      KeyRepository keys, PepperProvider peppers, InstantSource clock, KeysConfig cfg) {
    return new VerifyKey(
        keys, peppers, new VerifyPolicy(new SecretHasher(), cfg.verifyTtl()), clock);
  }

  @Singleton
  RecordUsage recordUsage(UsageStore usage, KeyRepository keys) {
    return new RecordUsage(usage, keys);
  }

  @Singleton
  Callers apiCallers(InstantSource clock, KeysConfig cfg) {
    return new Callers(clock, cfg.requestDeadline());
  }

  @Singleton
  ApiSettings apiSettings(KeysConfig cfg) {
    return new ApiSettings(cfg.defaultGrace(), cfg.signatureSkew());
  }

  @Singleton
  InternalAuth internalAuth(CallerCredentials callers, InstantSource clock, KeysConfig cfg) {
    return new InternalAuth(callers, clock, cfg.signatureSkew());
  }
}
