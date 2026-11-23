package dev.keyedge.bench;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Layering of the bench and its independence from the services it measures. */
@AnalyzeClasses(
    packages = "dev.keyedge.bench",
    importOptions = ImportOption.DoNotIncludeTests.class)
class BenchArchitectureTest {
  @ArchTest
  static final ArchRule domainIsPure =
      noClasses()
          .that()
          .resideInAPackage("..bench.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..bench.app..",
              "..bench.adapters..",
              "..bench.cli..",
              "tools.jackson..",
              "picocli..");

  @ArchTest
  static final ArchRule appDependsOnPortsNotAdapters =
      noClasses()
          .that()
          .resideInAPackage("..bench.app..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..bench.adapters..", "..bench.cli..", "tools.jackson..");

  @ArchTest
  static final ArchRule neverDependsOnTheServices =
      noClasses()
          .that()
          .resideInAPackage("dev.keyedge.bench..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "dev.keyedge.edge..",
              "dev.keyedge.quotes..",
              "dev.keyedge.keys..",
              "dev.keyedge.sim..",
              "dev.keyedge.issuer..");
}
