package dev.keyedge.keys.adapters.dynamodb;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * The single table (masterplan §5, D-23 as implemented): one item per key ({@code K#<keyId>}),
 * idempotency records ({@code I#<tenant>#<key>}), usage buckets ({@code U#<tenant>#<keyId>} /
 * minute); GSI1 lists a tenant's keys newest first. The CDK DataStack creates it (Phase 012);
 * {@link #create} is for tests (Testcontainers) only.
 */
public final class KeysTable {
  static final String PK = "PK";
  static final String SK = "SK";
  static final String GSI1 = "GSI1";
  static final String GSI1PK = "GSI1PK";
  static final String GSI1SK = "GSI1SK";
  static final String TTL = "ttl";

  private KeysTable() {}

  /** Creates the table (on-demand, GSI1, TTL on {@code ttl}) and waits until it is active. */
  public static void create(DynamoDbClient dynamo, String name) {
    dynamo.createTable(b ->
        b.tableName(name)
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .attributeDefinitions(attr(PK), attr(SK), attr(GSI1PK), attr(GSI1SK))
            .keySchema(key(PK, KeyType.HASH), key(SK, KeyType.RANGE))
            .globalSecondaryIndexes(
                GlobalSecondaryIndex.builder()
                    .indexName(GSI1)
                    .keySchema(key(GSI1PK, KeyType.HASH), key(GSI1SK, KeyType.RANGE))
                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                    .build()));
    dynamo.waiter().waitUntilTableExists(b -> b.tableName(name));
    dynamo.updateTimeToLive(
        b -> b.tableName(name).timeToLiveSpecification(t -> t.enabled(true).attributeName(TTL)));
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
}
