package dev.keyedge.keys.lab;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.awstarget.AwsTarget;
import dev.keyedge.awstarget.AwsTargetConfigurer;
import dev.keyedge.awstarget.TargetKind;
import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyEnvironment;
import dev.keyedge.keyformat.KeyFormat;
import dev.keyedge.keyformat.KeyIds;
import dev.keyedge.keyformat.ParseResult;
import dev.keyedge.keyformat.RatePlan;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.keyformat.SecretHasher;
import dev.keyedge.keyformat.TenantId;
import dev.keyedge.keys.adapters.dynamodb.DynamoKeys;
import dev.keyedge.keys.adapters.dynamodb.KeysTable;
import dev.keyedge.keys.adapters.secrets.SecretsManagerPeppers;
import dev.keyedge.keys.app.IssueKey;
import dev.keyedge.keys.app.KeyMaterial;
import dev.keyedge.keys.app.RequestContext;
import dev.keyedge.keys.app.VerifyKey;
import dev.keyedge.keys.domain.VerifyPolicy;
import dev.keyedge.testkit.aws.Emulators;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Notebook experiments of Phase 005, run on both emulators with the real adapters. Each asserts its
 * verdict and writes {@code results/labs/<id>-<target>.json}.
 */
@Tag("experiment")
class KeysExperiments {
  private static final JsonMapper JSON = JsonMapper.createDefault();

  private record Env(DynamoKeys store, SecretsManagerClient secrets, String pepperSecret) {}

  private static Env env(TargetKind kind, int pepperVersions) {
    AwsTarget target = Emulators.start(kind);
    var dynamo = AwsTargetConfigurer.configure(DynamoDbClient.builder(), target).build();
    String table = "keyedge-lab-" + UUID.randomUUID().toString().substring(0, 8);
    KeysTable.create(dynamo, table);
    var secrets = AwsTargetConfigurer.configure(SecretsManagerClient.builder(), target).build();
    String id = "keyedge/lab-peppers-" + UUID.randomUUID();
    secrets.createSecret(b -> b.name(id).secretString(pepperDoc(pepperVersions, pepperVersions)));
    return new Env(
        new DynamoKeys(dynamo, table, Duration.ofSeconds(30), Duration.ofHours(24)), secrets, id);
  }

  private static String pepperDoc(int current, int... versions) {
    var peppers = new LinkedHashMap<String, String>();
    for (int v = 1; v <= Math.max(current, versions.length == 0 ? current : versions[0]); v++) {
      byte[] k = new byte[32];
      java.util.Arrays.fill(k, (byte) v);
      peppers.put(Integer.toString(v), Base64.getEncoder().encodeToString(k));
    }
    return "{\"current\":" + current + ",\"peppers\":" + toJson(peppers) + "}";
  }

