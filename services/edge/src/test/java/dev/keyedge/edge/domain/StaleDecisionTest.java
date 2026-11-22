package dev.keyedge.edge.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.keyformat.KeyCheck;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.MalformedReason;
import dev.keyedge.keyformat.RatePlan;
import dev.keyedge.keyformat.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

class StaleDecisionTest {
  private static final Instant EXPIRE_AT = Instant.parse("2026-11-23T10:00:00Z");
  private static final KeyId ID = new KeyId(1, 1);

  @Provide
  Arbitrary<KeyCheck> verdicts() {
    return Arbitraries.of(
        new KeyCheck.Valid(
            new TenantId("acme"), ID, Set.of(), new RatePlan(1, 1), Duration.ofSeconds(60)),
        new KeyCheck.Revoked(ID, EXPIRE_AT),
        new KeyCheck.Expired(ID, EXPIRE_AT),
        new KeyCheck.RotatedOut(ID, EXPIRE_AT),
        new KeyCheck.Unknown(ID),
        new KeyCheck.Malformed(MalformedReason.CHARSET));
  }

  @Property(tries = 1000)
  void staleIsServedOnlyForValidUnderFailStaticInsideTheWindow(
      @ForAll FailurePolicy policy,
      @ForAll("verdicts") KeyCheck cached,
      @ForAll @LongRange(min = -600, max = 900) long secondsAfterExpiry,
      @ForAll @LongRange(min = 0, max = 600) long windowSeconds) {
    Instant now = EXPIRE_AT.plusSeconds(secondsAfterExpiry);
    Duration window = Duration.ofSeconds(windowSeconds);
    boolean served = StaleDecision.mayServeStale(policy, cached, EXPIRE_AT, window, now);
    boolean expected =
        policy == FailurePolicy.FAIL_STATIC
            && cached instanceof KeyCheck.Valid
            && secondsAfterExpiry < windowSeconds;
    assertThat(served).isEqualTo(expected);
  }

  @Property(tries = 200)
  void failClosedNeverServesStale(
      @ForAll("verdicts") KeyCheck cached, @ForAll @LongRange(min = -600, max = 900) long s) {
    assertThat(
            StaleDecision.mayServeStale(
                FailurePolicy.FAIL_CLOSED,
                cached,
                EXPIRE_AT,
                Duration.ofHours(1),
                EXPIRE_AT.plusSeconds(s)))
        .isFalse();
  }

  @Property(tries = 200)
  void aNegativeVerdictIsNeverAdmittedStale(
      @ForAll("verdicts") KeyCheck cached, @ForAll @LongRange(min = -600, max = 900) long s) {
    boolean served =
        StaleDecision.mayServeStale(
            FailurePolicy.FAIL_STATIC,
            cached,
            EXPIRE_AT,
            Duration.ofHours(1),
            EXPIRE_AT.plusSeconds(s));
    assertThat(served).isEqualTo(cached instanceof KeyCheck.Valid);
  }
}
