package dev.keyedge.edge.app;

import dev.keyedge.edge.domain.UsageCount;
import dev.keyedge.edge.ports.UsageSink;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.TenantId;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts admitted/rejected requests per key and minute off the request path (two {@code
 * LongAdder}s), and flushes aggregates. When the control plane is unreachable, counts are
 * <b>merged</b> into a pending map (O(keys × minutes), not O(requests)); beyond {@code
 * maxPendingWindows} the oldest windows are dropped <b>and counted</b> (D-28): never a silent drop,
 * never blocking a request.
 */
public final class MeterUsage {
  private record Window(TenantId tenant, KeyId keyId, Instant minute) {}

  private record Counts(LongAdder allowed, LongAdder rejected) {}

  private final InstantSource clock;
  private final UsageSink sink;
  private final int maxPendingWindows;
  private final Map<Window, Counts> live = new ConcurrentHashMap<>();
  private final Map<Window, UsageCount> pending = new ConcurrentHashMap<>();
  private final LongAdder dropped = new LongAdder();
  private final LongAdder flushFailures = new LongAdder();

  public MeterUsage(InstantSource clock, UsageSink sink, int maxPendingWindows) {
    this.clock = clock;
    this.sink = sink;
    this.maxPendingWindows = maxPendingWindows;
  }

  public void record(TenantId tenant, KeyId keyId, boolean allowed) {
    var w = new Window(tenant, keyId, clock.instant().truncatedTo(ChronoUnit.MINUTES));
    var c = live.computeIfAbsent(w, k -> new Counts(new LongAdder(), new LongAdder()));
    (allowed ? c.allowed() : c.rejected()).increment();
  }

  /**
   * Moves live counts into the pending map and tries to send everything pending. One flusher at a
   * time.
   */
  public synchronized void flush() {
    for (Window w : List.copyOf(live.keySet())) {
      Counts c = live.remove(w);
      if (c != null) {
        pending.merge(
            w,
            new UsageCount(
                w.tenant(), w.keyId(), w.minute(), c.allowed().sum(), c.rejected().sum()),
            UsageCount::plus);
      }
    }
    if (pending.isEmpty()) {
      return;
    }
    List<UsageCount> batch = new ArrayList<>(pending.values());
    try {
      sink.send(batch);
      batch.forEach(u -> pending.remove(new Window(u.tenant(), u.keyId(), u.minute())));
    } catch (RuntimeException e) {
      flushFailures.increment();
      trim();
    }
  }

  private void trim() {
    int excess = pending.size() - maxPendingWindows;
    if (excess > 0) {
      pending.keySet().stream()
          .sorted(Comparator.comparing(Window::minute))
          .limit(excess)
          .toList()
          .forEach(w -> {
            pending.remove(w);
            dropped.increment();
          });
    }
  }

  /** Windows dropped because the control plane was unreachable for too long. */
  public long dropped() {
    return dropped.sum();
  }

  public long flushFailures() {
    return flushFailures.sum();
  }

  public int pendingWindows() {
    return pending.size();
  }
}