  private static String toJson(Object o) {
    try {
      return JSON.writeValueAsString(o);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void write(String id, TargetKind kind, Map<String, Object> result)
      throws IOException {
    Path dir = Path.of("../../results/labs");
    Files.createDirectories(dir);
    result.put("target", kind.id());
    result.put("recorded_at", Instant.now().toString());
    Files.writeString(dir.resolve(id + "-" + kind.id() + ".json"), toJson(result) + "\n");
  }

  private static KeyMaterial material(SecretsManagerPeppers peppers) {
    var random = new SecureRandom();
    return new KeyMaterial(
        new KeyIds(InstantSource.system(), random),
        random,
        new SecretHasher(),
        peppers,
        KeyEnvironment.TEST);
  }

  @ParameterizedTest
  @EnumSource(
      value = TargetKind.class,
      names = {"FLOCI", "AWS_EMULATOR"})
  void idempotencyRace(TargetKind kind) throws Exception {
    var env = env(kind, 1);
    var peppers =
        new SecretsManagerPeppers(env.secrets(), JSON, env.pepperSecret(), Duration.ofMinutes(5));
    var issue =
        new IssueKey(env.store(), material(peppers), InstantSource.system(), new RatePlan(10, 20));
    String tenant = "lab-" + UUID.randomUUID().toString().substring(0, 8);
    var ctx = new RequestContext("lab", new TenantId(tenant), Instant.now().plusSeconds(30));
    var cmd = new IssueKey.Command("race", Set.of(new Scope("quotes:read")), Optional.empty());
    int pairs = 50;
    var outcomes = new ConcurrentHashMap<String, AtomicInteger>();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < pairs; i++) {
        String idem = "race-" + i;
        var start = new CountDownLatch(1);
        for (int twin = 0; twin < 2; twin++) {
          pool.submit(() -> {
            start.await();
            var r = issue.run(ctx, cmd, idem);
            outcomes
                .computeIfAbsent(r.getClass().getSimpleName(), k -> new AtomicInteger())
                .incrementAndGet();
            return null;
          });
        }
        start.countDown();
      }
    }
    // Independent count: read the tenant's keys back through the list index, not the use case's
    // results.
    int stored = 0;
    Optional<String> cursor = Optional.empty();
    do {
      var page = env.store().list(new TenantId(tenant), cursor, 100);
      stored += page.keys().size();
      cursor = page.next();
    } while (cursor.isPresent());
    var result = new LinkedHashMap<String, Object>();
    result.put("experiment", "idempotency-race");
    result.put(
        "hypothesis", "two identical concurrent POSTs per Idempotency-Key create exactly one key");
    result.put("pairs", pairs);
    result.put("keys_stored", stored);
    result.put(
        "outcomes",
        outcomes.entrySet().stream()
            .collect(
                java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())));
    result.put("verdict", stored == pairs ? "confirmed" : "refuted");
    write("idempotency-race", kind, result);
    assertThat(stored).isEqualTo(pairs);
    assertThat(outcomes.keySet()).isSubsetOf("Issued", "Replayed", "InFlight");
  }

  @ParameterizedTest
  @EnumSource(
      value = TargetKind.class,
      names = {"FLOCI", "AWS_EMULATOR"})
  void pepperRotation(TargetKind kind) throws Exception {
    var env = env(kind, 1);
    var verifyPolicy = new VerifyPolicy(new SecretHasher(), Duration.ofSeconds(60));
    var peppersV1 =
        new SecretsManagerPeppers(env.secrets(), JSON, env.pepperSecret(), Duration.ofMinutes(5));
    var ctx = new RequestContext("lab", new TenantId("acme"), Instant.now().plusSeconds(30));
    var cmd = new IssueKey.Command("p", Set.of(), Optional.empty());
    var old =
        (IssueKey.Result.Issued)
            new IssueKey(
                    env.store(), material(peppersV1), InstantSource.system(), new RatePlan(1, 1))
                .run(ctx, cmd, "old");
    env.secrets().putSecretValue(b -> b.secretId(env.pepperSecret()).secretString(pepperDoc(2)));
    var peppersV2 =
        new SecretsManagerPeppers(env.secrets(), JSON, env.pepperSecret(), Duration.ofMinutes(5));
    var fresh =
        (IssueKey.Result.Issued)
            new IssueKey(
                    env.store(), material(peppersV2), InstantSource.system(), new RatePlan(1, 1))
                .run(ctx, cmd, "new");
    var verify = new VerifyKey(env.store(), peppersV2, verifyPolicy, InstantSource.system());
    List<String> afterRotation = new ArrayList<>();
    for (var issued : List.of(old, fresh)) {
      var p = (ParseResult.Parsed) KeyFormat.parse(issued.fullKey());
      afterRotation.add(verify.verify(p.keyId(), p.secret()).getClass().getSimpleName());
    }
    // Retire pepper 1: only pepper 2 remains.
    env.secrets()
        .putSecretValue(b ->
            b.secretId(env.pepperSecret())
                .secretString(
                    "{\"current\":2,\"peppers\":{\"2\":\""
                        + Base64.getEncoder().encodeToString(filled(2))
                        + "\"}}"));
    var peppersRetired =
        new SecretsManagerPeppers(env.secrets(), JSON, env.pepperSecret(), Duration.ofMinutes(5));
    var p = (ParseResult.Parsed) KeyFormat.parse(old.fullKey());
    KeyCheck afterRetire =
        new VerifyKey(env.store(), peppersRetired, verifyPolicy, InstantSource.system())
            .verify(p.keyId(), p.secret());
    var result = new LinkedHashMap<String, Object>();
    result.put("experiment", "pepper-rotation");
    result.put(
        "hypothesis",
        "keys hashed with an old pepper keep verifying after rotation; new keys use the new pepper; retiring the old pepper invalidates old keys");
    result.put("old_key_pepper", old.key().hash().version().value());
    result.put("new_key_pepper", fresh.key().hash().version().value());
    result.put("after_rotation", afterRotation);
    result.put("old_key_after_retire", afterRetire.getClass().getSimpleName());
    boolean ok =
        afterRotation.equals(List.of("Valid", "Valid"))
            && afterRetire instanceof KeyCheck.Unknown
            && old.key().hash().version().value() == 1
            && fresh.key().hash().version().value() == 2;
    result.put("verdict", ok ? "confirmed" : "refuted");
    write("pepper-rotation", kind, result);
    assertThat(ok).isTrue();
  }

  private static byte[] filled(int v) {
    byte[] k = new byte[32];
    java.util.Arrays.fill(k, (byte) v);
    return k;
  }
}
