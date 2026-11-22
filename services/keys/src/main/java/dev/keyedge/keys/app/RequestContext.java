package dev.keyedge.keys.app;

import dev.keyedge.keyformat.TenantId;
import java.time.Instant;

/**
 * Who asks and until when: passed explicitly to every use case (D-13). A {@code ScopedValue}
 * carries the same record for logs only.
 */
public record RequestContext(String requestId, TenantId tenant, Instant deadline) {
  /** Bound by the API layer around each request, for logging and tracing only. */
  public static final ScopedValue<RequestContext> CURRENT = ScopedValue.newInstance();
}
