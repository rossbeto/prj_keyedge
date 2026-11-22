package dev.keyedge.edge.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.edge.domain.Admission;
import dev.keyedge.edge.domain.RouteScopes;
import dev.keyedge.edge.ports.Verdicts;
import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyEnvironment;
import dev.keyedge.keyformat.KeyFormat;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.KeySecret;
import dev.keyedge.keyformat.RatePlan;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.keyformat.TenantId;
import dev.keyedge.problem.ProblemType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdmitRequestTest {
  private static final KeyId ID = new KeyId(7, 7);
  private static final KeySecret SECRET = KeySecret.ofBytes(new byte[32]);
  private static final String KEY = "Bearer " + KeyFormat.format(KeyEnvironment.TEST, ID, SECRET);
  private final RouteScopes routes =
      new RouteScopes(
          List.of(
              new RouteScopes.Rule("GET", "/v1/quotes", new Scope("quotes:read")),
              new RouteScopes.Rule("*", "/v1/admin", new Scope("admin:all"))));

  private Optional<Duration> limit = Optional.empty();
  private KeyCheck verdict =
      new KeyCheck.Valid(
          new TenantId("acme"),
          ID,
          Set.of(new Scope("quotes:read")),
          new RatePlan(1, 1),
          Duration.ofSeconds(60));
  private boolean down;

  private Admission admit(String method, String path, Optional<String> auth) {
    Verdicts v =
        (id, s) -> {
          if (down) {
            throw new Verdicts.Unavailable("down", null);
          }
          return verdict;
        };
    return new AdmitRequest(routes, v, (id, plan) -> limit).admit(method, path, auth);
  }

  private static ProblemType problem(Admission a) {
    return ((Admission.Rejected) a).problem();
  }

  @Test
  void admitsAValidKeyWithTheRightScope() {
    var a = admit("GET", "/v1/quotes/sku-1", Optional.of(KEY));
    assertThat(a)
        .isInstanceOfSatisfying(
            Admission.Admitted.class,
            ad -> assertThat(ad.principal().tenant().value()).isEqualTo("acme"));
  }

  @Test
  void cheapChecksComeFirst() {
    assertThat(problem(admit("GET", "/v1/unknown", Optional.of(KEY))))
        .isEqualTo(ProblemType.NOT_FOUND);
    assertThat(problem(admit("POST", "/v1/quotes/x", Optional.of(KEY))))
        .isEqualTo(ProblemType.NOT_FOUND);
    assertThat(problem(admit("GET", "/v1/quotes/x", Optional.empty())))
        .isEqualTo(ProblemType.UNAUTHENTICATED);
    assertThat(problem(admit("GET", "/v1/quotes/x", Optional.of("Basic abc"))))
        .isEqualTo(ProblemType.UNAUTHENTICATED);
    assertThat(problem(admit("GET", "/v1/quotes/x", Optional.of("Bearer ke_live_typo"))))
        .isEqualTo(ProblemType.MALFORMED_KEY);
  }

  @Test
  void scopeAndRateLimit() {
    assertThat(problem(admit("DELETE", "/v1/admin/x", Optional.of(KEY))))
        .isEqualTo(ProblemType.MISSING_SCOPE);
    limit = Optional.of(Duration.ofMillis(1500));
    var r = (Admission.Rejected) admit("GET", "/v1/quotes", Optional.of(KEY));
    assertThat(r.problem()).isEqualTo(ProblemType.RATE_LIMITED);
    assertThat(r.retryAfter()).contains(Duration.ofMillis(1500));
    assertThat(r.principal()).isPresent();
  }

  @Test
  void everyRejectionVerdictAndAnUnavailableControlPlane() {
    var at = Instant.EPOCH;
    for (var c :
        List.of(
            Optional.of(new KeyCheck.Unknown(ID))
                .map(k -> (KeyCheck) k)
                .map(k -> List.<Object>of(k, ProblemType.UNKNOWN_KEY))
                .get(),
            List.<Object>of(new KeyCheck.Revoked(ID, at), ProblemType.REVOKED_KEY),
            List.<Object>of(new KeyCheck.Expired(ID, at), ProblemType.EXPIRED_KEY),
            List.<Object>of(new KeyCheck.RotatedOut(ID, at), ProblemType.UNKNOWN_KEY),
            List.<Object>of(
                new KeyCheck.Malformed(dev.keyedge.keyformat.MalformedReason.CHARSET),
                ProblemType.MALFORMED_KEY))) {
      verdict = (KeyCheck) c.get(0);
      assertThat(problem(admit("GET", "/v1/quotes", Optional.of(KEY)))).isEqualTo(c.get(1));
    }
    down = true;
    var r = (Admission.Rejected) admit("GET", "/v1/quotes", Optional.of(KEY));
    assertThat(r.problem()).isEqualTo(ProblemType.UPSTREAM_UNAVAILABLE);
    assertThat(r.retryAfter()).contains(Duration.ofSeconds(1));
  }

  @Test
  void routePrefixesMatchWholeSegmentsAndTheLongestWins() {
    var r =
        new RouteScopes(
            List.of(
                new RouteScopes.Rule("GET", "/v1", new Scope("a:b")),
                new RouteScopes.Rule("get", "/v1/quotes", new Scope("quotes:read"))));
    assertThat(r.required("GET", "/v1/quotes/x")).contains(new Scope("quotes:read"));
    assertThat(r.required("GET", "/v1/quotesX")).contains(new Scope("a:b"));
    assertThat(r.required("GET", "/v2")).isEmpty();
    assertThat(r.required("GET", "/v1")).contains(new Scope("a:b"));
  }
}
