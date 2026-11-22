package dev.keyedge.keyformat;

/** Rate limit attached to a key: sustained permits per second and a burst. */
public record RatePlan(long permitsPerSecond, long burst) {
  public RatePlan {
    if (permitsPerSecond < 1 || burst < 1) {
      throw new IllegalArgumentException("rate and burst must be positive");
    }
  }
}
