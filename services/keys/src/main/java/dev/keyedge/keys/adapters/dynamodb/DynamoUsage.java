package dev.keyedge.keys.adapters.dynamodb;

import static dev.keyedge.keys.adapters.dynamodb.KeyItems.n;
import static dev.keyedge.keys.adapters.dynamodb.KeyItems.s;

import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.TenantId;
import dev.keyedge.keys.domain.UsageWindow;
import dev.keyedge.keys.ports.UsageStore;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Usage buckets per key and minute, merged with {@code ADD} (no read-modify-write). */
public final class DynamoUsage implements UsageStore {
  private final DynamoDbClient dynamo;
  private final String table;
  private final Duration retention;

  public DynamoUsage(DynamoDbClient dynamo, String table, Duration retention) {
    this.dynamo = dynamo;
    this.table = table;
    this.retention = retention;
  }

  private static String pk(TenantId tenant, KeyId keyId) {
    return "U#" + tenant.value() + "#" + keyId;
  }

  @Override
  public void add(List<UsageWindow> windows) {
    for (UsageWindow w : windows) {
      Instant minute = w.minute().truncatedTo(ChronoUnit.MINUTES);
      dynamo.updateItem(b ->
          b.tableName(table)
              .key(
                  Map.of(
                      KeysTable.PK,
                      s(pk(w.tenant(), w.keyId())),
                      KeysTable.SK,
                      s(minute.toString())))
              .updateExpression("ADD allowed :a, rejected :r SET #ttl = :ttl")
              .expressionAttributeNames(Map.of("#ttl", KeysTable.TTL))
              .expressionAttributeValues(
                  Map.of(
                      ":a",
                      n(w.allowed()),
                      ":r",
                      n(w.rejected()),
                      ":ttl",
                      n(minute.plus(retention).getEpochSecond()))));
    }
  }

  @Override
  public List<UsageWindow> query(TenantId tenant, KeyId keyId, Instant from, Instant to) {
    return dynamo
        .queryPaginator(b ->
            b.tableName(table)
                .keyConditionExpression("PK = :pk AND SK BETWEEN :from AND :to")
                .expressionAttributeValues(
                    Map.of(
                        ":pk", s(pk(tenant, keyId)),
                        ":from", s(from.truncatedTo(ChronoUnit.MINUTES).toString()),
                        ":to", s(to.minusMillis(1).toString()))))
        .items()
        .stream()
        .map(i ->
            new UsageWindow(
                tenant,
                keyId,
                Instant.parse(KeyItems.str(i, KeysTable.SK)),
                KeyItems.num(i, "allowed"),
                KeyItems.num(i, "rejected")))
        .toList();
  }
}
