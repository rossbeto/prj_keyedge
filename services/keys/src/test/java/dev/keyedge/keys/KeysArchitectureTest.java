package dev.keyedge.keys;

import dev.keyedge.testkit.KeyedgeArchitectureRules;
import org.junit.jupiter.api.Test;

/** The shared layering rules (masterplan §3.3) on the keys service. */
class KeysArchitectureTest {
  @Test
  void followsTheLayeringRules() {
    KeyedgeArchitectureRules.checkAll("dev.keyedge.keys");
  }
}
