package dev.keyedge.quotes.adapters.auth;

import dev.keyedge.fanout.CallException;
import dev.keyedge.fanout.Deadline;
import dev.keyedge.quotes.ports.TokenProvider;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * One token fetch at a time, shared by every caller (D-21, jcstress {@code TokenFetchStress}): the
 * in-flight fetch is a future swapped in with a CAS; the CAS winner starts the fetch, everyone else
 * waits on the same future with their own timeout. Refresh-ahead: past {@code refreshAt} the
 * current token is still served while one refresh runs; only a missing or hard-expired token makes
 * callers wait. A 401 from the upstream {@linkplain #invalidate invalidates} that token.
 */
public final class SingleFlightTokenProvider implements TokenProvider {
  private final TokenEndpoint endpoint;
  private final Executor executor;
  private final LongSupplier nanos;
  private final double refreshAtFraction;
  private final AtomicReference<@Nullable CompletableFuture<Token>> inFlight =
      new AtomicReference<>();
  private final AtomicReference<@Nullable Token> current = new AtomicReference<>();
  private final AtomicLong fetches = new AtomicLong();

  /** Fetches a token from the authorization server. */
  @FunctionalInterface
  public interface TokenEndpoint {
    Issued fetch() throws Exception;
  }

  /** What the server issued. */
  public record Issued(String value, Duration lifetime) {}

  private record Token(String value, long refreshAtNanos, long expiresAtNanos) {}

  public SingleFlightTokenProvider(
      TokenEndpoint endpoint, Executor executor, LongSupplier nanos, double refreshAtFraction) {
    this.endpoint = endpoint;
    this.executor = executor;
    this.nanos = nanos;
    this.refreshAtFraction = refreshAtFraction;
  }

  @Override
  public String token(Deadline deadline) throws CallException, InterruptedException {
    String usable = usable();
    if (usable != null) {
      return usable;
    }
    try {
      return fetchOnce()
          .copy()
          .orTimeout(deadline.remaining(nanos).toNanos(), TimeUnit.NANOSECONDS)
          .get()
          .value();
    } catch (ExecutionException e) {
      throw e.getCause() instanceof TimeoutException
          ? CallException.timeout("token")
          : CallException.io("token", String.valueOf(e.getCause()));
    }
  }

  @Override
  public CompletableFuture<String> tokenAsync() {
    String usable = usable();
    return usable != null
        ? CompletableFuture.completedFuture(usable)
        : fetchOnce().thenApply(Token::value);
  }

  @Override
  public void invalidate(String token) {
    Token t = current.get();
    if (t != null && t.value().equals(token)) {
      current.compareAndSet(t, null);
    }
  }

  /** Token fetches started (the independent count is the server's). */
  public long fetches() {
    return fetches.get();
  }

  /** The current token if it is not hard-expired; starts a refresh-ahead when it is due. */
  private @Nullable String usable() {
    Token t = current.get();
    long now = nanos.getAsLong();
    if (t == null || now - t.expiresAtNanos() >= 0) {
      return null;
    }
    if (now - t.refreshAtNanos() >= 0) {
      fetchOnce(); // refresh ahead; callers keep the still-valid token
    }
    return t.value();
  }

  /**
   * The in-flight fetch, or a new one if none is running (exactly one CAS winner starts it). A
   * fetch that completed after the caller looked at {@code current} is reused while its token is
   * still the current one and not due for refresh: without this, a caller that saw "no token" just
   * before the first fetch finished started a second one (found by jcstress, TokenFetchStress).
   */
  private CompletableFuture<Token> fetchOnce() {
    while (true) {
      CompletableFuture<Token> seen = inFlight.get();
      if (seen != null && !seen.isDone()) {
        return seen;
      }
      if (seen != null && !seen.isCompletedExceptionally()) {
        Token done = seen.join();
        if (current.get() == done && nanos.getAsLong() - done.refreshAtNanos() < 0) {
          return seen;
        }
      }
      var fresh = new CompletableFuture<Token>();
      if (inFlight.compareAndSet(seen, fresh)) {
        fetches.incrementAndGet();
        executor.execute(() -> fetchInto(fresh));
        return fresh;
      }
    }
  }

  private void fetchInto(CompletableFuture<Token> target) {
    try {
      Issued issued = endpoint.fetch();
      long now = nanos.getAsLong();
      long life = issued.lifetime().toNanos();
      var token = new Token(issued.value(), now + (long) (life * refreshAtFraction), now + life);
      current.set(token);
      target.complete(token);
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      target.completeExceptionally(
          e instanceof CompletionException ? e : new CompletionException(e));
    }
  }
}
