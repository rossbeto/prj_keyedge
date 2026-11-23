package dev.keyedge.fanout;

import java.util.concurrent.Callable;

/**
 * Carries the caller's context (e.g. a {@code ScopedValue}) into tasks on other threads. Captured
 * on the calling thread; {@link Binder#wrap} re-binds it around the task. Structured concurrency
 * inherits scoped values by itself; an executor does not (D-13), hence this.
 */
@FunctionalInterface
public interface ContextCarrier {
  /** Called on the caller's thread: returns a wrapper that re-binds what it captured. */
  Binder capture();

  /** Re-binds the captured context around a task. */
  @FunctionalInterface
  interface Binder {
    <T> Callable<T> wrap(Callable<T> task);
  }

  /** Carries nothing. */
  static ContextCarrier none() {
    return () ->
        new Binder() {
          @Override
          public <T> Callable<T> wrap(Callable<T> task) {
            return task;
          }
        };
  }
}
