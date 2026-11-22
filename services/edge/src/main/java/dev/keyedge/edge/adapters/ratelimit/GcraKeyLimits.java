package dev.keyedge.edge.adapters.ratelimit;

import dev.keyedge.edge.ports.KeyLimits;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.RatePlan;
import dev.keyedge.ratelimit.Decision;
import dev.keyedge.ratelimit.Gcra;
import dev.keyedge.ratelimit.KeyedLimiters;
import dev.keyedge.ratelimit.Ticker;
import java.time.Duration;
import java.util.Optional;

/** Per-key GCRA from libs/ratelimit (jcstress-proven), in a bounded map. */
public final class GcraKeyLimits implements KeyLimits {
  private final KeyedLimiters<KeyId> limiters;

  public GcraKeyLimits(long maxKeys, Duration idle, Ticker ticker) {
    this.limiters = new KeyedLimiters<>(maxKeys, idle, plan -> new Gcra(plan, ticker));
  }

  @Override
  public Optional<Duration> tryAcquire(KeyId keyId, RatePlan plan) {
    return switch (limiters.tryAcquire(keyId, plan)) {
      case Decision.Allowed a -> Optional.empty();
      case Decision.Rejected(Duration wait) -> Optional.of(wait);
    };
  }
}
