package dev.keyedge.ratelimit;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.keyedge.keyformat.RatePlan;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.ZZZ_Result;

/** Burst 2, frozen time, three racing actors: exactly two may win. */
@JCStressTest
@Outcome(
    id = {"true, true, false", "true, false, true", "false, true, true"},
    expect = ACCEPTABLE,
    desc = "two of three")
@Outcome(id = "true, true, true", expect = FORBIDDEN, desc = "over-admission")
@Outcome(expect = FORBIDDEN, desc = "a permit was lost")
@State
public class GcraNoOverAdmit {
  private final Gcra gcra = new Gcra(new RatePlan(1, 2), () -> 7_000_000_000L);

  @Actor
  public void actor1(ZZZ_Result r) {
    r.r1 = gcra.tryAcquire().allowed();
  }

  @Actor
  public void actor2(ZZZ_Result r) {
    r.r2 = gcra.tryAcquire().allowed();
  }

  @Actor
  public void actor3(ZZZ_Result r) {
    r.r3 = gcra.tryAcquire().allowed();
  }
}
