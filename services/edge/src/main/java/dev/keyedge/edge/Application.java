package dev.keyedge.edge;

import io.micronaut.runtime.Micronaut;

/** edge sidecar entry point. */
public final class Application {
  private Application() {}

  public static void main(String[] args) {
    Micronaut.run(Application.class, args);
  }
}
