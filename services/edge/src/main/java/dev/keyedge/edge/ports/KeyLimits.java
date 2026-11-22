package dev.keyedge.edge.ports;

import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.RatePlan;
import java.time.Duration;
import java.util.Optional;

/** Per-key rate limits (per sidecar task: the effective limit is tasks × plan, D-22). */
public interface KeyLimits {
  /** Empty when admitted; the wait otherwise. */
  Optional<Duration> tryAcquire(KeyId keyId, RatePlan plan);
}
