package dev.keyedge.edge.adapters.observe.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** A per-key rate-limit decision (admitted, or rejected with a retry delay). */
@Name("dev.keyedge.RateLimitDecision")
@Label("Rate limit decision")
@Category({"keyedge", "edge"})
@Description("Per-key GCRA decision for an authenticated request")
@StackTrace(false)
public final class RateLimitDecisionEvent extends Event {
  @Label("Key id")
  public String keyId = "";

  @Label("Allowed")
  public boolean allowed;

  @Label("Retry after (ms)")
  public long retryAfterMs;
}
