package dev.keyedge.edge.domain;

import dev.keyedge.keyformat.PrincipalHeader.Principal;
import dev.keyedge.problem.ProblemType;
import java.time.Duration;
import java.util.Optional;

/** The sidecar's decision for one request. */
public sealed interface Admission {
  /** Forward it, as this principal; {@code stale}: on a verdict served past expiry (D-27). */
  record Admitted(Principal principal, boolean stale) implements Admission {}

  /** Answer it here with a problem; never reaches the app. */
  record Rejected(ProblemType problem, Optional<Duration> retryAfter, Optional<Principal> principal)
      implements Admission {
    public static Rejected of(ProblemType problem) {
      return new Rejected(problem, Optional.empty(), Optional.empty());
    }
  }
}
