package dev.keyedge.edge.app;

import dev.keyedge.edge.domain.Admission;
import dev.keyedge.edge.domain.RouteScopes;
import dev.keyedge.edge.ports.AdmissionObserver;
import dev.keyedge.edge.ports.KeyLimits;
import dev.keyedge.edge.ports.Verdicts;
import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyFormat;
import dev.keyedge.keyformat.ParseResult;
import dev.keyedge.keyformat.PrincipalHeader.Principal;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.problem.ProblemType;
import java.time.Duration;
import java.util.Optional;

/**
 * The sidecar's decision, in the order of cost: route exposed? (no I/O) → key well formed and
 * checksum valid? (no I/O) → verdict (cached, single flight) → scope → per-key rate limit. Expected
 * outcomes are values; only an unreachable control plane is an exception, caught here.
 */
public final class AdmitRequest {
  private final RouteScopes routes;
  private final Verdicts verdicts;
  private final KeyLimits limits;
  private final AdmissionObserver observer;
  private final java.util.function.LongSupplier nanos;

  public AdmitRequest(RouteScopes routes, Verdicts verdicts, KeyLimits limits) {
    this(routes, verdicts, limits, AdmissionObserver.none(), () -> 0L);
  }

  public AdmitRequest(
      RouteScopes routes,
      Verdicts verdicts,
      KeyLimits limits,
      AdmissionObserver observer,
      java.util.function.LongSupplier nanos) {
    this.routes = routes;
    this.verdicts = verdicts;
    this.limits = limits;
    this.observer = observer;
    this.nanos = nanos;
  }

  public Admission admit(String method, String path, Optional<String> authorization) {
    long start = nanos.getAsLong();
    Admission admission = decide(method, path, authorization);
    observer.decided(admission, java.time.Duration.ofNanos(nanos.getAsLong() - start));
    return admission;
  }

  private Admission decide(String method, String path, Optional<String> authorization) {
    Optional<Scope> needed = routes.required(method, path);
    if (needed.isEmpty()) {
      return Admission.Rejected.of(ProblemType.NOT_FOUND);
    }
    Optional<String> bearer =
        authorization.filter(a -> a.startsWith("Bearer ")).map(a -> a.substring(7).trim());
    if (bearer.isEmpty()) {
      return Admission.Rejected.of(ProblemType.UNAUTHENTICATED);
    }
    if (!(KeyFormat.parse(bearer.get()) instanceof ParseResult.Parsed parsed)) {
      return Admission.Rejected.of(ProblemType.MALFORMED_KEY);
    }
    Verdicts.Answer answer;
    try {
      answer = verdicts.answer(parsed.keyId(), parsed.secret());
    } catch (Verdicts.Unavailable e) {
      // 503, not 401: the key may well be valid, the client should retry (D-27)
      Duration retry = e.retryAfter().orElse(Duration.ofSeconds(1));
      return new Admission.Rejected(
          ProblemType.UPSTREAM_UNAVAILABLE,
          Optional.of(retry.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : retry),
          Optional.empty());
    }
    boolean stale = answer.stale();
    return switch (answer.check()) {
      case KeyCheck.Valid v when !v.scopes().contains(needed.get()) ->
          new Admission.Rejected(
              ProblemType.MISSING_SCOPE, Optional.empty(), Optional.of(principal(v)));
      case KeyCheck.Valid v ->
          limits
              .tryAcquire(v.keyId(), v.plan())
              .<Admission>map(wait ->
                  new Admission.Rejected(
                      ProblemType.RATE_LIMITED, Optional.of(wait), Optional.of(principal(v))))
              .orElseGet(() -> new Admission.Admitted(principal(v), stale));
      case KeyCheck.Malformed m -> Admission.Rejected.of(ProblemType.MALFORMED_KEY);
      case KeyCheck.Revoked r -> Admission.Rejected.of(ProblemType.REVOKED_KEY);
      case KeyCheck.Expired e -> Admission.Rejected.of(ProblemType.EXPIRED_KEY);
      case KeyCheck.Unknown u -> Admission.Rejected.of(ProblemType.UNKNOWN_KEY);
      case KeyCheck.RotatedOut r ->
          Admission.Rejected.of(ProblemType.UNKNOWN_KEY); // never reveal it existed
    };
  }

  private static Principal principal(KeyCheck.Valid v) {
    return new Principal(v.tenant(), v.keyId(), v.scopes());
  }
}
