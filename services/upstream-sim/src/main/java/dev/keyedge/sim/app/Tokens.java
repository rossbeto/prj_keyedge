package dev.keyedge.sim.app;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/** Short-lived opaque access tokens for the client-credentials flow. */
public final class Tokens {
  private final Map<String, Instant> live = new ConcurrentHashMap<>();
  private final InstantSource clock;
  private final RandomGenerator random;

  public Tokens(InstantSource clock, RandomGenerator random) {
    this.clock = clock;
    this.random = random;
  }

  public Issued issue(Duration ttl) {
    byte[] b = new byte[24];
    random.nextBytes(b);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    live.put(token, clock.instant().plus(ttl));
    if (live.size() > 100_000) {
      live.values().removeIf(exp -> exp.isBefore(clock.instant()));
    }
    return new Issued(token, ttl.toSeconds());
  }

  /** Valid, expired, or unknown. */
  public Check check(Optional<String> token) {
    if (token.isEmpty()) {
      return Check.MISSING;
    }
    Instant exp = live.get(token.get());
    if (exp == null) {
      return Check.UNKNOWN;
    }
    return clock.instant().isBefore(exp) ? Check.VALID : Check.EXPIRED;
  }

  public void clear() {
    live.clear();
  }

  /** Issued token. */
  public record Issued(String token, long expiresIn) {}

  /** Token check result. */
  public enum Check {
    VALID,
    EXPIRED,
    UNKNOWN,
    MISSING
  }
}
