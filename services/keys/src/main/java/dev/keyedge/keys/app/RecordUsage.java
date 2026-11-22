package dev.keyedge.keys.app;

import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keys.domain.UsageWindow;
import dev.keyedge.keys.ports.KeyRepository;
import dev.keyedge.keys.ports.UsageStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Stores sidecar usage batches and answers usage queries for a tenant's own keys. */
public final class RecordUsage {
  private final UsageStore usage;
  private final KeyRepository keys;

  public RecordUsage(UsageStore usage, KeyRepository keys) {
    this.usage = usage;
    this.keys = keys;
  }

  public void record(List<UsageWindow> windows) {
    if (!windows.isEmpty()) {
      usage.add(windows);
    }
  }

  /** Empty when the key is not the caller's. */
  public Optional<List<UsageWindow>> query(
      RequestContext ctx, KeyId keyId, Instant from, Instant to) {
    return keys.find(keyId)
        .filter(k -> k.tenant().equals(ctx.tenant()))
        .map(k -> usage.query(ctx.tenant(), keyId, from, to));
  }
}
