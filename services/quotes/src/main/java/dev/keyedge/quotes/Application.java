package dev.keyedge.quotes;

import io.micronaut.runtime.Micronaut;

/** quotes entry point. */
public final class Application {
  private Application() {}

  public static void main(String[] args) {
    dev.keyedge.ops.HealthProbe.exitIfRequested(args); // native images: --health-check <url>
    // request fields for every log line, read from the ScopedValue at log time (Phase 011)
    dev.keyedge.observe.LogFields.register(() -> {
      if (!dev.keyedge.quotes.ports.RequestContext.CURRENT.isBound()) {
        return java.util.Map.of();
      }
      var c = dev.keyedge.quotes.ports.RequestContext.CURRENT.get();
      return java.util.Map.of("request_id", c.requestId(), "tenant", c.tenant());
    });
    Micronaut.run(Application.class, args);
  }
}
