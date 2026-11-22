package dev.keyedge.sim.app;

import dev.keyedge.sim.domain.Behaviour;
import dev.keyedge.sim.domain.Scenario;
import java.time.Duration;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Decides each call's behaviour from the current scenario. One seeded generator per endpoint, drawn
 * in arrival order: a sequential run is fully reproducible; under concurrency the order varies, so
 * experiments assert ratios over many calls, never exact sequences.
 */
public final class Respond {
  private final AtomicReference<State> state;

  private record State(Scenario scenario, Map<String, SplittableRandom> randoms) {}

  public Respond(Scenario initial) {
    this.state = new AtomicReference<>(fresh(initial));
  }

  private static State fresh(Scenario s) {
    return new State(s, new ConcurrentHashMap<>());
  }

  /** Switches scenario (and restarts every endpoint's sequence from the seed). */
  public void apply(Scenario scenario) {
    state.set(fresh(scenario));
  }

  public Scenario scenario() {
    return state.get().scenario();
  }

  public Behaviour decide(String endpoint) {
    State s = state.get();
    Scenario.Endpoint e = s.scenario().endpoint(endpoint);
    SplittableRandom random =
        s.randoms()
            .computeIfAbsent(
                endpoint, n -> new SplittableRandom(s.scenario().seed() ^ n.hashCode()));
    double roll;
    Duration latency;
    synchronized (random) { // SplittableRandom is not thread-safe
      latency = e.latency().sample(random);
      roll = random.nextDouble();
    }
    double cumulative = 0;
    for (Scenario.ErrorRule rule : e.errors()) {
      cumulative += rule.rate();
      if (roll < cumulative) {
        return new Behaviour.Fail(latency, rule.status(), rule.retryAfterSeconds());
      }
    }
    if (e.slowClose().isPresent() && roll >= 1 - e.slowClose().get().rate()) {
      return new Behaviour.SlowClose(latency, Duration.ofMillis(e.slowClose().get().pauseMs()));
    }
    return new Behaviour.Ok(latency);
  }
}
