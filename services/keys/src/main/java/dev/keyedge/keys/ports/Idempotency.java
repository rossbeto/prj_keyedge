package dev.keyedge.keys.ports;

import dev.keyedge.keys.domain.StoredKey;
import java.time.Instant;

/**
 * The idempotency record of a request and the create that completes it, in one atomic write (key +
 * record): a key never exists without its record saying DONE.
 */
public interface Idempotency {
  /** Claims the key for a request. */
  Begin begin(Request request, Instant now);

  /** Creates the key and marks the request DONE in one transaction. */
  Create createAndComplete(
      Request request, Instant startedAt, StoredKey key, StoredResponse response);

  /** Releases a claim after a failure so the client may retry. */
  void abandon(Request request, Instant startedAt);

  /** Tenant, client key and the hash of what was asked. */
  record Request(String tenant, String idempotencyKey, String requestHash) {}

  /** What a replay returns (never the secret: it is shown exactly once). */
  record StoredResponse(int status, String keyId, String location) {}

  /** Outcome of {@link #begin}. */
  sealed interface Begin {
    /** This request owns the key now. */
    record Started(Instant startedAt) implements Begin {}

    /** Done before with the same request: replay. */
    record Replay(StoredResponse response) implements Begin {}

    /** Another request with this key is in flight. */
    record InFlight() implements Begin {}

    /** The key was used with a different request. */
    record Mismatch() implements Begin {}
  }

  /** Outcome of {@link #createAndComplete}. */
  sealed interface Create {
    record Created() implements Create {}

    /** The generated key id exists already: retry with another id. */
    record IdCollision() implements Create {}

    /** The claim was taken over (it timed out): report a conflict. */
    record LostClaim() implements Create {}
  }
}
