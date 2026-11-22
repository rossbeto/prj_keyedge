package dev.keyedge.edge;

import dev.keyedge.testkit.KeyedgeArchitectureRules;
import org.junit.jupiter.api.Test;

class EdgeArchitectureTest {
  @Test
  void followsTheLayeringRules() {
    KeyedgeArchitectureRules.checkAll("dev.keyedge.edge");
  }
}
