package dev.keyedge.keyformat;

/** Version of the server-side pepper a hash was made with; stored next to every hash. */
public record PepperVersion(int value) {
  public PepperVersion {
    if (value < 1) {
      throw new IllegalArgumentException("pepper versions start at 1");
    }
  }
}
