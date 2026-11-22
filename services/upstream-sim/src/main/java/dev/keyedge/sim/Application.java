package dev.keyedge.sim;

import io.micronaut.runtime.Micronaut;

/** upstream-sim entry point. */
public final class Application {
  private Application() {}

  public static void main(String[] args) {
    Micronaut.run(Application.class, args);
  }
}
