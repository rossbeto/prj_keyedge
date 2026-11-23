package dev.keyedge.quotes.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.keyedge.fanout.CallException;
import dev.keyedge.fanout.Deadline;
import dev.keyedge.quotes.adapters.resilience.Guard;
import dev.keyedge.quotes.domain.RetryBudget;
import dev.keyedge.quotes.domain.RetryPolicy;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GuardTest {
  private final AtomicLong now = new AtomicLong();
  private final List<Duration> slept = new ArrayList<>();
  private final AtomicInteger attempts = new AtomicInteger();
  private CircuitBreaker breaker =
      CircuitBreaker.of(
          "t",
          CircuitBreakerConfig.custom().slidingWindowSize(100).minimumNumberOfCalls(100).build());

  private Guard guard(int maxAttempts, RetryBudget budget, int bulkhead) {
    return new Guard(
        "up",
        Bulkhead.of(
            "t",
            BulkheadConfig.custom()
                .maxConcurrentCalls(bulkhead)
                .maxWaitDuration(Duration.ZERO)
                .build()),
        breaker,
        new RetryPolicy(
            maxAttempts, Duration.ofMillis(10), Duration.ofMillis(50), RetryPolicy.Jitter.FULL),
        budget,
        now::get,
        new SplittableRandom(1),
        d -> {
          slept.add(d);
          now.addAndGet(d.toNanos());
        });
  }

  private Guard.Work<String> failing(int status, Duration retryAfter) {
    return () -> {
      attempts.incrementAndGet();
      throw CallException.status("up", status, retryAfter);
    };
  }

  private Deadline in(Duration d) {
    return Deadline.after(d, now::get);
  }

  @Test
  void nonIdempotentCallsAreNeverRetried() {
    var g = guard(3, new RetryBudget(1, 100), 10);
    assertThatThrownBy(() -> g.call(false, in(Duration.ofSeconds(5)), failing(503, null)))
        .isInstanceOf(CallException.class);
    assertThat(attempts.get()).isEqualTo(1);
  }

  @Test
  void idempotentCallsAreRetriedUpToMaxAttempts() throws Exception {
    var g = guard(3, new RetryBudget(1, 100), 10);
    assertThatThrownBy(() -> g.call(true, in(Duration.ofSeconds(5)), failing(503, null)))
        .isInstanceOf(CallException.class);
    assertThat(attempts.get()).isEqualTo(3);
    assertThat(slept).hasSize(2);
    attempts.set(0);
    var flaky = new AtomicInteger();
    assertThat(
            g.call(
                true,
                in(Duration.ofSeconds(5)),
                () -> {
                  if (flaky.incrementAndGet() < 2) {
                    throw CallException.status("up", 500, null);
                  }
                  return "ok";
                }))
        .isEqualTo("ok");
  }

  @Test
  void theBudgetStopsRetries() {
    var g = guard(3, new RetryBudget(0, 1), 10);
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> g.call(true, in(Duration.ofSeconds(5)), failing(503, null)))
          .isInstanceOf(CallException.class);
    }
    assertThat(attempts.get()).isEqualTo(3 + 1); // one retry in total
  }

  @Test
  void clientErrorsAreNotRetriedAndDoNotOpenTheBreaker() {
    var g = guard(3, new RetryBudget(1, 100), 10);
    for (int i = 0; i < 10; i++) {
      assertThatThrownBy(() -> g.call(true, in(Duration.ofSeconds(5)), failing(404, null)))
          .isInstanceOf(CallException.class);
    }
    assertThat(attempts.get()).isEqualTo(10);
    assertThat(g.state()).isEqualTo("CLOSED");
  }

  @Test
  void serverErrorsOpenTheBreakerAndThenFailFast() {
    breaker =
        CircuitBreaker.of(
            "small",
            CircuitBreakerConfig.custom().slidingWindowSize(4).minimumNumberOfCalls(4).build());
    var g = guard(1, new RetryBudget(1, 100), 10);
    for (int i = 0; i < 4; i++) {
      assertThatThrownBy(() -> g.call(true, in(Duration.ofSeconds(5)), failing(503, null)))
          .isInstanceOf(CallException.class);
    }
    assertThat(g.state()).isEqualTo("OPEN");
    assertThatThrownBy(() -> g.call(true, in(Duration.ofSeconds(5)), failing(503, null)))
        .hasMessageContaining("circuit open");
    assertThat(attempts.get()).isEqualTo(4);
  }

  @Test
  void a429RetryAfterIsHonouredOnlyWithinTheDeadline() {
    var g = guard(3, new RetryBudget(1, 100), 10);
    assertThatThrownBy(
            () -> g.call(true, in(Duration.ofSeconds(5)), failing(429, Duration.ofSeconds(1))))
        .isInstanceOf(CallException.class);
    assertThat(slept).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1));
    slept.clear();
    attempts.set(0);
    assertThatThrownBy(
            () -> g.call(true, in(Duration.ofMillis(500)), failing(429, Duration.ofSeconds(1))))
        .isInstanceOf(CallException.class);
    assertThat(slept).isEmpty(); // the wait would pass the deadline: give up now
    assertThat(attempts.get()).isEqualTo(1);
  }

  @Test
  void aFullBulkheadRejectsWithoutCalling() throws Exception {
    var g = guard(1, new RetryBudget(1, 100), 1);
    var inner = new AtomicInteger();
    String r =
        g.call(
            true,
            in(Duration.ofSeconds(5)),
            () -> {
              inner.incrementAndGet();
              assertThatThrownBy(() -> g.call(true, in(Duration.ofSeconds(5)), () -> "nested"))
                  .hasMessageContaining("bulkhead full");
              return "outer";
            });
    assertThat(r).isEqualTo("outer");
    assertThat(inner.get()).isEqualTo(1);
  }
}
