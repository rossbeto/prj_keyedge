package dev.keyedge.fanout;

import java.util.Optional;

/** The outcome of one call: expected failures are values, not exceptions. */
public sealed interface CallResult<T> {
  /** It answered. */
  record Ok<T>(T value) implements CallResult<T> {}

  /** It failed: {@code status} is the upstream's HTTP status when there was one. */
  record Failed<T>(String reason, Optional<Integer> status) implements CallResult<T> {}

  /** The deadline (or its own timeout) passed first. */
  record TimedOut<T>() implements CallResult<T> {}

  /** Never finished: cancelled because a required sibling failed. */
  record Skipped<T>(String reason) implements CallResult<T> {}

  default boolean ok() {
    return this instanceof Ok;
  }

  /** Runs the call's work and maps every outcome to a result; never throws. */
  static <T> CallResult<T> of(Call<T> call) {
    try {
      return new Ok<>(call.work().run());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Skipped<>("cancelled");
    } catch (CallException e) {
      return e.timedOut()
          ? new TimedOut<>()
          : new Failed<>(String.valueOf(e.getMessage()), e.status());
    } catch (java.util.concurrent.TimeoutException | java.net.http.HttpTimeoutException e) {
      return new TimedOut<>();
    } catch (Exception e) {
      if (Thread.currentThread().isInterrupted()) {
        return new Skipped<>("cancelled");
      }
      return new Failed<>(e.getClass().getSimpleName(), Optional.empty());
    }
  }
}
