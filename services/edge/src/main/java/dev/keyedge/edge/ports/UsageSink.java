package dev.keyedge.edge.ports;

import dev.keyedge.edge.domain.UsageCount;
import java.util.List;

/** Ships aggregated usage to the control plane. Throws on failure; the caller keeps the counts. */
public interface UsageSink {
  void send(List<UsageCount> counts);
}
