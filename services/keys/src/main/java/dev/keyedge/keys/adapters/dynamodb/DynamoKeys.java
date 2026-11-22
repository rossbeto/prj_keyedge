package dev.keyedge.keys.adapters.dynamodb;

import static dev.keyedge.keys.adapters.dynamodb.KeyItems.n;
import static dev.keyedge.keys.adapters.dynamodb.KeyItems.s;

import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.TenantId;
import dev.keyedge.keys.domain.StoredKey;
import dev.keyedge.keys.ports.Idempotency;
import dev.keyedge.keys.ports.KeyRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * Keys and idempotency records in one table. Creating a key and completing its idempotency record
 * are one {@code TransactWriteItems}; the cancellation reasons say which condition failed
 * (masterplan §5).
 */
public final class DynamoKeys implements KeyRepository, Idempotency {
  private final DynamoDbClient dynamo;
  private final String table;
  private final Duration inFlightTimeout;
  private final Duration retention;

  public DynamoKeys(
      DynamoDbClient dynamo, String table, Duration inFlightTimeout, Duration retention) {
    this.dynamo = dynamo;
    this.table = table;
    this.inFlightTimeout = inFlightTimeout;
    this.retention = retention;
  }

  @Override
  public Optional<StoredKey> find(KeyId keyId) {
    var item =
        dynamo
            .getItem(b -> b.tableName(table).key(KeyItems.key(keyId)).consistentRead(true))
            .item();
    return item == null || item.isEmpty() ? Optional.empty() : Optional.of(KeyItems.fromItem(item));
  }

  @Override
  public boolean update(StoredKey next) {
    try {
      dynamo.putItem(b ->
          b.tableName(table)
              .item(KeyItems.toItem(next))
              .conditionExpression("#v = :expected")
              .expressionAttributeNames(Map.of("#v", "version"))
              .expressionAttributeValues(Map.of(":expected", n(next.version() - 1))));
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  @Override
  public Page list(TenantId tenant, Optional<String> cursor, int limit) {
    var response =
        dynamo.query(b -> {
          b.tableName(table)
              .indexName(KeysTable.GSI1)
              .keyConditionExpression("#pk = :t")
              .expressionAttributeNames(Map.of("#pk", KeysTable.GSI1PK))
              .expressionAttributeValues(Map.of(":t", s("T#" + tenant.value())))
              .scanIndexForward(false)
              .limit(limit);
          // The cursor is the last key id; the partition comes from the caller's tenant, so a
          // forged
          // cursor can only move within that tenant's own keys.
          cursor
              .flatMap(KeyId::parse)
              .ifPresent(last -> {
                var start = new HashMap<>(KeyItems.key(last));
                start.put(KeysTable.GSI1PK, s("T#" + tenant.value()));
                start.put(KeysTable.GSI1SK, s(last.toString()));
                b.exclusiveStartKey(start);
              });
        });
    List<StoredKey> keys = response.items().stream().map(KeyItems::fromItem).toList();
    boolean more =
        response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty() && !keys.isEmpty();
    return new Page(keys, more ? Optional.of(keys.getLast().keyId().toString()) : Optional.empty());
  }

  private static Map<String, AttributeValue> recordKey(Request r) {
    String id = "I#" + r.tenant() + "#" + r.idempotencyKey();
    return Map.of(KeysTable.PK, s(id), KeysTable.SK, s("I"));
  }

  @Override
  public Begin begin(Request request, Instant now) {
    var existing =
        dynamo.getItem(b -> b.tableName(table).key(recordKey(request)).consistentRead(true)).item();
    Optional<Instant> stale = Optional.empty();
    if (existing != null && !existing.isEmpty()) {
      if (!KeyItems.str(existing, "hash").equals(request.requestHash())) {
        return new Begin.Mismatch();
      }
      if ("DONE".equals(KeyItems.str(existing, "status"))) {
        return new Begin.Replay(
            new StoredResponse(
                (int) KeyItems.num(existing, "httpStatus"),
                KeyItems.str(existing, "keyId"),
                KeyItems.str(existing, "location")));
      }
      Instant started = Instant.ofEpochMilli(KeyItems.num(existing, "startedAt"));
      if (now.isBefore(started.plus(inFlightTimeout))) {
        return new Begin.InFlight();
      }
      stale = Optional.of(started); // abandoned claim: take it over, but only if still unchanged
    }
    var item = new HashMap<>(recordKey(request));
    item.put("hash", s(request.requestHash()));
    item.put("status", s("IN_FLIGHT"));
    item.put("startedAt", n(now.toEpochMilli()));
    item.put(KeysTable.TTL, n(now.plus(retention).getEpochSecond()));
    final Optional<Instant> takeover = stale;
    try {
      dynamo.putItem(b -> {
        b.tableName(table).item(item);
        takeover.ifPresentOrElse(
            old ->
                b.conditionExpression("#st = :inflight AND startedAt = :old")
                    .expressionAttributeNames(Map.of("#st", "status"))
                    .expressionAttributeValues(
                        Map.of(":inflight", s("IN_FLIGHT"), ":old", n(old.toEpochMilli()))),
            () -> b.conditionExpression("attribute_not_exists(PK)"));
      });
      return new Begin.Started(now);
    } catch (ConditionalCheckFailedException e) {
      return new Begin.InFlight(); // someone else claimed it between our read and write
    }
  }

  @Override
  public Create createAndComplete(
      Request request, Instant startedAt, StoredKey key, StoredResponse response) {
    Put putKey =
        Put.builder()
            .tableName(table)
            .item(KeyItems.toItem(key))
            .conditionExpression("attribute_not_exists(PK)")
            .build();
    Update complete =
        Update.builder()
            .tableName(table)
            .key(recordKey(request))
            .updateExpression("SET #st = :done, httpStatus = :code, keyId = :kid, #loc = :loc")
            .conditionExpression("#st = :inflight AND startedAt = :started")
            .expressionAttributeNames(Map.of("#st", "status", "#loc", "location"))
            .expressionAttributeValues(
                Map.of(
                    ":done",
                    s("DONE"),
                    ":inflight",
                    s("IN_FLIGHT"),
                    ":started",
                    n(startedAt.toEpochMilli()),
                    ":code",
                    n(response.status()),
                    ":kid",
                    s(response.keyId()),
                    ":loc",
                    s(response.location())))
            .build();
    try {
      dynamo.transactWriteItems(b ->
          b.transactItems(
              TransactWriteItem.builder().put(putKey).build(),
              TransactWriteItem.builder().update(complete).build()));
      return new Create.Created();
    } catch (TransactionCanceledException e) {
      List<CancellationReason> reasons = e.cancellationReasons();
      if (reasons.size() == 2 && "ConditionalCheckFailed".equals(reasons.get(0).code())) {
        return new Create.IdCollision();
      }
      if (reasons.size() == 2 && "ConditionalCheckFailed".equals(reasons.get(1).code())) {
        return new Create.LostClaim();
      }
      throw e; // throttling, conflicts with another transaction: let the caller's retry policy
      // decide
    }
  }

  @Override
  public void abandon(Request request, Instant startedAt) {
    try {
      dynamo.deleteItem(b ->
          b.tableName(table)
              .key(recordKey(request))
              .conditionExpression("#st = :inflight AND startedAt = :started")
              .expressionAttributeNames(Map.of("#st", "status"))
              .expressionAttributeValues(
                  Map.of(":inflight", s("IN_FLIGHT"), ":started", n(startedAt.toEpochMilli()))));
    } catch (ConditionalCheckFailedException e) {
      // already completed or taken over: nothing to release
    }
  }
}
