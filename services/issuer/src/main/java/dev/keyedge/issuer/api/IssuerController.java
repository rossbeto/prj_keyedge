package dev.keyedge.issuer.api;

import dev.keyedge.issuer.app.IssueToken;
import dev.keyedge.issuer.ports.TokenSigner;
import dev.keyedge.problem.Problem;
import dev.keyedge.problem.ProblemException;
import dev.keyedge.problem.ProblemType;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.serde.annotation.Serdeable;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * JWKS for verifiers; a token endpoint for demos and tests (bound to loopback by configuration).
 */
@Controller
public class IssuerController {
  private final IssueToken issue;
  private final TokenSigner signer;

  IssuerController(IssueToken issue, TokenSigner signer) {
    this.issue = issue;
    this.signer = signer;
  }

  /** POST /token body. */
  @Serdeable
  public record TokenRequest(
      String tenant, @Nullable List<String> scopes, @Nullable Long ttlSeconds) {}

  /** Response (OAuth2 token-response shape). */
  @Serdeable
  public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}

  @Get("/.well-known/jwks.json")
  @Produces(MediaType.APPLICATION_JSON)
  public String jwks() {
    return signer.publicJwks();
  }

  @Post("/token")
  public TokenResponse token(@Body TokenRequest request) {
    try {
      var issued =
          issue.issue(
              request.tenant(),
              request.scopes() == null ? List.of() : request.scopes(),
              Duration.ofSeconds(request.ttlSeconds() == null ? 3600 : request.ttlSeconds()));
      return new TokenResponse(issued.accessToken(), "Bearer", issued.expiresIn());
    } catch (IllegalArgumentException e) {
      throw new ProblemException(Problem.of(ProblemType.VALIDATION, e.getMessage()));
    }
  }
}
