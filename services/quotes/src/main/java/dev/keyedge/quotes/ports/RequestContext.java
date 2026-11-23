package dev.keyedge.quotes.ports;

import dev.keyedge.fanout.Deadline;

/**
 * Per-request data the upstream adapters need (request id for tracing and for the upstream's
 * idempotency accounting, tenant, deadline). Passed explicitly to the use cases; also bound as a
 * {@link ScopedValue} so code running in fan-out tasks can read it (D-13).
 */
public record RequestContext(String requestId, String tenant, Deadline deadline) {
  public static final ScopedValue<RequestContext> CURRENT = ScopedValue.newInstance();
}
