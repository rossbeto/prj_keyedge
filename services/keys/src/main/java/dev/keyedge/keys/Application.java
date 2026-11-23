package dev.keyedge.keys;

import io.micronaut.runtime.Micronaut;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/** keys service entry point. */
@OpenAPIDefinition(
    info =
        @Info(
            title = "keyedge keys",
            version = "1",
            description = "Issue, rotate, revoke and verify API keys"))
public final class Application {
  private Application() {}

  public static void main(String[] args) {
    dev.keyedge.ops.HealthProbe.exitIfRequested(args); // native images: --health-check <url>
    // request fields for every log line, read from the ScopedValue at log time (Phase 011)
    dev.keyedge.observe.LogFields.register(() -> {
      if (!dev.keyedge.keys.app.RequestContext.CURRENT.isBound()) {
        return java.util.Map.of();
      }
      var c = dev.keyedge.keys.app.RequestContext.CURRENT.get();
      return java.util.Map.of("request_id", c.requestId(), "tenant", c.tenant().value());
    });
    Micronaut.run(Application.class, args);
  }
}
