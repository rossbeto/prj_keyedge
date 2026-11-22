package dev.keyedge.problem.micronaut;

import dev.keyedge.problem.Problem;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import java.util.Map;

/** Builds the HTTP response for a problem: status, media type, body members, Retry-After. */
public final class ProblemResponses {
  /** RFC 9457 media type. */
  public static final String MEDIA_TYPE = "application/problem+json";

  private ProblemResponses() {}

  public static MutableHttpResponse<Map<String, Object>> of(Problem problem) {
    MutableHttpResponse<Map<String, Object>> response =
        HttpResponse.<Map<String, Object>>status(HttpStatus.valueOf(problem.status()))
            .contentType(MEDIA_TYPE)
            .body(problem.members());
    if (problem.extensions().get(Problem.RETRY_AFTER_SECONDS) instanceof Number seconds) {
      response.header("Retry-After", Long.toString(Math.max(1, seconds.longValue())));
    }
    return response;
  }
}
