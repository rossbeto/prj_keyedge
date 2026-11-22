package dev.keyedge.edge.domain;

import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.TenantId;
import java.time.Instant;

/** Admitted and rejected requests of one key in one minute. */
public record UsageCount(
    TenantId tenant, KeyId keyId, Instant minute, long allowed, long rejected) {
  /** The sum of two counts of the same key and minute. */
  public UsageCount plus(UsageCount other) {
    return new UsageCount(
        tenant, keyId, minute, allowed + other.allowed, rejected + other.rejected);
  }
}
