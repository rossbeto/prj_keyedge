package dev.keyedge.edge.adapters.cache;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import dev.keyedge.edge.ports.Verdicts;
import dev.keyedge.edge.ports.VerifyClient;
import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.KeySecret;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;

/**
 * Verdict cache (D-21): keyed by key id <b>and</b> secret (a cached Valid for K must never admit
 * K's id with another secret); one in-flight load per key (Caffeine's async cache shares the
 * future: single flight); each waiter has its own timeout on a copy of that future, so one
 * impatient caller does not fail the others; per-entry expiry from the verdict's TTL hint;
 * refresh-ahead at {@code refreshRatio} of the TTL, served from the old value while the reload
 * runs; rejections expire after a short TTL and are never refreshed.
 */
public final class CaffeineVerdicts implements Verdicts {
  private record Key(KeyId keyId, KeySecret secret) {}

  private record Timed(KeyCheck check, Instant refreshAt, Instant expireAt) {}

  private final AsyncLoadingCache<Key, Timed> cache;
  private final InstantSource clock;
  private final Duration waitTimeout;
  private final Duration negativeTtl;
  private final double refreshRatio;
  private final VerifyClient client;
  private final Executor executor;
  private final Set<Key> refreshing = ConcurrentHashMap.newKeySet();
  private final LongAdder loads = new LongAdder();
  private final LongAdder refreshes = new LongAdder();

  public CaffeineVerdicts(
      VerifyClient client,
      InstantSource clock,
      Executor executor,
      long maxEntries,
      Duration waitTimeout,
      Duration negativeTtl,
      double refreshRatio) {
    this.client = client;
    this.executor = executor;
    this.clock = clock;
    this.waitTimeout = waitTimeout;
    this.negativeTtl = negativeTtl;
    this.refreshRatio = refreshRatio;
    this.cache =
        Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .executor(executor)
            .expireAfter(new ExpireAtTtl(clock))
            .buildAsync((key, ex) ->
                CompletableFuture.supplyAsync(
                    () -> {
                      loads.increment();
                      return timed(client.verify(key.keyId(), key.secret()));
                    },
                    ex));
  }

  private Timed timed(KeyCheck check) {
    Instant now = clock.instant();
    if (check instanceof KeyCheck.Valid v) {
      long ttlMs = Math.max(1, v.ttlHint().toMillis());
      return new Timed(check, now.plusMillis((long) (ttlMs * refreshRatio)), now.plusMillis(ttlMs));
    }
    return new Timed(check, Instant.MAX, now.plus(negativeTtl));
  }

  @Override
  public KeyCheck verdict(KeyId keyId, KeySecret secret) {
    var key = new Key(keyId, secret);
    Timed t;
    CompletableFuture<Timed> future = cache.get(key);
    try {
      t = future.copy().orTimeout(waitTimeout.toMillis(), TimeUnit.MILLISECONDS).join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (!(cause instanceof TimeoutException)) {
        // A failed load: drop exactly that future so the next caller reloads (Caffeine evicts it
        // asynchronously). A timeout keeps it: the load is still running and others may use it.
        cache.asMap().remove(key, future);
      }
      if (cause instanceof Verdicts.Unavailable u) {
        throw u;
      }
      throw new Verdicts.Unavailable(
          cause instanceof TimeoutException
              ? "verify timed out after " + waitTimeout
              : "verify failed",
          cause);
    }
    if (!clock.instant().isBefore(t.refreshAt()) && refreshing.add(key)) {
      refreshAhead(key);
    }
    return t.check();
  }

  /**
   * Reload in the background and swap in a completed future: readers never wait on a refresh. One
   * refresh per key at a time (the flag): readers still holding the old value must not start a
   * cascade of reloads — measured with Caffeine's own refresh(), which in-flight readers
   * re-triggered (Phase 007 deviations). A failed refresh keeps the old value until it expires.
   */
  private void refreshAhead(Key key) {
    refreshes.increment();
    CompletableFuture.runAsync(
        () -> {
          try {
            loads.increment();
            cache.put(
                key,
                CompletableFuture.completedFuture(timed(client.verify(key.keyId(), key.secret()))));
          } catch (RuntimeException e) {
            // keep serving the old value; the next reader past refreshAt tries again
          } finally {
            refreshing.remove(key);
          }
        },
        executor);
  }

  /** Loads started (verify calls), for tests and metrics. */
  public long loads() {
    return loads.sum();
  }

  public long refreshes() {
    return refreshes.sum();
  }

  /** Expire each entry at its own deadline. */
  private static final class ExpireAtTtl implements Expiry<Key, Timed> {
    private final InstantSource clock;

    ExpireAtTtl(InstantSource clock) {
      this.clock = clock;
    }

    private long nanosUntil(Timed t) {
      return Math.max(0, Duration.between(clock.instant(), t.expireAt()).toNanos());
    }

    @Override
    public long expireAfterCreate(Key key, Timed value, long currentTime) {
      return nanosUntil(value);
    }

    @Override
    public long expireAfterUpdate(Key key, Timed value, long currentTime, long currentDuration) {
      return nanosUntil(value);
    }

    @Override
    public long expireAfterRead(Key key, Timed value, long currentTime, long currentDuration) {
      return currentDuration;
    }
  }
}
