package dev.keyedge.keys.fakes;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/** A clock tests move by hand. */
public final class MutableClock implements InstantSource {
  private Instant now;

  public MutableClock(Instant start) {
    this.now = start;
  }

  @Override
  public Instant instant() {
    return now;
  }

  public void advance(Duration d) {
    now = now.plus(d);
  }
}
