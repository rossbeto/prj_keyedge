package dev.keyedge.edge.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.keyedge.edge.adapters.cache.CaffeineVerdicts;
import dev.keyedge.edge.domain.FailurePolicy;
import dev.keyedge.edge.ports.Verdicts;
import dev.keyedge.edge.ports.VerifyClient;
import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.KeySecret;
import dev.keyedge.keyformat.RatePlan;
import dev.keyedge.keyformat.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Fail-static (D-27) on the real cache with a scripted clock and a switchable control plane. */
class CaffeineVerdictsStaleTest {
  private static final KeyId ID = new KeyId(1, 1);
  private static final KeySecret GOOD = KeySecret.ofBytes(new byte[32]);
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-11-23T10:00:00Z"));
  private final AtomicBoolean down = new AtomicBoolean();
  private final AtomicReference<KeyCheck> answer =
      new AtomicReference<>(
          new KeyCheck.Valid(
              new TenantId("acme"), ID, Set.of(), new RatePlan(1, 1), Duration.ofSeconds(10)));
  private final AtomicInteger calls = new AtomicInteger();
  private final VerifyClient client =
      (id, s) -> {
        calls.incrementAndGet();
        if (down.get()) {
          throw new Verdicts.Unavailable("down", null);
        }
        return answer.get();
      };

  private CaffeineVerdicts cache(FailurePolicy policy) {
    return new CaffeineVerdicts(
        client,
        now::get,
        Executors.newVirtualThreadPerTaskExecutor(),
        1000,
        Duration.ofSeconds(1),
        Duration.ofSeconds(5),
        1.0,
        new CaffeineVerdicts.Staleness(policy, Duration.ofMinutes(5)));
  }

  private void advance(Duration d) {
    now.updateAndGet(t -> t.plus(d));
  }

  @Test
  void expiredValidIsServedStaleWhileTheControlPlaneIsDown() {
    var cache = cache(FailurePolicy.FAIL_STATIC);
    assertThat(cache.answer(ID, GOOD).stale()).isFalse();
    down.set(true);
    advance(Duration.ofSeconds(11)); // past the 10 s TTL, inside the 5 min window
    var a = cache.answer(ID, GOOD);
    assertThat(a.check()).isInstanceOf(KeyCheck.Valid.class);
    assertThat(a.stale()).isTrue();
    assertThat(cache.staleServed()).isEqualTo(1);
  }

  @Test
  void pastTheStaleWindowItIsUnavailable() {
    var cache = cache(FailurePolicy.FAIL_STATIC);
    cache.answer(ID, GOOD);
    down.set(true);
    advance(Duration.ofSeconds(10).plus(Duration.ofMinutes(5)));
    assertThatThrownBy(() -> cache.answer(ID, GOOD)).isInstanceOf(Verdicts.Unavailable.class);
  }

  @Test
  void failClosedNeverServesAnExpiredVerdict() {
    var cache = cache(FailurePolicy.FAIL_CLOSED);
    cache.answer(ID, GOOD);
    down.set(true);
    advance(Duration.ofSeconds(11));
    assertThatThrownBy(() -> cache.answer(ID, GOOD)).isInstanceOf(Verdicts.Unavailable.class);
  }

  @Test
  void aSuccessfulRevalidationReplacesTheStaleEntryAndSeesARevoke() {
    var cache = cache(FailurePolicy.FAIL_STATIC);
    cache.answer(ID, GOOD);
    down.set(true);
    advance(Duration.ofSeconds(11));
    assertThat(cache.answer(ID, GOOD).stale()).isTrue();
    down.set(false);
    answer.set(new KeyCheck.Revoked(ID, now.get()));
    var a = cache.answer(ID, GOOD);
    assertThat(a.stale()).isFalse();
    assertThat(a.check()).isInstanceOf(KeyCheck.Revoked.class);
    assertThat(cache.answer(ID, GOOD).check()).isInstanceOf(KeyCheck.Revoked.class);
  }

  @Test
  void aCachedNegativeVerdictIsNeverServedStale() {
    answer.set(new KeyCheck.Revoked(ID, now.get()));
    var cache = cache(FailurePolicy.FAIL_STATIC);
    assertThat(cache.answer(ID, GOOD).check()).isInstanceOf(KeyCheck.Revoked.class);
    down.set(true);
    advance(Duration.ofSeconds(6)); // past the 5 s negative TTL
    assertThatThrownBy(() -> cache.answer(ID, GOOD)).isInstanceOf(Verdicts.Unavailable.class);
  }

  @Test
  void freshEntriesDoNotCallTheControlPlane() {
    var cache = cache(FailurePolicy.FAIL_STATIC);
    cache.answer(ID, GOOD);
    advance(Duration.ofSeconds(5));
    cache.answer(ID, GOOD);
    assertThat(calls.get()).isEqualTo(1);
  }
}
