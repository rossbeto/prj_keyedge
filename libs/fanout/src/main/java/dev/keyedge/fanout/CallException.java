package dev.keyedge.fanout;

import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** An upstream answered with an error status, or timed out on its own. */
public final class CallException extends Exception {
  private static final long serialVersionUID = 1L;
  private final @Nullable Integer status;
  private final @Nullable Duration retryAfter;
  private final boolean timedOut;

  private CallException(
      String message, @Nullable Integer status, @Nullable Duration retryAfter, boolean timedOut) {
    super(message);
    this.status = status;
    this.retryAfter = retryAfter;
    this.timedOut = timedOut;
  }

  public static CallException status(String upstream, int status, @Nullable Duration retryAfter) {
    return new CallException(upstream + " answered HTTP " + status, status, retryAfter, false);
  }

  public static CallException timeout(String upstream) {
    return new CallException(upstream + " timed out", null, null, true);
  }

  public static CallException io(String upstream, String what) {
    return new CallException(upstream + ": " + what, null, null, false);
  }

  public Optional<Integer> status() {
    return Optional.ofNullable(status);
  }

  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }

  public boolean timedOut() {
    return timedOut;
  }

  /** A 4xx other than 429 is the caller's problem, not the upstream's health. */
  public boolean clientError() {
    return status != null && status >= 400 && status < 500 && status != 429;
  }
}
