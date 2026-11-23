package dev.keyedge.infra;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Layering of the CDK app (Phase 012 plan): config ← constructs ← stacks ← app. */
class InfraArchitectureTest {
  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("dev.keyedge.infra");

  @Test
  void constructsDoNotKnowStacks() {
    noClasses()
        .that()
        .resideInAPackage("..infra.constructs..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..infra.stacks..", "..infra.config..")
        .because("constructs take props records; they read neither context nor the spec")
        .check(classes);
  }

  @Test
  void configDoesNotKnowConstructsOrStacks() {
    noClasses()
        .that()
        .resideInAPackage("..infra.config..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..infra.constructs..", "..infra.stacks..", "software.amazon.awscdk..")
        .check(classes);
  }

  @Test
  void onlyTheAppReadsTheEnvironment() {
    noClasses()
        .should()
        .callMethod(System.class, "getenv", String.class)
        .orShould()
        .callMethod(System.class, "getenv")
        .check(classes);
  }
}
