package dev.keyedge.keys.fakes;

import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.TenantId;
import dev.keyedge.keys.domain.StoredKey;
import dev.keyedge.keys.ports.Idempotency;
import dev.keyedge.keys.ports.KeyRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory keys + idempotency with the same semantics as the DynamoDB adapters (shared contract
 * tests).
 */
public final class InMemoryKeys implements KeyRepository, Idempotency {
  private final Map<KeyId, StoredKey> keys = new ConcurrentHashMap<>();
  private final Map<String, Claim> records = new ConcurrentHashMap<>();
  private final Duration inFlightTimeout;
  public int forcedCollisions;

  private record Claim(String hash, Instant startedAt, Optional<StoredResponse> done) {}

  public InMemoryKeys(Duration inFlightTimeout) {
    this.inFlightTimeout = inFlightTimeout;
  }

  @Override
  public Optional<StoredKey> find(KeyId keyId) {
    return Optional.ofNullable(keys.get(keyId));
  }

  @Override
  public synchronized boolean update(StoredKey next) {
    StoredKey current = keys.get(next.keyId());
    if (current == null || current.version() != next.version() - 1) {
      return false;
    }
    keys.put(next.keyId(), next);
    return true;
  }

  @Override
  public Page list(TenantId tenant, Optional<String> cursor, int limit) {
    var sorted =
        keys.values().stream()
            .filter(k -> k.tenant().equals(tenant))
            .sorted(Comparator.comparing(StoredKey::keyId).reversed())
            .filter(k -> cursor.map(c -> k.keyId().toString().compareTo(c) < 0).orElse(true))
            .toList();
    var page = sorted.stream().limit(limit).toList();
    return new Page(
        page,
        sorted.size() > limit ? Optional.of(page.getLast().keyId().toString()) : Optional.empty());
  }

  private static String id(Request r) {
    return r.tenant() + "#" + r.idempotencyKey();
  }

  @Override
  public synchronized Begin begin(Request request, Instant now) {
    Claim existing = records.get(id(request));
    if (existing != null) {
      if (!existing.hash().equals(request.requestHash())) {
        return new Begin.Mismatch();
      }
      if (existing.done().isPresent()) {
        return new Begin.Replay(existing.done().get());
      }
      if (now.isBefore(existing.startedAt().plus(inFlightTimeout))) {
        return new Begin.InFlight();
      }
    }
    records.put(id(request), new Claim(request.requestHash(), now, Optional.empty()));
    return new Begin.Started(now);
  }

  @Override
  public synchronized Create createAndComplete(
      Request request, Instant startedAt, StoredKey key, StoredResponse response) {
    if (forcedCollisions > 0) {
      forcedCollisions--;
      return new Create.IdCollision();
    }
    if (keys.containsKey(key.keyId())) {
      return new Create.IdCollision();
    }
    Claim r = records.get(id(request));
    if (r == null || !r.startedAt().equals(startedAt) || r.done().isPresent()) {
      return new Create.LostClaim();
    }
    keys.put(key.keyId(), key);
    records.put(id(request), new Claim(r.hash(), startedAt, Optional.of(response)));
    return new Create.Created();
  }

  @Override
  public synchronized void abandon(Request request, Instant startedAt) {
    Claim r = records.get(id(request));
    if (r != null && r.startedAt().equals(startedAt) && r.done().isEmpty()) {
      records.remove(id(request));
    }
  }

  public int size() {
    return keys.size();
  }
}
