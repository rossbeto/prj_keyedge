package dev.keyedge.keys.ports;

import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.TenantId;
import dev.keyedge.keys.domain.UsageWindow;
import java.time.Instant;
import java.util.List;

/** Aggregated usage per key and minute. */
public interface UsageStore {
  /** Adds counts (merged with what is stored). */
  void add(List<UsageWindow> windows);

  /** Windows of one key in [from, to). */
  List<UsageWindow> query(TenantId tenant, KeyId keyId, Instant from, Instant to);
}
