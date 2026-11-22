package dev.keyedge.problem.micronaut;

import dev.keyedge.problem.Problem;
import dev.keyedge.problem.ProblemType;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Last resort for bugs: logs once with the request id and returns {@code internal} without the
 * exception text (messages can contain secrets or internal names). Framework exceptions with their
 * own, more specific handlers (bad JSON, 404, validation) are not caught here.
 */
@Singleton
@Produces(ProblemResponses.MEDIA_TYPE)
public class UnexpectedExceptionHandler
    implements ExceptionHandler<RuntimeException, HttpResponse<?>> {
  private static final Logger LOG = LoggerFactory.getLogger(UnexpectedExceptionHandler.class);

  @Override
  public HttpResponse<?> handle(HttpRequest request, RuntimeException exception) {
    String requestId = request.getHeaders().get("X-Request-Id", String.class).orElse("-");
    LOG.error("unexpected error, request id {}", requestId, exception);
    return ProblemResponses.of(
        Problem.of(ProblemType.INTERNAL, "See the server log for request id " + requestId));
  }
}
