package dev.keyedge.fanout;

/**
 * One unit of a fan-out. {@code required}: if it does not succeed the whole fan-out has no answer,
 * so the others are cancelled.
 */
public record Call<T>(String name, boolean required, Work<T> work) {
  /** The blocking work; checked exceptions allowed (they become {@link CallResult.Failed}). */
  @FunctionalInterface
  public interface Work<T> {
    T run() throws Exception;
  }

  public static <T> Call<T> required(String name, Work<T> work) {
    return new Call<>(name, true, work);
  }

  public static <T> Call<T> optional(String name, Work<T> work) {
    return new Call<>(name, false, work);
  }
}
