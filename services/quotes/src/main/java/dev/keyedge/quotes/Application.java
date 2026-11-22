package dev.keyedge.quotes;

import io.micronaut.runtime.Micronaut;

/** quotes entry point. */
public final class Application {
  private Application() {}

  public static void main(String[] args) {
    Micronaut.run(Application.class, args);
  }
}
