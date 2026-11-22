package dev.keyedge.keyformat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

class KeyIdsTest {
  private final AtomicLong now = new AtomicLong(1_764_000_000_000L);
  private final InstantSource clock = () -> Instant.ofEpochMilli(now.get());

  @Test
  void idsInOneMillisecondAreStrictlyIncreasing() {
    var ids = new KeyIds(clock, RandomGeneratorFactory.of("L64X128MixRandom").create(42));
    var seen = new ArrayList<KeyId>();
    for (int i = 0; i < 1000; i++) {
      seen.add(ids.next());
    }
    for (int i = 1; i < seen.size(); i++) {
      assertThat(seen.get(i)).isGreaterThan(seen.get(i - 1));
      assertThat(seen.get(i).toString()).isGreaterThan(seen.get(i - 1).toString());
    }
    assertThat(seen.getFirst().timestamp()).isEqualTo(Instant.ofEpochMilli(now.get()));
  }

  @Test
  void laterMillisecondSortsAfterAndClockGoingBackDoesNotBreakOrder() {
    var ids = new KeyIds(clock, RandomGeneratorFactory.of("L64X128MixRandom").create(7));
    KeyId first = ids.next();
    now.addAndGet(5);
    KeyId second = ids.next();
    now.addAndGet(-100); // clock steps back (NTP): ids still increase
    KeyId third = ids.next();
    assertThat(second).isGreaterThan(first);
    assertThat(third).isGreaterThan(second);
  }

  @Test
  void randomPartOverflowBorrowsTheNextMillisecond() {
    RandomGenerator allOnes = () -> -1L;
    var ids = new KeyIds(clock, allOnes);
    KeyId max = ids.next();
    KeyId next = ids.next();
    assertThat(next).isGreaterThan(max);
    assertThat(next.timestamp()).isEqualTo(Instant.ofEpochMilli(now.get() + 1));
  }

  @Test
  void rejectsClockBeyondTheIdRange() {
    var ids = new KeyIds(() -> Instant.ofEpochMilli(1L << 48), () -> 0L);
    assertThatThrownBy(ids::next).isInstanceOf(IllegalStateException.class);
  }
}
