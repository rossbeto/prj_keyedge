package dev.keyedge.keys.adapters.dynamodb;

import dev.keyedge.keyformat.KeyEnvironment;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.PepperVersion;
import dev.keyedge.keyformat.RatePlan;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.keyformat.SecretHash;
import dev.keyedge.keyformat.TenantId;
import dev.keyedge.keys.domain.KeyStatus;
import dev.keyedge.keys.domain.StoredKey;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Maps {@link StoredKey} to and from its DynamoDB item. No secret is ever part of an item. */
final class KeyItems {
  private KeyItems() {}

  static Map<String, AttributeValue> key(KeyId keyId) {
    String id = "K#" + keyId;
    return Map.of(KeysTable.PK, s(id), KeysTable.SK, s(id));
  }

  static Map<String, AttributeValue> toItem(StoredKey k) {
    var item = new HashMap<>(key(k.keyId()));
    item.put(KeysTable.GSI1PK, s("T#" + k.tenant().value()));
    item.put(KeysTable.GSI1SK, s(k.keyId().toString()));
    item.put("tenant", s(k.tenant().value()));
    item.put("env", s(k.environment().label()));
    item.put("name", s(k.name()));
    item.put(
        "scopes",
        AttributeValue.fromL(
            k.scopes().stream()
                .map(sc -> s(sc.value()))
                .sorted((a, b) -> a.s().compareTo(b.s()))
                .toList()));
    item.put("rate", n(k.plan().permitsPerSecond()));
    item.put("burst", n(k.plan().burst()));
    item.put("hash", s(k.hash().digestBase64()));
    item.put("pepper", n(k.hash().version().value()));
    item.put("hint", s(k.hint()));
    item.put("createdAt", n(k.createdAt().toEpochMilli()));
    k.expiresAt().ifPresent(e -> item.put("expiresAt", n(e.toEpochMilli())));
    item.put("version", n(k.version()));
    switch (k.status()) {
      case KeyStatus.Active a -> item.put("status", s("ACTIVE"));
      case KeyStatus.Rotating(SecretHash prev, Instant retireAfter) -> {
        item.put("status", s("ROTATING"));
        item.put("prevHash", s(prev.digestBase64()));
        item.put("prevPepper", n(prev.version().value()));
        item.put("retireAfter", n(retireAfter.toEpochMilli()));
      }
      case KeyStatus.Revoked(Instant at) -> {
        item.put("status", s("REVOKED"));
        item.put("revokedAt", n(at.toEpochMilli()));
      }
    }
    return item;
  }

  static StoredKey fromItem(Map<String, AttributeValue> item) {
    KeyStatus status =
        switch (str(item, "status")) {
          case "ROTATING" ->
              new KeyStatus.Rotating(
                  SecretHash.of(
                      new PepperVersion((int) num(item, "prevPepper")), str(item, "prevHash")),
                  Instant.ofEpochMilli(num(item, "retireAfter")));
          case "REVOKED" -> new KeyStatus.Revoked(Instant.ofEpochMilli(num(item, "revokedAt")));
          default -> new KeyStatus.Active();
        };
    List<AttributeValue> scopes = item.containsKey("scopes") ? item.get("scopes").l() : List.of();
    return new StoredKey(
        new TenantId(str(item, "tenant")),
        KeyId.parse(str(item, KeysTable.GSI1SK)).orElseThrow(),
        KeyEnvironment.ofLabel(str(item, "env")).orElseThrow(),
        str(item, "name"),
        scopes.stream().map(v -> new Scope(v.s())).collect(Collectors.toSet()),
        new RatePlan(num(item, "rate"), num(item, "burst")),
        SecretHash.of(new PepperVersion((int) num(item, "pepper")), str(item, "hash")),
        str(item, "hint"),
        status,
        Instant.ofEpochMilli(num(item, "createdAt")),
        Optional.ofNullable(item.get("expiresAt"))
            .map(v -> Instant.ofEpochMilli(Long.parseLong(v.n()))),
        num(item, "version"));
  }

  static AttributeValue s(String v) {
    return AttributeValue.fromS(v);
  }

  static AttributeValue n(long v) {
    return AttributeValue.fromN(Long.toString(v));
  }

  static long num(Map<String, AttributeValue> item, String name) {
    return Long.parseLong(attr(item, name).n());
  }

  static String str(Map<String, AttributeValue> item, String name) {
    return attr(item, name).s();
  }

  private static AttributeValue attr(Map<String, AttributeValue> item, String name) {
    AttributeValue v = item.get(name);
    if (v == null) {
      throw new IllegalStateException("item has no attribute '" + name + "'");
    }
    return v;
  }
}
