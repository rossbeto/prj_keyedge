package dev.keyedge.testkit.fixtures.bad.api;

import java.time.Instant;

/** Breaks: noClockReadsOutsideWiring. */
public final class ReadsClock {
  public Instant now() {
    return Instant.now();
  }
}
