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
    Micronaut.run(Application.class, args);
  }
}
