package dev.keyedge.issuer;

import io.micronaut.runtime.Micronaut;

/** issuer entry point. */
public final class Application {
  private Application() {}

  public static void main(String[] args) {
    Micronaut.run(Application.class, args);
  }
}
