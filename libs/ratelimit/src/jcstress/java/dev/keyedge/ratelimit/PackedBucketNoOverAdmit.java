package dev.keyedge.ratelimit;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.keyedge.keyformat.RatePlan;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.ZZ_Result;

/** One token, frozen time, two racing actors: exactly one may win. */
@JCStressTest
@Outcome(id = "true, false", expect = ACCEPTABLE, desc = "actor 1 won")
@Outcome(id = "false, true", expect = ACCEPTABLE, desc = "actor 2 won")
@Outcome(id = "true, true", expect = FORBIDDEN, desc = "over-admission")
@Outcome(id = "false, false", expect = FORBIDDEN, desc = "the token was lost")
@State
public class PackedBucketNoOverAdmit {
  private final PackedTokenBucket bucket =
      new PackedTokenBucket(new RatePlan(1, 1), () -> 7_000_000_000L);

  @Actor
  public void actor1(ZZ_Result r) {
    r.r1 = bucket.tryAcquire().allowed();
  }

  @Actor
  public void actor2(ZZ_Result r) {
    r.r2 = bucket.tryAcquire().allowed();
  }
}
