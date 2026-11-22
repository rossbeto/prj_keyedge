package dev.keyedge.quotes.api;

import dev.keyedge.keyformat.PrincipalHeader;
import dev.keyedge.problem.Problem;
import dev.keyedge.problem.ProblemType;
import dev.keyedge.problem.micronaut.ProblemResponses;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import java.time.Duration;
import java.time.InstantSource;
import org.reactivestreams.Publisher;

/**
 * The app trusts only requests that carry a principal signed by its sidecar (D-26): "it came from
 * localhost" is not an identity — a port exposed by mistake must not bypass the sidecar. The
 * verified principal is put on the request as attribute {@code keyedge.principal}.
 */
@Filter("/v1/**")
public class PrincipalFilter implements HttpServerFilter {
  /** Request attribute holding the verified {@link PrincipalHeader.Principal}. */
  public static final String ATTRIBUTE = "keyedge.principal";

  private final byte[] key;
  private final InstantSource clock;
  private final Duration maxAge;

  PrincipalFilter(PrincipalKey key, InstantSource clock) {
    this.key = key.bytes();
    this.clock = clock;
    this.maxAge = Duration.ofSeconds(30);
  }

  /** The principal-signing key shared with the sidecar. */
  public record PrincipalKey(byte[] bytes) {}

  @Override
  public Publisher<MutableHttpResponse<?>> doFilter(
      HttpRequest<?> request, ServerFilterChain chain) {
    String principal = request.getHeaders().get(PrincipalHeader.PRINCIPAL);
    String signature = request.getHeaders().get(PrincipalHeader.SIGNATURE);
    if (principal != null && signature != null) {
      var verified =
          PrincipalHeader.verify(
              key,
              principal,
              signature,
              request.getMethodName(),
              request.getPath(),
              clock.instant(),
              maxAge);
      if (verified.isPresent()) {
        request.setAttribute(ATTRIBUTE, verified.get());
        return chain.proceed(request);
      }
    }
    return Publishers.just(
        ProblemResponses.of(Problem.of(ProblemType.UNAUTHENTICATED, "not signed by the sidecar")));
  }
}
