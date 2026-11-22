package dev.keyedge.problem.micronaut;

import dev.keyedge.problem.ProblemException;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;

/** Renders a {@link ProblemException} thrown by an adapter. */
@Singleton
@Produces(ProblemResponses.MEDIA_TYPE)
@Requires(classes = ProblemException.class)
public class ProblemExceptionHandler
    implements ExceptionHandler<ProblemException, HttpResponse<?>> {
  @Override
  public HttpResponse<?> handle(HttpRequest request, ProblemException exception) {
    return ProblemResponses.of(exception.problem());
  }
}
