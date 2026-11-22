package dev.keyedge.ratelimit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static dev.keyedge.testkit.KeyedgeArchitectureRules.noHiddenRandomnessOrTime;
import static dev.keyedge.testkit.KeyedgeArchitectureRules.pureLibrary;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class RatelimitArchitectureTest {
  private static final String BASE = "dev.keyedge.ratelimit";
  private final com.tngtech.archunit.core.domain.JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(BASE);

  @Test
  void pureExceptCaffeineAndKeyformat() {
    pureLibrary(BASE, "com.github.benmanes.caffeine..", "dev.keyedge.keyformat..").check(classes);
    noHiddenRandomnessOrTime(BASE).check(classes);
  }

  @Test
  void nanoTimeIsReadOnlyByTheSystemTicker() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(Ticker.class.getName())
        .should()
        .callMethod(System.class, "nanoTime")
        .check(classes);
  }

  @Test
  void productionCodeNeverUsesThePitfalls() {
    noClasses()
        .that()
        .resideOutsideOfPackage(BASE + ".lab.pitfalls..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(BASE + ".lab.pitfalls..")
        .check(classes);
  }
}
