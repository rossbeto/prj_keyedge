package dev.keyedge.keys.app;

import dev.keyedge.keyformat.RatePlan;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.keys.domain.KeyStatus;
import dev.keyedge.keys.domain.KeysError;
import dev.keyedge.keys.domain.StoredKey;
import dev.keyedge.keys.ports.Idempotency;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Creates a key exactly once per Idempotency-Key: the key and the idempotency record's DONE state
 * are written in one transaction, so a retry either replays or creates, never both.
 */
public final class IssueKey {
  private static final int ATTEMPTS = 3;
  private final Idempotency idempotency;
  private final KeyMaterial material;
  private final InstantSource clock;
  private final RatePlan defaultPlan;

  public IssueKey(
      Idempotency idempotency, KeyMaterial material, InstantSource clock, RatePlan defaultPlan) {
    this.idempotency = idempotency;
    this.material = material;
    this.clock = clock;
    this.defaultPlan = defaultPlan;
  }

  /** What the caller asked for. */
  public record Command(String name, Set<Scope> scopes, Optional<Instant> expiresAt) {
    public Command {
      scopes = Set.copyOf(scopes);
    }

    String canonical() {
      var sorted = new TreeSet<String>();
      scopes.forEach(s -> sorted.add(s.value()));
      return name
          + "\n"
          + String.join(",", sorted)
          + "\n"
          + expiresAt.map(Instant::toString).orElse("-");
    }
  }

  /** Outcome. */
  public sealed interface Result {
    record Issued(StoredKey key, String fullKey) implements Result {}

    record Replayed(Idempotency.StoredResponse response) implements Result {}

    record InFlight() implements Result {}

    record Mismatch() implements Result {}

    record Failed(KeysError error) implements Result {}
  }

  public Result run(RequestContext ctx, Command cmd, String idempotencyKey) {
    Instant now = clock.instant();
    if (cmd.name().isBlank() || cmd.name().length() > 80) {
      // validated here too: the use case must not depend on the API's annotations (found by a test)
      return new Result.Failed(new KeysError.Invalid("name must be 1-80 characters"));
    }
    if (cmd.expiresAt().isPresent() && !cmd.expiresAt().get().isAfter(now)) {
      return new Result.Failed(new KeysError.Invalid("expiresAt must be in the future"));
    }
    var request =
        new Idempotency.Request(
            ctx.tenant().value(),
            idempotencyKey,
            hash(ctx.tenant().value() + "\n" + cmd.canonical()));
    return switch (idempotency.begin(request, now)) {
      case Idempotency.Begin.Replay(var response) -> new Result.Replayed(response);
      case Idempotency.Begin.InFlight inFlight -> new Result.InFlight();
      case Idempotency.Begin.Mismatch mismatch -> new Result.Mismatch();
      case Idempotency.Begin.Started(var startedAt) -> create(ctx, cmd, request, startedAt, now);
    };
  }

  private Result create(
      RequestContext ctx,
      Command cmd,
      Idempotency.Request request,
      Instant startedAt,
      Instant now) {
    try {
      for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
        var fresh = material.create();
        var key =
            new StoredKey(
                ctx.tenant(),
                fresh.keyId(),
                fresh.environment(),
                cmd.name(),
                cmd.scopes(),
                defaultPlan,
                fresh.hash(),
                fresh.hint(),
                new KeyStatus.Active(),
                now,
                cmd.expiresAt(),
                1);
        var response =
            new Idempotency.StoredResponse(
                201, fresh.keyId().toString(), "/v1/keys/" + fresh.keyId());
        switch (idempotency.createAndComplete(request, startedAt, key, response)) {
          case Idempotency.Create.Created created -> {
            return new Result.Issued(key, fresh.fullKey());
          }
          case Idempotency.Create.LostClaim lost -> {
            return new Result.InFlight();
          }
          case Idempotency.Create.IdCollision collision -> {
            // a 128-bit id collided: astronomically rare unless the id source is broken; try again
          }
        }
      }
      idempotency.abandon(request, startedAt);
      return new Result.Failed(new KeysError.Conflict());
    } catch (RuntimeException e) {
      idempotency.abandon(request, startedAt);
      throw e;
    }
  }

  static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every JDK", e);
    }
  }
}
