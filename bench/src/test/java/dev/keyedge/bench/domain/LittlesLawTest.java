package dev.keyedge.bench.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LittlesLawTest {
  @Test
  void inFlightIsRateTimesLatency() {
    assertThat(LittlesLaw.concurrency(400, 0.12))
        .isEqualTo(48.0, org.assertj.core.data.Offset.offset(1e-9));
  }

  @Test
  void aCapOf32At80msCarries400PerSecond() {
    assertThat(LittlesLaw.maxRate(32, 0.08))
        .isEqualTo(400.0, org.assertj.core.data.Offset.offset(1e-9));
  }

  @Test
  void refusesZeroNegativeAndNonNumbers() {
    assertThatThrownBy(() -> LittlesLaw.concurrency(0, 1)).hasMessageContaining("rate");
    assertThatThrownBy(() -> LittlesLaw.concurrency(-5, 1)).hasMessageContaining("rate");
    assertThatThrownBy(() -> LittlesLaw.maxRate(10, Double.NaN)).hasMessageContaining("latency");
    assertThatThrownBy(() -> LittlesLaw.maxRate(10, Double.POSITIVE_INFINITY))
        .hasMessageContaining("latency");
  }
}
