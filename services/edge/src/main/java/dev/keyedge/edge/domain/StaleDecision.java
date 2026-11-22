package dev.keyedge.edge.domain;

import dev.keyedge.keyformat.KeyCheck;
import java.time.Duration;
import java.time.Instant;

/**
 * Whether an expired cached verdict may stand in for a fresh one while the control plane is
 * unavailable (D-27). Pure: the property tests cover every policy × verdict kind × time.
 *
 * <ul>
 *   <li>Only a {@code Valid} verdict is ever served stale; a negative one (revoked, unknown,
 *       expired, rotated out, malformed) is never turned into an admission.
 *   <li>{@link FailurePolicy#FAIL_CLOSED} never serves stale.
 *   <li>{@link FailurePolicy#FAIL_STATIC} serves it while {@code now < expireAt + staleWindow}.
 * </ul>
 *
 * The cost of the window is a bounded revocation latency: TTL + stale window (ADR 0010).
 */
public final class StaleDecision {
  private StaleDecision() {}

  public static boolean mayServeStale(
      FailurePolicy policy, KeyCheck cached, Instant expireAt, Duration staleWindow, Instant now) {
    return policy == FailurePolicy.FAIL_STATIC
        && cached instanceof KeyCheck.Valid
        && !staleWindow.isNegative()
        && now.isBefore(expireAt.plus(staleWindow));
  }
}
