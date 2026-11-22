package dev.keyedge.issuer.app;

import dev.keyedge.issuer.ports.TokenSigner;
import dev.keyedge.keyformat.Scope;
import dev.keyedge.keyformat.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Issues a partner token: tenant, scopes, bounded lifetime. Validation reuses the shared value
 * types.
 */
public final class IssueToken {
  /** The {@code iss} claim the keys service validates. */
  public static final String ISSUER = "keyedge-issuer";

  private final TokenSigner signer;
  private final InstantSource clock;
  private final Duration maxTtl;

  public IssueToken(TokenSigner signer, InstantSource clock, Duration maxTtl) {
    this.signer = signer;
    this.clock = clock;
    this.maxTtl = maxTtl;
  }

  /**
   * A signed token, or an exception for invalid input (the API maps it to a validation problem).
   */
  public Issued issue(String tenant, List<String> scopes, Duration ttl) {
    var t = new TenantId(tenant);
    Set<String> sorted = new TreeSet<>();
    scopes.forEach(s -> sorted.add(new Scope(s).value()));
    if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(maxTtl) > 0) {
      throw new IllegalArgumentException("ttl must be between 1 s and " + maxTtl);
    }
    Instant now = clock.instant();
    Instant exp = now.plus(ttl);
    String token =
        signer.sign(
            Map.of(
                "iss",
                ISSUER,
                "sub",
                "demo:" + t.value(),
                "tenant",
                t.value(),
                "scope",
                String.join(" ", sorted)),
            now,
            exp);
    return new Issued(token, ttl.toSeconds());
  }

  /** A token and its lifetime in seconds. */
  public record Issued(String accessToken, long expiresIn) {}
}
