package dev.keyedge.sim.ports;

import java.time.Duration;

/** Waits; a fake in tests, {@code Thread.sleep} on a virtual thread in production. */
@FunctionalInterface
public interface Sleeper {
  void sleep(Duration d) throws InterruptedException;
}
