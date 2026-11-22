package dev.keyedge.keys.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.keyedge.keyformat.InternalSignature;
import dev.keyedge.keys.ports.CallerCredentials;
import dev.keyedge.problem.Problem;
import dev.keyedge.problem.ProblemException;
import dev.keyedge.problem.ProblemType;
import io.micronaut.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/**
 * Checks the HMAC signature of an internal request (D-25): known caller, body/path/method signed,
 * timestamp within the skew, and the signature not seen before (replay cache spanning twice the
 * skew). The same 401 for every failure: no hint which part was wrong.
 */
public final class InternalAuth {
  private final CallerCredentials callers;
  private final InstantSource clock;
  private final Duration skew;
  private final Cache<String, Boolean> seen;

  public InternalAuth(CallerCredentials callers, InstantSource clock, Duration skew) {
    this.callers = callers;
    this.clock = clock;
    this.skew = skew;
    this.seen =
        Caffeine.newBuilder().expireAfterWrite(skew.multipliedBy(2)).maximumSize(1_000_000).build();
  }

  /** Returns the caller id, or throws a 401 problem. */
  public String check(HttpRequest<?> request, String body) {
    var headers = request.getHeaders();
    String caller = headers.get(InternalSignature.CALLER);
    String ts = headers.get(InternalSignature.TIMESTAMP);
    String signature = headers.get(InternalSignature.SIGNATURE);
    if (caller == null || ts == null || signature == null) {
      throw rejected();
    }
    long epoch;
    try {
      epoch = Long.parseLong(ts);
    } catch (NumberFormatException e) {
      throw rejected();
    }
    byte[] credential = callers.credential(caller).orElseThrow(InternalAuth::rejected);
    boolean valid =
        InternalSignature.verify(
            credential,
            request.getMethodName(),
            request.getPath(),
            Instant.ofEpochSecond(epoch),
            body.getBytes(StandardCharsets.UTF_8),
            signature,
            clock.instant(),
            skew);
    if (!valid || seen.asMap().putIfAbsent(signature, Boolean.TRUE) != null) {
      throw rejected();
    }
    return caller;
  }

  private static ProblemException rejected() {
    return new ProblemException(Problem.of(ProblemType.UNAUTHENTICATED, null));
  }
}
