package dev.keyedge.quotes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class ValuesTest {
  @Test
  void valuesValidateTheirInput() {
    assertThatThrownBy(() -> new CurrencyCode("usd")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Sku("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Sku("-x")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Quantity(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Quantity(1001)).isInstanceOf(IllegalArgumentException.class);
    assertThat(new Quantity(1).value()).isEqualTo(1);
    assertThat(new Quantity(1000).value()).isEqualTo(1000);
    assertThat(new CurrencyCode("USD").value()).isEqualTo("USD");
  }

  @Test
  void upstreamBodiesAreParsedOrRejected() {
    assertThat(Upstream.price(Map.of("unitPriceCents", 1234, "currency", "EUR")))
        .isEqualTo(new Upstream.Price(1234, new CurrencyCode("EUR")));
    assertThat(Upstream.stock(Map.of("available", 7))).isEqualTo(new Upstream.Stock(7));
    assertThat(Upstream.fx(Map.of("from", "EUR", "to", "USD", "rate", 1.1)))
        .isEqualTo(new Upstream.FxRate(new CurrencyCode("EUR"), new CurrencyCode("USD"), 1.1));
    assertThat(Upstream.reservation(Map.of("reservation", "r-1")))
        .isEqualTo(new Upstream.Reservation("r-1"));
    assertThatThrownBy(() -> Upstream.stock(Map.of("available", "7")))
        .hasMessageContaining("available");
    assertThatThrownBy(() -> Upstream.price(Map.of("unitPriceCents", 1)))
        .hasMessageContaining("currency");
  }

  /** Returns the top of every range ({@code max}) or the bottom. */
  private static RandomGenerator edge(boolean max) {
    return new RandomGenerator() {
      @Override
      public long nextLong() {
        return 0;
      }

      @Override
      public long nextLong(long origin, long bound) {
        return max ? bound - 1 : origin;
      }
    };
  }

  @Test
  void exactBackoffBounds() {
    var full =
        new RetryPolicy(5, Duration.ofMillis(50), Duration.ofMillis(400), RetryPolicy.Jitter.FULL);
    assertThat(full.backoff(1, Duration.ZERO, edge(true))).isEqualTo(Duration.ofMillis(50));
    assertThat(full.backoff(2, Duration.ZERO, edge(true))).isEqualTo(Duration.ofMillis(100));
    assertThat(full.backoff(3, Duration.ZERO, edge(true))).isEqualTo(Duration.ofMillis(200));
    assertThat(full.backoff(5, Duration.ZERO, edge(true))).isEqualTo(Duration.ofMillis(400));
    assertThat(full.backoff(3, Duration.ZERO, edge(false))).isEqualTo(Duration.ZERO);
    var dec =
        new RetryPolicy(
            5, Duration.ofMillis(50), Duration.ofMillis(400), RetryPolicy.Jitter.DECORRELATED);
    assertThat(dec.backoff(1, Duration.ofMillis(100), edge(true)))
        .isEqualTo(Duration.ofMillis(300));
    assertThat(dec.backoff(1, Duration.ofMillis(200), edge(true)))
        .isEqualTo(Duration.ofMillis(400));
    assertThat(dec.backoff(1, Duration.ofMillis(10), edge(true))).isEqualTo(Duration.ofMillis(150));
    assertThat(dec.backoff(1, Duration.ofMillis(100), edge(false)))
        .isEqualTo(Duration.ofMillis(50));
    assertThat(
            dec.waitFor(
                1,
                Duration.ofMillis(100),
                java.util.Optional.of(Duration.ofMillis(100)),
                edge(true)))
        .isEqualTo(Duration.ofMillis(300));
  }
}
