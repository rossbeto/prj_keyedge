package dev.keyedge.ratelimit;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE_INTERESTING;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.keyedge.keyformat.RatePlan;
import dev.keyedge.ratelimit.lab.pitfalls.NaiveTokenBucket;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.ZZ_Result;

/**
 * The same race on the deliberately wrong bucket: "true, true" is the bug, expected to be observed.
 */
@JCStressTest
@Outcome(id = "true, false", expect = ACCEPTABLE, desc = "actor 1 won")
@Outcome(id = "false, true", expect = ACCEPTABLE, desc = "actor 2 won")
@Outcome(
    id = "true, true",
    expect = ACCEPTABLE_INTERESTING,
    desc = "over-admission: lost update between read and write")
@Outcome(id = "false, false", expect = FORBIDDEN, desc = "the token was lost")
@State
public class NaiveBucketOverAdmits {
  private final NaiveTokenBucket bucket =
      new NaiveTokenBucket(new RatePlan(1, 1), () -> 7_000_000_000L);

  @Actor
  public void actor1(ZZ_Result r) {
    r.r1 = bucket.tryAcquire();
  }

  @Actor
  public void actor2(ZZ_Result r) {
    r.r2 = bucket.tryAcquire();
  }
}
