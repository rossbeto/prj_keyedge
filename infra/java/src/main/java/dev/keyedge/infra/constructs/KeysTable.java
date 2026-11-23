package dev.keyedge.infra.constructs;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.GlobalSecondaryIndexProps;
import software.amazon.awscdk.services.dynamodb.PointInTimeRecoverySpecification;
import software.amazon.awscdk.services.dynamodb.ProjectionType;
import software.amazon.awscdk.services.dynamodb.Table;
import software.constructs.Construct;

/**
 * The single keys table (D-35): on-demand, PK/SK, GSI1 (a tenant's keys), TTL. Point-in-time
 * recovery and RETAIN only when the data matters (prodlike).
 */
public final class KeysTable extends Construct {
  private final Table table;

  /** Names and switches, from the spec. */
  public record Props(
      String tableName,
      String partitionKey,
      String sortKey,
      String gsiName,
      String gsiPartitionKey,
      String gsiSortKey,
      String ttlAttribute,
      boolean durable) {}

  public KeysTable(Construct scope, String id, Props p) {
    super(scope, id);
    table =
        Table.Builder.create(this, "Table")
            .tableName(p.tableName())
            .partitionKey(string(p.partitionKey()))
            .sortKey(string(p.sortKey()))
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .timeToLiveAttribute(p.ttlAttribute())
            .pointInTimeRecoverySpecification(
                PointInTimeRecoverySpecification.builder()
                    .pointInTimeRecoveryEnabled(p.durable())
                    .build())
            .removalPolicy(p.durable() ? RemovalPolicy.RETAIN : RemovalPolicy.DESTROY)
            .build();
    table.addGlobalSecondaryIndex(
        GlobalSecondaryIndexProps.builder()
            .indexName(p.gsiName())
            .partitionKey(string(p.gsiPartitionKey()))
            .sortKey(string(p.gsiSortKey()))
            .projectionType(ProjectionType.ALL)
            .build());
  }

  private static Attribute string(String name) {
    return Attribute.builder().name(name).type(AttributeType.STRING).build();
  }

  public Table table() {
    return table;
  }
}
