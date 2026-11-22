package dev.keyedge.ratelimit;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.keyedge.ratelimit.concurrency.FixedLimiter;
import dev.keyedge.ratelimit.concurrency.Permit;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.JJ_Result;

/**
 * Two threads release the same permit: in-flight returns to 0 and exactly one release is a double.
 */
@JCStressTest
@Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "released once, one double release counted")
@Outcome(expect = FORBIDDEN, desc = "in-flight count corrupted")
@State
public class PermitReleasedOnce {
  private final FixedLimiter limiter = new FixedLimiter(1);
  private final Permit permit = limiter.tryAcquire().orElseThrow();

  @Actor
  public void actor1() {
    permit.onSuccess(1);
  }

  @Actor
  public void actor2() {
    permit.onDropped();
  }

  @Arbiter
  public void arbiter(JJ_Result r) {
    r.r1 = limiter.inFlight();
    r.r2 = limiter.doubleReleases();
  }
}
