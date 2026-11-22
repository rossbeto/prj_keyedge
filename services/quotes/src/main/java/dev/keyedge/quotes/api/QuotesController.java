package dev.keyedge.quotes.api;

import dev.keyedge.keyformat.PrincipalHeader;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.util.Map;

/**
 * Phase 007 stub: proves the principal arrives; Phase 009 replaces it with the real quote use case.
 */
@Controller("/v1/quotes")
@ExecuteOn(TaskExecutors.VIRTUAL)
public class QuotesController {
  @Get("/{sku}")
  public Map<String, Object> quote(HttpRequest<?> request, @PathVariable String sku) {
    var principal =
        request
            .getAttribute(PrincipalFilter.ATTRIBUTE, PrincipalHeader.Principal.class)
            .orElseThrow();
    return Map.of(
        "sku",
        sku,
        "tenant",
        principal.tenant().value(),
        "keyId",
        principal.keyId().toString(),
        "stub",
        true);
  }
}
