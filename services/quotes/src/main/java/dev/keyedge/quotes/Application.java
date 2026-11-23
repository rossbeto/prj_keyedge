package dev.keyedge.quotes;

import io.micronaut.runtime.Micronaut;

/** quotes entry point. */
public final class Application {
  private Application() {}

  public static void main(String[] args) {
    dev.keyedge.ops.HealthProbe.exitIfRequested(args); // native images: --health-check <url>
    Micronaut.run(Application.class, args);
  }
}
