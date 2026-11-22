package dev.keyedge.problem.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.problem.Problem;
import dev.keyedge.problem.ProblemException;
import dev.keyedge.problem.ProblemType;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@MicronautTest
class ProblemHandlersTest {
  @Inject
  @Client("/")
  HttpClient client;

  @Controller("/t")
  @Requires(env = "test")
  static class ThrowingController {
    @Get("/problem")
    String problem() {
      throw new ProblemException(
          Problem.of(ProblemType.RATE_LIMITED, "slow down").with(Problem.RETRY_AFTER_SECONDS, 7));
    }

    @Get("/bug")
    String bug() {
      throw new IllegalStateException("db password is hunter2");
    }
  }

  private HttpClientResponseException call(String path) {
    var req = HttpRequest.GET(path).header("X-Request-Id", "req-42");
    var ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            HttpClientResponseException.class,
            () ->
                client
                    .toBlocking()
                    .exchange(
                        req,
                        Argument.of(String.class),
                        Argument.mapOf(String.class, Object.class)));
    return ex;
  }

  @Test
  void problemExceptionBecomesProblemJsonWithRetryAfter() {
    var e = call("/t/problem");
    assertThat(e.getStatus().getCode()).isEqualTo(429);
    assertThat(e.getResponse().getContentType())
        .hasValueSatisfying(m -> assertThat(m.toString()).isEqualTo(ProblemResponses.MEDIA_TYPE));
    assertThat(e.getResponse().header("Retry-After")).isEqualTo("7");
    @SuppressWarnings("unchecked")
    Map<String, Object> body = e.getResponse().getBody(Map.class).orElseThrow();
    assertThat(body)
        .containsEntry("type", "urn:keyedge:problem:rate-limited")
        .containsEntry("detail", "slow down");
  }

  @Test
  void unexpectedExceptionsAreInternalWithoutTheMessage() {
    var e = call("/t/bug");
    assertThat(e.getStatus().getCode()).isEqualTo(500);
    String raw = e.getResponse().getBody(String.class).orElse("");
    assertThat(raw)
        .contains("urn:keyedge:problem:internal")
        .contains("req-42")
        .doesNotContain("hunter2");
  }

  @Test
  void retryAfterNeverGoesBelowOneSecond() {
    var r =
        ProblemResponses.of(
            Problem.of(ProblemType.OVERLOADED, null).with(Problem.RETRY_AFTER_SECONDS, 0.2));
    assertThat(r.header("Retry-After")).isEqualTo("1");
    assertThat(ProblemResponses.of(Problem.of(ProblemType.OVERLOADED, null)).header("Retry-After"))
        .isNull();
  }
}
