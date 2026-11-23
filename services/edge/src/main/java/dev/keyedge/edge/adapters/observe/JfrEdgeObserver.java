package dev.keyedge.edge.adapters.observe;

import dev.keyedge.edge.adapters.observe.jfr.RateLimitDecisionEvent;
import dev.keyedge.edge.adapters.observe.jfr.VerifyCacheMissEvent;
import dev.keyedge.edge.domain.Admission;
import dev.keyedge.edge.ports.AdmissionObserver;
import dev.keyedge.edge.ports.VerifyObserver;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.problem.ProblemType;
import java.time.Duration;

/**
 * JFR events for the decisions worth seeing in a recording (D-72). Cheap when no recording is
 * running: {@code shouldCommit()} guards every field assignment. Key ids are fine here (JFR files
 * stay with the operator); metrics never carry them.
 */
public final class JfrEdgeObserver implements AdmissionObserver, VerifyObserver {
  @Override
  public void decided(Admission admission, Duration took) {
    var event = new RateLimitDecisionEvent();
    if (!event.shouldCommit()) {
      return;
    }
    switch (admission) {
      case Admission.Admitted(var principal, var stale) -> {
        event.keyId = principal.keyId().toString();
        event.allowed = true;
        event.commit();
      }
      case Admission.Rejected r when r.problem() == ProblemType.RATE_LIMITED -> {
        event.keyId = r.principal().map(p -> p.keyId().toString()).orElse("");
        event.allowed = false;
        event.retryAfterMs = r.retryAfter().map(Duration::toMillis).orElse(0L);
        event.commit();
      }
      case Admission.Rejected r -> {} // not a rate-limit decision
    }
  }

  @Override
  public void loaded(KeyId keyId, long nanos, String kind, String outcome) {
    var event = new VerifyCacheMissEvent();
    if (event.shouldCommit()) {
      event.keyId = keyId.toString();
      event.kind = kind;
      event.outcome = outcome;
      event.loadNanos = nanos;
      event.commit();
    }
  }
}
