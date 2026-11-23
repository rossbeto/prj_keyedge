package dev.keyedge.edge;

import io.micronaut.runtime.Micronaut;

/** edge sidecar entry point. */
public final class Application {
  private Application() {}

  public static void main(String[] args) {
    dev.keyedge.ops.HealthProbe.exitIfRequested(args); // native images: --health-check <url>
    Micronaut.run(Application.class, args);
  }
}
