package dev.keyedge.edge.ports;

import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.KeySecret;
import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The control plane's verdict for a presented key (through the cache). */
public interface Verdicts {
  /** The verdict; throws {@link Unavailable} when the control plane cannot answer in time. */
  KeyCheck verdict(KeyId keyId, KeySecret secret);

  /**
   * The verdict and whether it is stale (served past its expiry because the control plane is
   * unavailable, fail-static D-27). The default never serves stale.
   */
  default Answer answer(KeyId keyId, KeySecret secret) {
    return new Answer(verdict(keyId, secret), false);
  }

  /** A verdict and its freshness. */
  record Answer(KeyCheck check, boolean stale) {}

  /**
   * The control plane is unreachable, too slow, or the breaker is open. {@code retryAfter} is a
   * hint for clients (e.g. the breaker's remaining open time).
   */
  final class Unavailable extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Why: only {@link Reason#CONTRACT} is not a sign that the control plane is down. */
    public enum Reason {
      DOWN,
      CONTRACT,
      CIRCUIT_OPEN,
      BULKHEAD_FULL
    }

    private final Reason reason;
    private final @Nullable Duration retryAfter;

    public Unavailable(String message, @Nullable Throwable cause) {
      this(message, cause, Reason.DOWN, null);
    }

    public Unavailable(
        String message, @Nullable Throwable cause, Reason reason, @Nullable Duration retryAfter) {
      super(message, cause);
      this.reason = reason;
      this.retryAfter = retryAfter;
    }

    public Reason reason() {
      return reason;
    }

    public Optional<Duration> retryAfter() {
      return Optional.ofNullable(retryAfter);
    }
  }
}
