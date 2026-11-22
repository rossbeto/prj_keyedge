package dev.keyedge.keys.app;

import dev.keyedge.keyformat.KeyEnvironment;
import dev.keyedge.keyformat.KeyIds;
import dev.keyedge.keyformat.RatePlan;
import dev.keyedge.keyformat.SecretHasher;
import dev.keyedge.keyformat.TenantId;
import dev.keyedge.keys.domain.VerifyPolicy;
import dev.keyedge.keys.fakes.FixedPeppers;
import dev.keyedge.keys.fakes.InMemoryKeys;
import dev.keyedge.keys.fakes.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGeneratorFactory;

/** The use cases wired with fakes: what a test needs, built in one line. */
final class KeysScenario {
  final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T10:00:00Z"));
  final InMemoryKeys store = new InMemoryKeys(Duration.ofSeconds(30));
  final FixedPeppers peppers = new FixedPeppers();
  final KeyMaterial material =
      new KeyMaterial(
          new KeyIds(clock, RandomGeneratorFactory.of("L64X128MixRandom").create(1)),
          RandomGeneratorFactory.of("L64X128MixRandom").create(2),
          new SecretHasher(),
          peppers,
          KeyEnvironment.TEST);
  final IssueKey issue = new IssueKey(store, material, clock, new RatePlan(10, 20));
  final ManageKeys manage = new ManageKeys(store, material, clock, Duration.ofDays(7));
  final VerifyKey verify =
      new VerifyKey(
          store, peppers, new VerifyPolicy(new SecretHasher(), Duration.ofSeconds(60)), clock);

  RequestContext ctx(String tenant) {
    return new RequestContext("req-1", new TenantId(tenant), clock.instant().plusSeconds(2));
  }
}
