package dev.keyedge.keys.ports;

import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.TenantId;
import dev.keyedge.keys.domain.StoredKey;
import java.util.List;
import java.util.Optional;

/** Persistence of keys. */
public interface KeyRepository {
  /** The key with this id, whatever its tenant (the caller checks the tenant). One read. */
  Optional<StoredKey> find(KeyId keyId);

  /** Replaces the key if its stored version is still {@code next.version() - 1}. */
  boolean update(StoredKey next);

  /** A page of a tenant's keys, newest first. */
  Page list(TenantId tenant, Optional<String> cursor, int limit);

  /** A page and the opaque cursor of the next one. */
  record Page(List<StoredKey> keys, Optional<String> next) {}
}
