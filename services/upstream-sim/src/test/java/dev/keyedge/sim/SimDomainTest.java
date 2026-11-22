package dev.keyedge.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.keyedge.sim.adapters.YamlScenarios;
import dev.keyedge.sim.app.Counters;
import dev.keyedge.sim.app.Respond;
import dev.keyedge.sim.app.Tokens;
import dev.keyedge.sim.domain.Behaviour;
import dev.keyedge.sim.domain.LatencyDistribution;
import dev.keyedge.testkit.KeyedgeArchitectureRules;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SimDomainTest {
  @Test
  void logNormalHitsItsMedianAndP99() {
    var d = new LatencyDistribution.LogNormal(40, 900);
    var r = new SplittableRandom(1);
    long[] ms = new long[20_000];
    for (int i = 0; i < ms.length; i++) {
      ms[i] = d.sample(r).toMillis();
    }
    Arrays.sort(ms);
    assertThat(ms[ms.length / 2]).isBetween(36L, 44L);
    assertThat(ms[(int) (ms.length * 0.99)]).isBetween(750L, 1100L);
  }

  @Test
  void otherDistributions() {
    var r = new SplittableRandom(2);
    assertThat(new LatencyDistribution.Fixed(7).sample(r)).isEqualTo(Duration.ofMillis(7));
    for (int i = 0; i < 100; i++) {
      assertThat(new LatencyDistribution.Uniform(5, 9).sample(r).toMillis()).isBetween(5L, 9L);
    }
    long slow =
        java.util.stream.IntStream.range(0, 10_000)
            .filter(i -> new LatencyDistribution.Bimodal(1, 100, 0.1).sample(r).toMillis() == 100)
            .count();
    assertThat(slow).isBetween(800L, 1200L);
    assertThatThrownBy(() -> new LatencyDistribution.Uniform(5, 4))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LatencyDistribution.LogNormal(0, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LatencyDistribution.Bimodal(1, 2, 1.5))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void errorRatesMatchTheScenarioAndSequencesAreReproducible() {
    var scenario = YamlScenarios.load("brownout-pricing");
    var a = new Respond(scenario);
    int fails = 0;
    List<Behaviour> first = new java.util.ArrayList<>();
    for (int i = 0; i < 10_000; i++) {
      var b = a.decide("pricing");
      if (i < 50) {
        first.add(b);
      }
      if (b instanceof Behaviour.Fail f) {
        fails++;
        assertThat(f.status()).isEqualTo(503);
        assertThat(f.retryAfterSeconds()).contains(1);
      }
    }
    assertThat(fails).isBetween(1800, 2200);
    var b = new Respond(scenario);
    List<Behaviour> again =
        java.util.stream.IntStream.range(0, 50).mapToObj(i -> b.decide("pricing")).toList();
    assertThat(again).isEqualTo(first);
    assertThat(b.decide("unknown-endpoint")).isEqualTo(new Behaviour.Ok(Duration.ofMillis(5)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "healthy",
        "slow-tail",
        "brownout-pricing",
        "flaky-stock",
        "rate-limited-fx",
        "token-expiry-storm",
        "slow-close"
      })
  void everyShippedScenarioParses(String name) {
    assertThat(YamlScenarios.load(name).name()).isEqualTo(name);
  }

  @Test
  void slowCloseAndBadInput() {
    var r = new Respond(YamlScenarios.load("slow-close"));
    long slow =
        java.util.stream.IntStream.range(0, 1000)
            .filter(i -> r.decide("pricing") instanceof Behaviour.SlowClose)
            .count();
    assertThat(slow).isBetween(400L, 600L);
    assertThatThrownBy(() -> YamlScenarios.load("../etc/passwd"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> YamlScenarios.load("nope"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> YamlScenarios.read("- a list"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> YamlScenarios.read("endpoints: {x: {latency: {kind: weird}}}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> YamlScenarios.read("endpoints: {x: {latency: {kind: fixed, ms: hello}}}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(YamlScenarios.read("seed: 3").token().ttlSeconds()).isEqualTo(300);
  }

  @Test
  void tokensExpire() {
    var now = new AtomicReference<>(Instant.parse("2026-11-23T10:00:00Z"));
    var tokens = new Tokens(now::get, RandomGeneratorFactory.of("L64X128MixRandom").create(1));
    var t = tokens.issue(Duration.ofSeconds(5));
    assertThat(tokens.check(Optional.of(t.token()))).isEqualTo(Tokens.Check.VALID);
    now.set(now.get().plusSeconds(5));
    assertThat(tokens.check(Optional.of(t.token()))).isEqualTo(Tokens.Check.EXPIRED);
    assertThat(tokens.check(Optional.of("other"))).isEqualTo(Tokens.Check.UNKNOWN);
    assertThat(tokens.check(Optional.empty())).isEqualTo(Tokens.Check.MISSING);
    tokens.clear();
    assertThat(tokens.check(Optional.of(t.token()))).isEqualTo(Tokens.Check.UNKNOWN);
  }

  @Test
  void countersTrackInFlightHighWaterMarkAndRepeatedPosts() {
    var c = new Counters();
    c.start("pricing");
    c.start("pricing");
    c.end("pricing", 200);
    c.end("pricing", 503);
    c.post("r1");
    c.post("r1");
    c.post("r2");
    c.tokenIssued();
    var s = c.snapshot();
    assertThat(s.toString()).contains("inFlightMax=2", "inFlight=0", "200=1", "503=1");
    assertThat(s)
        .containsEntry("repeatedNonIdempotentPosts", 1L)
        .containsEntry("nonIdempotentPosts", 3L)
        .containsEntry("tokensIssued", 1L);
    c.reset();
    assertThat(c.snapshot()).containsEntry("tokensIssued", 0L);
  }

  @Test
  void layeringAndIndependence() {
    KeyedgeArchitectureRules.checkAll("dev.keyedge.sim");
    com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
        .that()
        .resideInAPackage("dev.keyedge.sim..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("dev.keyedge.keys..", "dev.keyedge.awstarget..", "software.amazon..")
        .check(KeyedgeArchitectureRules.importMain("dev.keyedge.sim"));
  }
}
