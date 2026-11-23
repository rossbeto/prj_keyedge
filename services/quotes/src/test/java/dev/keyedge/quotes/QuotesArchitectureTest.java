package dev.keyedge.quotes;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.keyedge.quotes.api.ReactiveQuotesController;
import org.junit.jupiter.api.Test;

/** Where the concurrency models may appear (Phase 009). */
class QuotesArchitectureTest {
  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("dev.keyedge.quotes");

  @Test
  void reactorOnlyInTheReactiveAdapterAndItsController() {
    noClasses()
        .that()
        .resideOutsideOfPackage("dev.keyedge.quotes.adapters.reactor..")
        .and()
        .doNotHaveFullyQualifiedName(ReactiveQuotesController.class.getName())
        .and()
        .haveNameNotMatching(".*\\$ReactiveQuotesController\\$.*") // Micronaut's generated metadata
        .should()
        .dependOnClassesThat()
        .resideInAPackage("reactor..")
        .check(classes);
  }

  @Test
  void noStructuredTaskScopeInQuotes() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.util.concurrent.StructuredTaskScope")
        .because("the preview API stays in libs/fanout-structured, loaded through ServiceLoader")
        .check(classes);
  }
}
