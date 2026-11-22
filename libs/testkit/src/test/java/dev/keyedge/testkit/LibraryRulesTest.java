package dev.keyedge.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class LibraryRulesTest {
  private static final String CLEAN = "dev.keyedge.testkit.fixtures.purelib.clean";
  private static final String DIRTY = "dev.keyedge.testkit.fixtures.purelib.dirty";
  private static final JavaClasses ALL =
      new ClassFileImporter().importPackages("dev.keyedge.testkit.fixtures", "reactor.fixture");

  @Test
  void pureLibraryPassesOnJdkOnlyCodeAndFailsOnALibraryImport() {
    assertThatCode(() -> KeyedgeArchitectureRules.pureLibrary(CLEAN).check(ALL))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> KeyedgeArchitectureRules.pureLibrary(DIRTY).check(ALL))
        .isInstanceOf(AssertionError.class);
    assertThatCode(() -> KeyedgeArchitectureRules.pureLibrary(DIRTY, "reactor..").check(ALL))
        .doesNotThrowAnyException();
  }

  @Test
  void hiddenRandomnessAndTimeAreDetectedUnlessExempt() {
    assertThatCode(() -> KeyedgeArchitectureRules.noHiddenRandomnessOrTime(CLEAN).check(ALL))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> KeyedgeArchitectureRules.noHiddenRandomnessOrTime(DIRTY).check(ALL))
        .isInstanceOf(AssertionError.class);
    assertThatCode(
            () -> KeyedgeArchitectureRules.noHiddenRandomnessOrTime(DIRTY, DIRTY + "..").check(ALL))
        .doesNotThrowAnyException();
  }

  @Test
  void checkAllAcceptsAnEmptyPackageAndImportMainSkipsTests() {
    assertThatCode(() -> KeyedgeArchitectureRules.checkAll("dev.keyedge.nothing.here"))
        .doesNotThrowAnyException();
    assertThat(KeyedgeArchitectureRules.importMain("dev.keyedge.testkit.fixtures")).isEmpty();
    assertThat(KeyedgeArchitectureRules.all("x")).hasSize(5);
  }

  @Test
  void pitfallPackagesAreRecognised() {
    var classes = new ClassFileImporter().importClasses(String.class);
    assertThat(KeyedgeArchitectureRules.isPitfall(classes.get(String.class))).isFalse();
  }
}
