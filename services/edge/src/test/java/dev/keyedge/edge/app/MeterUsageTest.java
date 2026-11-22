package dev.keyedge.edge.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.edge.domain.UsageCount;
import dev.keyedge.keyformat.KeyId;
import dev.keyedge.keyformat.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MeterUsageTest {
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-11-23T10:00:10Z"));
  private final List<List<UsageCount>> sent = new ArrayList<>();
  private final AtomicBoolean down = new AtomicBoolean();
  private final MeterUsage meter =
      new MeterUsage(
          now::get,
          batch -> {
            if (down.get()) {
              throw new IllegalStateException("keys down");
            }
            sent.add(batch);
          },
          2);
  private final TenantId acme = new TenantId("acme");

  @Test
  void aggregatesPerKeyAndMinuteAndFlushes() {
    for (int i = 0; i < 5; i++) {
      meter.record(acme, new KeyId(1, 1), true);
    }
    meter.record(acme, new KeyId(1, 1), false);
    meter.flush();
    assertThat(sent)
        .singleElement()
        .satisfies(b ->
            assertThat(b)
                .singleElement()
                .satisfies(u -> {
                  assertThat(u.allowed()).isEqualTo(5);
                  assertThat(u.rejected()).isEqualTo(1);
                  assertThat(u.minute()).isEqualTo(Instant.parse("2026-11-23T10:00:00Z"));
                }));
    meter.flush();
    assertThat(sent).hasSize(1);
  }

  @Test
  void failuresMergeThenTheOldestWindowsAreDroppedAndCounted() {
    down.set(true);
    for (int minute = 0; minute < 4; minute++) {
      now.set(Instant.parse("2026-11-23T10:00:10Z").plusSeconds(60L * minute));
      meter.record(acme, new KeyId(1, 1), true);
      meter.record(acme, new KeyId(1, 1), true);
      meter.flush();
    }
    assertThat(meter.pendingWindows()).isEqualTo(2);
    assertThat(meter.dropped()).isEqualTo(2);
    assertThat(meter.flushFailures()).isEqualTo(4);
    down.set(false);
    meter.flush();
    assertThat(sent)
        .singleElement()
        .satisfies(b ->
            assertThat(b)
                .hasSize(2)
                .allSatisfy(u -> assertThat(u.allowed()).isEqualTo(2))
                .extracting(UsageCount::minute)
                .containsExactlyInAnyOrder(
                    Instant.parse("2026-11-23T10:02:00Z"), Instant.parse("2026-11-23T10:03:00Z")));
    assertThat(meter.pendingWindows()).isZero();
  }
}
