package dev.keyedge.awstarget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.keyedge.testkit.aws.Emulators;
import dev.keyedge.testkit.aws.OnTarget;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.InvalidCiphertextException;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.KeyUsageType;
import software.amazon.awssdk.services.kms.model.MacAlgorithmSpec;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.sts.StsClient;

/**
 * The AWS behaviours keyedge relies on, run against each emulator by a subclass. Each case creates
 * uniquely named resources; nothing is shared between cases. A known emulator difference is
 * declared with {@code @EmulatorDifference} on the case and listed in docs/parity.md. Results feed
 * {@code make emu-parity}.
 */
@Tag("aws")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AwsContractSuite implements OnTarget {
  protected AwsTarget target;
  protected DynamoDbClient dynamo;
  private String table;

  @BeforeAll
  void startEmulator() {
    target = Emulators.start(kind());
    assertThat(target.local()).as("contract tests never run against real AWS").isTrue();
    dynamo = AwsTargetConfigurer.configure(DynamoDbClient.builder(), target).build();
    table = createTable("contract");
  }

  static String unique(String prefix) {
    return "keyedge-" + prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private String createTable(String prefix) {
    String name = unique(prefix);
    dynamo.createTable(b ->
        b.tableName(name)
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .attributeDefinitions(attr("PK"), attr("SK"), attr("GSI1PK"), attr("GSI1SK"))
            .keySchema(key("PK", KeyType.HASH), key("SK", KeyType.RANGE))
            .globalSecondaryIndexes(
                GlobalSecondaryIndex.builder()
                    .indexName("GSI1")
                    .keySchema(key("GSI1PK", KeyType.HASH), key("GSI1SK", KeyType.RANGE))
                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                    .build()));
    dynamo.waiter().waitUntilTableExists(b -> b.tableName(name));
    return name;
  }

  private static AttributeDefinition attr(String name) {
    return AttributeDefinition.builder()
        .attributeName(name)
        .attributeType(ScalarAttributeType.S)
        .build();
  }

  private static KeySchemaElement key(String name, KeyType type) {
    return KeySchemaElement.builder().attributeName(name).keyType(type).build();
  }

  private static AttributeValue s(String v) {
    return AttributeValue.fromS(v);
  }

  private static Map<String, AttributeValue> item(String pk, String sk) {
    return Map.of("PK", s(pk), "SK", s(sk));
  }

  @Test
  void stsReportsTheEmulatorAccount() {
    try (var sts = AwsTargetConfigurer.configure(StsClient.builder(), target).build()) {
      assertThat(sts.getCallerIdentity().account()).isEqualTo("000000000000");
    }
  }

  @Test
  void transactionCancellationReportsWhichConditionFailed() {
    String id = UUID.randomUUID().toString();
    Put lookup =
        Put.builder()
            .tableName(table)
            .item(item("K#" + id, "K#" + id))
            .conditionExpression("attribute_not_exists(PK)")
            .build();
    Put key =
        Put.builder()
            .tableName(table)
            .item(item("T#acme", "K#" + id))
            .conditionExpression("attribute_not_exists(PK)")
            .build();
    dynamo.transactWriteItems(b ->
        b.transactItems(
            TransactWriteItem.builder().put(key).build(),
            TransactWriteItem.builder().put(lookup).build()));

    String other = UUID.randomUUID().toString();
    Put freshKey = key.toBuilder().item(item("T#acme", "K#" + other)).build();
    assertThatThrownBy(() ->
            dynamo.transactWriteItems(b ->
                b.transactItems(
                    TransactWriteItem.builder().put(freshKey).build(),
                    TransactWriteItem.builder().put(lookup).build())))
        .isInstanceOfSatisfying(
            TransactionCanceledException.class,
            e -> {
              var codes = e.cancellationReasons().stream().map(r -> r.code()).toList();
              assertThat(codes).containsExactly("None", "ConditionalCheckFailed");
            });
    assertThat(
            dynamo
                .getItem(
                    b -> b.tableName(table).key(item("T#acme", "K#" + other)).consistentRead(true))
                .hasItem())
        .as("nothing of a cancelled transaction is written")
        .isFalse();
  }

  @Test
  void optimisticLockAndAtomicCounter() {
    var k = item("U#" + UUID.randomUUID(), "m");
    dynamo.putItem(b ->
        b.tableName(table)
            .item(
                Map.of(
                    "PK", k.get("PK"), "SK", k.get("SK"), "version", AttributeValue.fromN("1"))));
    var updated =
        dynamo.updateItem(b ->
            b.tableName(table)
                .key(k)
                .updateExpression("ADD cnt :one SET version = :v2")
                .conditionExpression("version = :v1")
                .expressionAttributeValues(
                    Map.of(
                        ":one",
                        AttributeValue.fromN("1"),
                        ":v1",
                        AttributeValue.fromN("1"),
                        ":v2",
                        AttributeValue.fromN("2")))
                .returnValues(ReturnValue.ALL_NEW));
    assertThat(updated.attributes().get("cnt").n()).isEqualTo("1");
    assertThatThrownBy(() ->
            dynamo.updateItem(b ->
                b.tableName(table)
                    .key(k)
                    .updateExpression("ADD cnt :one")
                    .conditionExpression("version = :v1")
                    .expressionAttributeValues(
                        Map.of(
                            ":one", AttributeValue.fromN("1"), ":v1", AttributeValue.fromN("1")))))
        .isInstanceOf(ConditionalCheckFailedException.class);
  }

  @Test
  void gsiQueryPaginatesWithExclusiveStartKey() {
    String partition = "T#" + UUID.randomUUID() + "#ACTIVE";
    for (int i = 0; i < 5; i++) {
      var it =
          Map.of(
              "PK",
              s("Q#" + UUID.randomUUID()),
              "SK",
              s("m"),
              "GSI1PK",
              s(partition),
              "GSI1SK",
              s("2026-" + i));
      dynamo.putItem(b -> b.tableName(table).item(it));
    }
    var seen = new java.util.ArrayList<String>();
    Map<String, AttributeValue> start = null;
    int pages = 0;
    do {
      final Map<String, AttributeValue> from = start;
      var page =
          dynamo.query(b -> {
            b.tableName(table)
                .indexName("GSI1")
                .keyConditionExpression("GSI1PK = :p")
                .expressionAttributeValues(Map.of(":p", s(partition)))
                .limit(2);
            if (from != null) {
              b.exclusiveStartKey(from);
            }
          });
      page.items().forEach(i -> seen.add(i.get("GSI1SK").s()));
      start =
          page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty()
              ? page.lastEvaluatedKey()
              : null;
      pages++;
    } while (start != null && pages < 10);
    assertThat(seen).containsExactly("2026-0", "2026-1", "2026-2", "2026-3", "2026-4");
    assertThat(pages).isBetween(3, 4);
  }

  @Test
  @org.junit.jupiter.api.Timeout(150) // Floci sweeps TTL every ~60 s: longer than the 60 s default
  void ttlIsEnabledAndExpiredItemsEventuallyGo() throws InterruptedException {
    String ttlTable = createTable("ttl");
    dynamo.updateTimeToLive(b ->
        b.tableName(ttlTable).timeToLiveSpecification(t -> t.enabled(true).attributeName("ttl")));
    var ttl = dynamo.describeTimeToLive(b -> b.tableName(ttlTable)).timeToLiveDescription();
    assertThat(ttl.attributeName()).isEqualTo("ttl");
    assertThat(ttl.timeToLiveStatus()).isIn(TimeToLiveStatus.ENABLED, TimeToLiveStatus.ENABLING);
    var k = item("TTL#1", "m");
    dynamo.putItem(b ->
        b.tableName(ttlTable)
            .item(
                Map.of(
                    "PK",
                    k.get("PK"),
                    "SK",
                    k.get("SK"),
                    "ttl",
                    AttributeValue.fromN(String.valueOf(Instant.now().getEpochSecond() - 60)))));
    Instant start = Instant.now();
    // AWS deletes "within a few days": production code never relies on this; the emulators' timing
    // is a parity row.
    while (Duration.between(start, Instant.now()).toSeconds() < 120) {
      if (!dynamo.getItem(b -> b.tableName(ttlTable).key(k).consistentRead(true)).hasItem()) {
        System.out.println(
            "PARITY-DATA ttl_deleted_after_s="
                + Duration.between(start, Instant.now()).toSeconds());
        return;
      }
      Thread.sleep(2_000);
    }
    throw new AssertionError("expired item still present after 120 s");
  }

  @Test
  void secretVersionsAndStages() {
    try (var sm = AwsTargetConfigurer.configure(SecretsManagerClient.builder(), target).build()) {
      String name = unique("pepper");
      String v1 = sm.createSecret(b -> b.name(name).secretString("pepper-v1")).versionId();
      sm.putSecretValue(b -> b.secretId(name).secretString("pepper-v2"));
      assertThat(sm.getSecretValue(b -> b.secretId(name)).secretString()).isEqualTo("pepper-v2");
      assertThat(
              sm.getSecretValue(b -> b.secretId(name).versionStage("AWSPREVIOUS")).secretString())
          .isEqualTo("pepper-v1");
      assertThat(sm.getSecretValue(b -> b.secretId(name).versionId(v1)).secretString())
          .isEqualTo("pepper-v1");
      var stages =
          sm.listSecretVersionIds(b -> b.secretId(name)).versions().stream()
              .flatMap(v -> v.versionStages().stream())
              .toList();
      assertThat(stages).contains("AWSCURRENT", "AWSPREVIOUS");
    }
  }

  @Test
  void parametersByPathWithSecureStringsAndVersions() {
    try (var ssm = AwsTargetConfigurer.configure(SsmClient.builder(), target).build()) {
      String root = "/" + unique("ssm");
      ssm.putParameter(b -> b.name(root + "/table-name").value("t1").type(ParameterType.STRING));
      ssm.putParameter(
          b -> b.name(root + "/deep/key").value("s3cr3t").type(ParameterType.SECURE_STRING));
      var overwritten =
          ssm.putParameter(b ->
              b.name(root + "/table-name").value("t2").type(ParameterType.STRING).overwrite(true));
      assertThat(overwritten.version()).isEqualTo(2L);
      var params =
          ssm.getParametersByPath(b -> b.path(root).recursive(true).withDecryption(true))
              .parameters();
      assertThat(params)
          .extracting(p -> p.name().substring(root.length()), p -> p.value())
          .containsExactlyInAnyOrder(
              org.assertj.core.groups.Tuple.tuple("/table-name", "t2"),
              org.assertj.core.groups.Tuple.tuple("/deep/key", "s3cr3t"));
    }
  }

  @Test
  void kmsEncryptionContextMustMatch() {
    try (var kms = AwsTargetConfigurer.configure(KmsClient.builder(), target).build()) {
      String keyId = kms.createKey(b -> b.keySpec(KeySpec.SYMMETRIC_DEFAULT)).keyMetadata().keyId();
      var context = Map.of("tenant", "acme");
      var blob =
          kms.encrypt(b ->
                  b.keyId(keyId)
                      .plaintext(SdkBytes.fromUtf8String("hello"))
                      .encryptionContext(context))
              .ciphertextBlob();
      assertThat(
              kms.decrypt(b -> b.ciphertextBlob(blob).encryptionContext(context))
                  .plaintext()
                  .asUtf8String())
          .isEqualTo("hello");
      assertThatThrownBy(() ->
              kms.decrypt(b -> b.ciphertextBlob(blob).encryptionContext(Map.of("tenant", "other"))))
          .isInstanceOf(InvalidCiphertextException.class);
    }
  }

  @Test
  void kmsHmacKeysGenerateAndVerifyMacs() {
    try (var kms = AwsTargetConfigurer.configure(KmsClient.builder(), target).build()) {
      String keyId =
          kms.createKey(b -> b.keySpec(KeySpec.HMAC_256).keyUsage(KeyUsageType.GENERATE_VERIFY_MAC))
              .keyMetadata()
              .keyId();
      var mac =
          kms.generateMac(b ->
                  b.keyId(keyId)
                      .macAlgorithm(MacAlgorithmSpec.HMAC_SHA_256)
                      .message(SdkBytes.fromUtf8String("m")))
              .mac();
      assertThat(
              kms.verifyMac(b ->
                      b.keyId(keyId)
                          .macAlgorithm(MacAlgorithmSpec.HMAC_SHA_256)
                          .message(SdkBytes.fromUtf8String("m"))
                          .mac(mac))
                  .macValid())
          .isTrue();
      assertThat(List.of(mac.asByteArray().length)).containsExactly(32);
    }
  }
}
