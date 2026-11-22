package dev.keyedge.sim.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A named, seeded behaviour for every endpoint plus the token policy. */
public record Scenario(String name, long seed, Map<String, Endpoint> endpoints, TokenPolicy token) {
  public Scenario {
    endpoints = Map.copyOf(endpoints);
  }

  /** The behaviour of one endpoint, or a fast healthy default. */
  public Endpoint endpoint(String name) {
    return endpoints.getOrDefault(name, Endpoint.HEALTHY);
  }

  /** One endpoint: latency, error rules (checked in order), optional slow close. */
  public record Endpoint(
      LatencyDistribution latency, List<ErrorRule> errors, Optional<SlowCloseRule> slowClose) {
    public static final Endpoint HEALTHY =
        new Endpoint(new LatencyDistribution.Fixed(5), List.of(), Optional.empty());

    public Endpoint {
      errors = List.copyOf(errors);
    }
  }

  /** Answer {@code status} with probability {@code rate}; 429/503 may carry Retry-After. */
  public record ErrorRule(int status, double rate, Optional<Integer> retryAfterSeconds) {
    public ErrorRule {
      if (rate < 0 || rate > 1 || status < 400 || status > 599) {
        throw new IllegalArgumentException("error rule needs rate in [0,1] and a 4xx/5xx status");
      }
    }
  }

  /** With probability {@code rate}, send headers and half the body, then pause {@code pauseMs}. */
  public record SlowCloseRule(double rate, long pauseMs) {}

  /** Access tokens: lifetime and how long issuing takes. */
  public record TokenPolicy(long ttlSeconds, LatencyDistribution issueLatency) {
    public static final TokenPolicy DEFAULT =
        new TokenPolicy(300, new LatencyDistribution.Fixed(20));
  }
}
