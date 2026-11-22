package dev.keyedge.keys.fakes;

import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.TenantId;
import dev.keyedge.keys.domain.UsageWindow;
import dev.keyedge.keys.ports.UsageStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory usage store with the adapter's merge semantics. */
public final class InMemoryUsage implements UsageStore {
  private final Map<String, UsageWindow> windows = new ConcurrentHashMap<>();

  @Override
  public void add(List<UsageWindow> batch) {
    for (UsageWindow w : batch) {
      Instant minute = w.minute().truncatedTo(ChronoUnit.MINUTES);
      windows.merge(
          w.tenant() + "#" + w.keyId() + "#" + minute,
          new UsageWindow(w.tenant(), w.keyId(), minute, w.allowed(), w.rejected()),
          (a, b) ->
              new UsageWindow(
                  a.tenant(),
                  a.keyId(),
                  minute,
                  a.allowed() + b.allowed(),
                  a.rejected() + b.rejected()));
    }
  }

  @Override
  public List<UsageWindow> query(TenantId tenant, KeyId keyId, Instant from, Instant to) {
    return windows.values().stream()
        .filter(w -> w.tenant().equals(tenant) && w.keyId().equals(keyId))
        .filter(w ->
            !w.minute().isBefore(from.truncatedTo(ChronoUnit.MINUTES)) && w.minute().isBefore(to))
        .sorted(java.util.Comparator.comparing(UsageWindow::minute))
        .toList();
  }
}
