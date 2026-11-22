package dev.keyedge.testkit.fixtures.purelib.clean;

import java.time.InstantSource;

/** Uses only the JDK and gets time injected. */
public final class Pure {
  private final InstantSource clock;

  public Pure(InstantSource clock) {
    this.clock = clock;
  }

  public long millis() {
    return clock.millis();
  }
}
