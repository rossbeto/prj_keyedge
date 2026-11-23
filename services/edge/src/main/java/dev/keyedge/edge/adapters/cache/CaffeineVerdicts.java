package dev.keyedge.edge.adapters.cache;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import dev.keyedge.edge.domain.FailurePolicy;
import dev.keyedge.edge.domain.StaleDecision;
import dev.keyedge.edge.ports.Verdicts;
import dev.keyedge.edge.ports.VerifyClient;
import dev.keyedge.edge.ports.VerifyObserver;
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
 *
 * <p>Fail-static (D-27, Phase 008): a Valid entry stays in Caffeine for {@code staleWindow} past
 * its expiry. A reader past the expiry revalidates (one shared revalidation per key, single flight
 * again); if the control plane is unavailable, {@link StaleDecision} decides whether the old Valid
 * may be served (flagged stale) or the caller gets {@link Verdicts.Unavailable}.
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
  private final ConcurrentHashMap<Key, CompletableFuture<Timed>> revalidating =
      new ConcurrentHashMap<>();
  private final LongAdder loads = new LongAdder();
  private final LongAdder refreshes = new LongAdder();
  private final LongAdder staleServed = new LongAdder();
  private final FailurePolicy policy;
  private volatile VerifyObserver observer = VerifyObserver.none();
  private final Duration staleWindow;

  /** Fail-static settings; {@link #closed()} keeps the Phase 007 behaviour. */
  public record Staleness(FailurePolicy policy, Duration staleWindow) {
    public static Staleness closed() {
      return new Staleness(FailurePolicy.FAIL_CLOSED, Duration.ZERO);
    }
  }

  public CaffeineVerdicts(
      VerifyClient client,
      InstantSource clock,
      Executor executor,
      long maxEntries,
      Duration waitTimeout,
      Duration negativeTtl,
      double refreshRatio) {
    this(
        client,
        clock,
        executor,
        maxEntries,
        waitTimeout,
        negativeTtl,
        refreshRatio,
        Staleness.closed());
  }

  public CaffeineVerdicts(
      VerifyClient client,
      InstantSource clock,
      Executor executor,
      long maxEntries,
      Duration waitTimeout,
      Duration negativeTtl,
      double refreshRatio,
      Staleness staleness) {
    this.client = client;
    this.policy = staleness.policy();
    this.staleWindow =
        staleness.policy() == FailurePolicy.FAIL_STATIC ? staleness.staleWindow() : Duration.ZERO;
    this.executor = executor;
    this.clock = clock;
    this.waitTimeout = waitTimeout;
    this.negativeTtl = negativeTtl;
    this.refreshRatio = refreshRatio;
    this.cache =
        Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .executor(executor)
            .expireAfter(new ExpireAtTtl(clock, staleWindow))
            .buildAsync((key, ex) -> CompletableFuture.supplyAsync(() -> load(key, "miss"), ex));
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
    return answer(keyId, secret).check();
  }

  @Override
  public Answer answer(KeyId keyId, KeySecret secret) {
    var key = new Key(keyId, secret);
    CompletableFuture<Timed> future = cache.get(key);
    Timed t = await(key, future, true);
    Instant now = clock.instant();
    if (now.isBefore(t.expireAt())) {
      if (!now.isBefore(t.refreshAt()) && refreshing.add(key)) {
        refreshAhead(key);
      }
      return new Answer(t.check(), false);
    }
    // past expiry but not evicted yet (a Valid one lingers for the stale window): revalidate
    return revalidate(key, t);
  }

  /** Past expiry, inside the stale window: one shared reload; on failure, maybe serve stale. */
  private Answer revalidate(Key key, Timed old) {
    CompletableFuture<Timed> reload = sharedReload(key);
    try {
      return new Answer(await(key, reload, false).check(), false);
    } catch (Verdicts.Unavailable e) {
      if (StaleDecision.mayServeStale(
          policy, old.check(), old.expireAt(), staleWindow, clock.instant())) {
        staleServed.increment();
        return new Answer(old.check(), true);
      }
      throw e;
    }
  }

  /**
   * One reload per key. A reload that already failed may still be in the map for an instant (its
   * removal runs after the waiters wake): it is replaced, never reused, or the first caller after
   * recovery would get one more stale answer. The work starts only after this caller won the slot,
   * outside any map lock.
   */
  private CompletableFuture<Timed> sharedReload(Key key) {
    while (true) {
      CompletableFuture<Timed> existing = revalidating.get(key);
      if (existing != null && !existing.isCompletedExceptionally()) {
        return existing;
      }
      var mine = new CompletableFuture<Timed>();
      boolean won =
          existing == null
              ? revalidating.putIfAbsent(key, mine) == null
              : revalidating.replace(key, existing, mine);
      if (!won) {
        continue;
      }
      executor.execute(() -> {
        try {
          Timed fresh = load(key, "revalidate");
          cache.put(key, CompletableFuture.completedFuture(fresh));
          mine.complete(fresh);
        } catch (RuntimeException e) {
          mine.completeExceptionally(e);
        } finally {
          revalidating.remove(key, mine);
        }
      });
      return mine;
    }
  }

  private Timed await(Key key, CompletableFuture<Timed> future, boolean cached) {
    try {
      return future.copy().orTimeout(waitTimeout.toMillis(), TimeUnit.MILLISECONDS).join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cached && !(cause instanceof TimeoutException)) {
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
            cache.put(key, CompletableFuture.completedFuture(load(key, "refresh")));
          } catch (RuntimeException e) {
            // keep serving the old value; the next reader past refreshAt tries again
          } finally {
            refreshing.remove(key);
          }
        },
        executor);
  }

  /** Where each control-plane call is reported (metrics, JFR); set once by wiring. */
  public void observeWith(VerifyObserver observer) {
    this.observer = observer;
  }

  /** One verify call, timed and reported. */
  private Timed load(Key key, String kind) {
    loads.increment();
    long start = System.nanoTime();
    try {
      Timed t = timed(client.verify(key.keyId(), key.secret()));
      observer.loaded(
          key.keyId(), System.nanoTime() - start, kind, t.check().getClass().getSimpleName());
      return t;
    } catch (RuntimeException e) {
      observer.loaded(key.keyId(), System.nanoTime() - start, kind, "unavailable");
      throw e;
    }
  }

  /** Loads started (verify calls), for tests and metrics. */
  public long loads() {
    return loads.sum();
  }

  public long refreshes() {
    return refreshes.sum();
  }

  /** Requests answered with a stale Valid (fail-static), for metrics and tests. */
  public long staleServed() {
    return staleServed.sum();
  }

  /** Expire each entry at its own deadline; a Valid one lingers for the stale window. */
  private static final class ExpireAtTtl implements Expiry<Key, Timed> {
    private final InstantSource clock;
    private final Duration staleWindow;

    ExpireAtTtl(InstantSource clock, Duration staleWindow) {
      this.clock = clock;
      this.staleWindow = staleWindow;
    }

    private long nanosUntil(Timed t) {
      Instant until =
          t.check() instanceof KeyCheck.Valid ? t.expireAt().plus(staleWindow) : t.expireAt();
      return Math.max(0, Duration.between(clock.instant(), until).toNanos());
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
