package dev.keyedge.keys.api;

import dev.keyedge.keyformat.Scope;
import dev.keyedge.keys.domain.KeyStatus;
import dev.keyedge.keys.domain.StoredKey;
import dev.keyedge.keys.domain.UsageWindow;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Request and response bodies of the keys API (JSON). Never contains a secret except {@code key}
 * once.
 */
public final class Dtos {
  private Dtos() {}

  /** POST /v1/keys. */
  @Serdeable
  public record CreateKey(
      @NotBlank @Size(max = 80) String name,
      @Size(max = 20) @Nullable List<String> scopes,
      @Nullable Instant expiresAt) {}

  /** POST /v1/keys/{id}/rotate. */
  @Serdeable
  public record Rotate(@Nullable Long graceSeconds) {}

  /** Rate limit attached to a key. */
  @Serdeable
  public record RateLimit(long perSecond, long burst) {}

  /** A key's metadata. */
  @Serdeable
  public record KeyView(
      String keyId,
      String name,
      String hint,
      List<String> scopes,
      String status,
      Instant createdAt,
      @Nullable Instant expiresAt,
      @Nullable Instant previousSecretRetiresAt,
      @Nullable Instant revokedAt,
      RateLimit rateLimit,
      long version) {
    static KeyView of(StoredKey k) {
      return new KeyView(
          k.keyId().toString(),
          k.name(),
          k.hint(),
          k.scopes().stream().map(Scope::value).sorted().toList(),
          switch (k.status()) {
            case KeyStatus.Active a -> "ACTIVE";
            case KeyStatus.Rotating r -> "ROTATING";
            case KeyStatus.Revoked r -> "REVOKED";
          },
          k.createdAt(),
          k.expiresAt().orElse(null),
          k.status() instanceof KeyStatus.Rotating r ? r.retireAfter() : null,
          k.status() instanceof KeyStatus.Revoked r ? r.at() : null,
          new RateLimit(k.plan().permitsPerSecond(), k.plan().burst()),
          k.version());
    }
  }

  /** A key with its full text: returned once, at creation or rotation. */
  @Serdeable
  public record KeyWithSecret(
      @Nullable String key, boolean keyShownOnce, @Nullable KeyView metadata, String keyId) {}

  /** A page of keys. */
  @Serdeable
  public record KeyPage(List<KeyView> items, @Nullable String next) {}

  /** One usage bucket. */
  @Serdeable
  public record Usage(String keyId, Instant minute, long allowed, long rejected) {
    static Usage of(UsageWindow w) {
      return new Usage(w.keyId().toString(), w.minute(), w.allowed(), w.rejected());
    }
  }

  /** POST /internal/v1/verify. */
  @Serdeable
  public record VerifyRequest(String keyId, String secret) {}

  /** The verdict for a sidecar. */
  @Serdeable
  public record Verdict(
      String verdict,
      @Nullable String tenant,
      String keyId,
      List<String> scopes,
      @Nullable RateLimit rateLimit,
      long ttlSeconds,
      @Nullable Instant at) {}

  /** POST /internal/v1/usage (one entry per key and minute). */
  @Serdeable
  public record UsageEntry(
      String tenant, String keyId, Instant minute, long allowed, long rejected) {}
}
