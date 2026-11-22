package dev.keyedge.keys.api;

import dev.keyedge.keyformat.TenantId;
import dev.keyedge.keys.app.RequestContext;
import dev.keyedge.problem.Problem;
import dev.keyedge.problem.ProblemException;
import dev.keyedge.problem.ProblemType;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.MDC;

/**
 * Turns an authenticated request into a {@link RequestContext}: tenant and scopes from the JWT only
 * (never from the path or body), request id from {@code X-Request-Id}. Runs the use case with the
 * context bound to a {@code ScopedValue} and MDC, for logs.
 */
public final class Callers {
  private final InstantSource clock;
  private final Duration deadline;

  public Callers(InstantSource clock, Duration deadline) {
    this.clock = clock;
    this.deadline = deadline;
  }

  RequestContext context(HttpRequest<?> request, Authentication auth, String requiredScope) {
    if (!(auth.getAttributes().get("tenant") instanceof String tenant)) {
      throw new ProblemException(
          Problem.of(ProblemType.UNAUTHENTICATED, "token has no tenant claim"));
    }
    Set<String> scopes =
        auth.getAttributes().get("scope") instanceof String s
            ? Arrays.stream(s.split(" ")).filter(x -> !x.isBlank()).collect(Collectors.toSet())
            : Set.of();
    if (!scopes.contains(requiredScope)) {
      throw new ProblemException(
          Problem.of(ProblemType.MISSING_SCOPE, null).with("requiredScope", requiredScope));
    }
    String requestId =
        request
            .getHeaders()
            .get("X-Request-Id", String.class)
            .orElseGet(() -> UUID.randomUUID().toString());
    try {
      return new RequestContext(requestId, new TenantId(tenant), clock.instant().plus(deadline));
    } catch (IllegalArgumentException e) {
      throw new ProblemException(Problem.of(ProblemType.UNAUTHENTICATED, "invalid tenant claim"));
    }
  }

  static <T> T run(RequestContext ctx, Supplier<T> useCase) {
    try (var mdc = MDC.putCloseable("requestId", ctx.requestId());
        var tenant = MDC.putCloseable("tenant", ctx.tenant().value())) {
      return ScopedValue.where(RequestContext.CURRENT, ctx).call(useCase::get);
    }
  }
}
