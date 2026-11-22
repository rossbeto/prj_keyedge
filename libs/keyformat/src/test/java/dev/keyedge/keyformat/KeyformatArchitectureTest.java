package dev.keyedge.keyformat;

import static dev.keyedge.testkit.KeyedgeArchitectureRules.importMain;
import static dev.keyedge.testkit.KeyedgeArchitectureRules.noHiddenRandomnessOrTime;
import static dev.keyedge.testkit.KeyedgeArchitectureRules.noUtilsOrHelpers;
import static dev.keyedge.testkit.KeyedgeArchitectureRules.pureLibrary;

import org.junit.jupiter.api.Test;

class KeyformatArchitectureTest {
  private static final String BASE = "dev.keyedge.keyformat";

  @Test
  void isAPureLibraryWithInjectedTimeAndRandomness() {
    var classes = importMain(BASE);
    pureLibrary(BASE).check(classes);
    noHiddenRandomnessOrTime(BASE, BASE + ".demo..").check(classes);
    noUtilsOrHelpers(BASE).check(classes);
  }
}
