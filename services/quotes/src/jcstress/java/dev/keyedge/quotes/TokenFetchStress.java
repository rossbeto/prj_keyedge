package dev.keyedge.quotes;

import dev.keyedge.fanout.Deadline;
import dev.keyedge.quotes.adapters.auth.SingleFlightTokenProvider;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * Two callers ask for a token at the same time with none cached: exactly one fetch, and both get
 * the same token (D-21). The fetch runs on the CAS winner's thread (inline executor) so the loser
 * really waits on the shared future.
 */
@JCStressTest
@Description("single-flight token fetch: two concurrent callers, one fetch")
@Outcome(id = "1, 1, 1", expect = Expect.ACCEPTABLE, desc = "one fetch, both callers got token 1")
@Outcome(expect = Expect.FORBIDDEN, desc = "a second fetch or different tokens")
@State
public class TokenFetchStress {
  private final AtomicInteger fetches = new AtomicInteger();
  private final SingleFlightTokenProvider provider =
      new SingleFlightTokenProvider(
          () ->
              new SingleFlightTokenProvider.Issued(
                  Integer.toString(fetches.incrementAndGet()), Duration.ofMinutes(5)),
          Runnable::run,
          () -> 0L,
          0.8);
  private final Deadline deadline = new Deadline(Long.MAX_VALUE / 2);

  @Actor
  public void first(III_Result r) {
    r.r1 = token();
  }

  @Actor
  public void second(III_Result r) {
    r.r2 = token();
  }

  @Arbiter
  public void count(III_Result r) {
    r.r3 = fetches.get();
  }

  private int token() {
    try {
      return Integer.parseInt(provider.token(deadline));
    } catch (Exception e) {
      return -1;
    }
  }
}
