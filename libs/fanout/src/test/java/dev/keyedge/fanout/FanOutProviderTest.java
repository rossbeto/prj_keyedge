package dev.keyedge.fanout;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static dev.keyedge.testkit.KeyedgeArchitectureRules.noHiddenRandomnessOrTime;
import static dev.keyedge.testkit.KeyedgeArchitectureRules.pureLibrary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class FanOutProviderTest {
  @Test
  void findsTheFutureAdapterAndFailsClearlyForAMissingOne() {
    try (var vt = Executors.newVirtualThreadPerTaskExecutor()) {
      var r = new FanOutProvider.Resources(System::nanoTime, vt, ContextCarrier.none());
      assertThat(FanOutProvider.load("future", r)).isInstanceOf(FutureFanOut.class);
      assertThatThrownBy(() -> FanOutProvider.load("structured", r)) // not on this classpath
          .hasMessageContaining("no fan-out adapter 'structured'");
    }
  }

  @Test
  void isAPureLibraryWithInjectedTime() {
    var classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(location ->
                !location.contains("testFixtures") && !location.contains("test-fixtures"))
            .importPackages("dev.keyedge.fanout");
    pureLibrary("dev.keyedge.fanout").check(classes);
    noHiddenRandomnessOrTime("dev.keyedge.fanout").check(classes);
    noClasses().should().callMethod(System.class, "nanoTime").check(classes);
  }
}
