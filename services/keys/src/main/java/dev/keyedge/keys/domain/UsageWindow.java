package dev.keyedge.keys.domain;

import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.TenantId;
import java.time.Instant;

/** Requests counted by a sidecar for one key in one minute. */
public record UsageWindow(
    TenantId tenant, KeyId keyId, Instant minute, long allowed, long rejected) {
  public UsageWindow {
    if (allowed < 0 || rejected < 0) {
      throw new IllegalArgumentException("counts cannot be negative");
    }
  }
}
