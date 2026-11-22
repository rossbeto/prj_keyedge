package dev.keyedge.sim.adapters;

import dev.keyedge.sim.ports.Sleeper;
import java.time.Duration;

/** Sleeps the current (virtual) thread: the carrier is free meanwhile. */
public final class VirtualThreadSleeper implements Sleeper {
  @Override
  public void sleep(Duration d) throws InterruptedException {
    if (!d.isZero() && !d.isNegative()) {
      Thread.sleep(d);
    }
  }
}
