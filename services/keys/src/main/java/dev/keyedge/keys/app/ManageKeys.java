package dev.keyedge.keys.app;

import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keys.domain.KeyLifecycle;
import dev.keyedge.keys.domain.KeyStatus;
import dev.keyedge.keys.domain.KeysError;
import dev.keyedge.keys.domain.StoredKey;
import dev.keyedge.keys.ports.KeyRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
import java.util.function.Function;

/** Read, list, rotate and revoke a tenant's keys (optimistic locking, one retry on conflict). */
public final class ManageKeys {
  private final KeyRepository keys;
  private final KeyMaterial material;
  private final InstantSource clock;
  private final Duration maxGrace;

  public ManageKeys(
      KeyRepository keys, KeyMaterial material, InstantSource clock, Duration maxGrace) {
    this.keys = keys;
    this.material = material;
    this.clock = clock;
    this.maxGrace = maxGrace;
  }

  /** Outcome of a change. {@code fullKey} is present only after a rotation (shown once). */
  public sealed interface Change {
    record Changed(StoredKey key, Optional<String> fullKey) implements Change {}

    record Failed(KeysError error) implements Change {}
  }

  /** What a transition decides for the current state of a key. */
  private sealed interface Step {
    record Write(StoredKey next, Optional<String> fullKey) implements Step {}

    record Unchanged(StoredKey key) implements Step {}

    record Refuse(KeysError error) implements Step {}
  }

  /** The key, if it belongs to the caller's tenant (another tenant's key is "not found"). */
  public Optional<StoredKey> get(RequestContext ctx, KeyId keyId) {
    return keys.find(keyId).filter(k -> k.tenant().equals(ctx.tenant()));
  }

  public KeyRepository.Page list(RequestContext ctx, Optional<String> cursor, int limit) {
    return keys.list(ctx.tenant(), cursor, Math.max(1, Math.min(limit, 100)));
  }

  /** New secret; the old one stays valid for {@code grace}. */
  public Change rotate(RequestContext ctx, KeyId keyId, Duration grace) {
    if (grace.isNegative() || grace.compareTo(maxGrace) > 0) {
      return new Change.Failed(new KeysError.Invalid("grace must be between 0 and " + maxGrace));
    }
    Instant now = clock.instant();
    return change(
        ctx,
        keyId,
        key -> {
          if (key.status() instanceof KeyStatus.Revoked) {
            return new Step.Refuse(new KeysError.Invalid("a revoked key cannot be rotated"));
          }
          var fresh = material.forId(keyId);
          return new Step.Write(
              KeyLifecycle.rotate(key, fresh.hash(), fresh.hint(), grace, now),
              Optional.of(fresh.fullKey()));
        });
  }

  /** Revoke now; revoking again returns the key unchanged (idempotent). */
  public Change revoke(RequestContext ctx, KeyId keyId) {
    Instant now = clock.instant();
    return change(
        ctx,
        keyId,
        key ->
            key.status() instanceof KeyStatus.Revoked
                ? new Step.Unchanged(key)
                : new Step.Write(KeyLifecycle.revoke(key, now), Optional.empty()));
  }

  private Change change(RequestContext ctx, KeyId keyId, Function<StoredKey, Step> transition) {
    for (int attempt = 0; attempt < 2; attempt++) {
      Optional<StoredKey> current = get(ctx, keyId);
      if (current.isEmpty()) {
        return new Change.Failed(new KeysError.NotFound());
      }
      switch (transition.apply(current.get())) {
        case Step.Unchanged(var key) -> {
          return new Change.Changed(key, Optional.empty());
        }
        case Step.Refuse(var error) -> {
          return new Change.Failed(error);
        }
        case Step.Write(var next, var fullKey) -> {
          if (keys.update(next)) {
            return new Change.Changed(next, fullKey);
          }
          // lost the optimistic lock: reload and decide again
        }
      }
    }
    return new Change.Failed(new KeysError.Conflict());
  }
}
