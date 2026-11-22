package dev.keyedge.sim.api;

import dev.keyedge.sim.app.Counters;
import dev.keyedge.sim.app.Respond;
import dev.keyedge.sim.app.Tokens;
import dev.keyedge.sim.domain.Scenario;
import dev.keyedge.sim.ports.ScenarioSource;
import dev.keyedge.sim.ports.Sleeper;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Token endpoint and the simulator's own controls: scenario switch, counters, reset. */
@Controller
@ExecuteOn(TaskExecutors.VIRTUAL)
public class AdminController {
  private final Respond respond;
  private final Counters counters;
  private final Tokens tokens;
  private final Sleeper sleeper;
  private final String clientAuth;
  private final ScenarioSource scenarios;

  AdminController(
      Respond respond,
      Counters counters,
      Tokens tokens,
      Sleeper sleeper,
      SimSettings settings,
      ScenarioSource scenarios) {
    this.scenarios = scenarios;
    this.respond = respond;
    this.counters = counters;
    this.tokens = tokens;
    this.sleeper = sleeper;
    this.clientAuth =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    (settings.clientId() + ":" + settings.clientSecret())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /**
   * OAuth2 client credentials (form body {@code grant_type=client_credentials}, Basic client auth).
   */
  @Post(value = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED)
  public HttpResponse<?> token(HttpRequest<?> request, @Body Map<String, String> form)
      throws InterruptedException {
    if (!clientAuth.equals(request.getHeaders().getAuthorization().orElse(""))
        || !"client_credentials".equals(form.get("grant_type"))) {
      return HttpResponse.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_client"));
    }
    Scenario.TokenPolicy policy = respond.scenario().token();
    sleeper.sleep(policy.issueLatency().sample(new java.util.SplittableRandom()));
    var issued = tokens.issue(Duration.ofSeconds(policy.ttlSeconds()));
    counters.tokenIssued();
    return HttpResponse.ok(
        Map.of(
            "access_token",
            issued.token(),
            "token_type",
            "Bearer",
            "expires_in",
            issued.expiresIn()));
  }

  /** Switch scenario: {@code ?name=brownout-pricing}, or a YAML body. */
  @Put("/__chaos")
  @Consumes({MediaType.APPLICATION_YAML, MediaType.TEXT_PLAIN, MediaType.ALL})
  public Map<String, Object> chaos(@QueryValue @Nullable String name, @Body @Nullable String yaml) {
    try {
      Scenario s = name != null ? scenarios.named(name) : scenarios.parse(yaml == null ? "" : yaml);
      respond.apply(s);
      return Map.of("scenario", s.name(), "seed", s.seed());
    } catch (IllegalArgumentException | ClassCastException e) {
      return Map.of("error", String.valueOf(e.getMessage()));
    }
  }

  @Get("/__stats")
  public Map<String, Object> stats() {
    var s = new java.util.TreeMap<String, Object>(counters.snapshot());
    s.put("scenario", respond.scenario().name());
    return s;
  }

  /** Zero the counters, forget tokens, restart the scenario's random sequences. */
  @Post("/__reset")
  public Map<String, Object> reset() {
    counters.reset();
    tokens.clear();
    respond.apply(respond.scenario());
    return Map.of("reset", true, "scenario", respond.scenario().name());
  }
}
