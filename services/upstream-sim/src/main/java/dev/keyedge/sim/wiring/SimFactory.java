package dev.keyedge.sim.wiring;

import dev.keyedge.sim.adapters.VirtualThreadSleeper;
import dev.keyedge.sim.adapters.YamlScenarios;
import dev.keyedge.sim.api.SimSettings;
import dev.keyedge.sim.app.Counters;
import dev.keyedge.sim.app.Respond;
import dev.keyedge.sim.app.Tokens;
import dev.keyedge.sim.ports.Sleeper;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.security.SecureRandom;
import java.time.InstantSource;

/** Composition root of the simulator. */
@Factory
public class SimFactory {
  @Singleton
  Respond respond(@Value("${keyedge.sim.scenario:healthy}") String scenario) {
    return new Respond(YamlScenarios.load(scenario));
  }

  @Singleton
  dev.keyedge.sim.ports.ScenarioSource scenarios() {
    return YamlScenarios.INSTANCE;
  }

  @Singleton
  Counters counters() {
    return new Counters();
  }

  @Singleton
  Tokens tokens() {
    return new Tokens(InstantSource.system(), new SecureRandom());
  }

  @Singleton
  Sleeper sleeper() {
    return new VirtualThreadSleeper();
  }

  @Singleton
  SimSettings settings(
      @Value("${keyedge.sim.client-id:quotes}") String clientId,
      @Value("${keyedge.sim.client-secret:local-only-not-a-secret}") String clientSecret) {
    return new SimSettings(clientId, clientSecret);
  }
}
