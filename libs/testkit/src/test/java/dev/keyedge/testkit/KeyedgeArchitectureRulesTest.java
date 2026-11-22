package dev.keyedge.testkit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Each rule passes on the good fixture and fails on the bad one: a rule that cannot fail proves
 * nothing.
 */
class KeyedgeArchitectureRulesTest {
  private static final String GOOD = "dev.keyedge.testkit.fixtures.good";
  private static final String BAD = "dev.keyedge.testkit.fixtures.bad";
  private static final JavaClasses ALL =
      new ClassFileImporter().importPackages("dev.keyedge.testkit.fixtures", "reactor.fixture");

  static Stream<Arguments> rules() {
    return Stream.of(
        Arguments.of("layers", (Function<String, ArchRule>) KeyedgeArchitectureRules::layers),
        Arguments.of(
            "coreIsFrameworkFree",
            (Function<String, ArchRule>) KeyedgeArchitectureRules::coreIsFrameworkFree),
        Arguments.of(
            "noClockReadsOutsideWiring",
            (Function<String, ArchRule>) KeyedgeArchitectureRules::noClockReadsOutsideWiring),
        Arguments.of(
            "noUtilsOrHelpers",
            (Function<String, ArchRule>) KeyedgeArchitectureRules::noUtilsOrHelpers),
        Arguments.of(
            "adaptersIndependent",
            (Function<String, ArchRule>) KeyedgeArchitectureRules::adaptersIndependent));
  }

  @ParameterizedTest(name = "{0} passes on the good fixture")
  @MethodSource("rules")
  void passesOnGoodFixture(String name, Function<String, ArchRule> rule) {
    assertThatCode(() -> rule.apply(GOOD).check(ALL)).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "{0} fails on the bad fixture")
  @MethodSource("rules")
  void failsOnBadFixture(String name, Function<String, ArchRule> rule) {
    assertThatThrownBy(() -> rule.apply(BAD).check(ALL)).isInstanceOf(AssertionError.class);
  }
}
