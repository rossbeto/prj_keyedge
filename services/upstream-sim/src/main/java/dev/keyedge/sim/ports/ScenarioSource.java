package dev.keyedge.sim.ports;

import dev.keyedge.sim.domain.Scenario;

/** Where scenarios come from: shipped by name, or a custom document. */
public interface ScenarioSource {
  Scenario named(String name);

  Scenario parse(String document);
}
