package dev.keyedge.bench.app;

import static dev.keyedge.bench.Fixtures.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.keyedge.bench.Fixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotebookTest {
  static final String DOC =
      """
      # Lab notebook

      <!-- notebook:index:begin -->
      old
      <!-- notebook:index:end -->

      ## naive-bucket-race (Phase 003)

      **Result**: verdict **confirmed**. Twelve per cent.

      ## native-vs-jvm (Phase 010)

      **Result**: verdict **refuted** as stated.
      """;

  @Test
  void aTwoExperimentNotebookRendersAsTheGoldenFile() throws IOException {
    var entries =
        List.of(
            new Notebook.LabEntry(
                "naive-bucket-race", Map.of("results/labs/naive-bucket-race.json", "confirmed")),
            new Notebook.LabEntry(
                "native-vs-jvm", Map.of("results/labs/native-vs-jvm.json", "refuted")));
    String rendered =
        Notebook.replaceBlock(DOC, "notebook:index", Notebook.index(entries, Notebook.phases(DOC)));
    Path golden = Path.of("bench/src/test/resources/golden/notebook-2.md");
    if (Boolean.getBoolean("updateGolden")) {
      Files.createDirectories(golden.getParent());
      Files.writeString(golden, rendered);
    }
    assertThat(rendered).isEqualTo(Files.readString(golden));
  }

  @Test
  void proseVerdictsMustAgreeWithTheirFiles() {
    assertThat(
            Notebook.verdictMismatches(
                DOC, Map.of("naive-bucket-race", "confirmed", "native-vs-jvm", "refuted")))
        .isEmpty();
    assertThat(Notebook.verdictMismatches(DOC, Map.of("native-vs-jvm", "confirmed")))
        .containsExactly("native-vs-jvm: notebook says refuted, results say confirmed");
  }

  @Test
  void perTargetFilesThatDisagreeAreShownSeparately() {
    var e = new Notebook.LabEntry("x", Map.of("a.json", "confirmed", "b.json", "refuted"));
    assertThat(e.verdict()).isEqualTo("confirmed (a.json), refuted (b.json)");
  }

  @Test
  void missingMarkersAreAnError() {
    assertThatThrownBy(() -> Notebook.replaceBlock("no markers", "bench", "x"))
        .hasMessageContaining("missing");
  }

  @Test
  void aBenchSectionCarriesPredictionTableVerdictAndWhere() {
    var r =
        Fixtures.result(
            "vt-vs-reactor",
            List.of(step("reactor", 100, 40, 0), step("reactor", 200, 60, 0)),
            "confirmed");
    String s =
        Notebook.benchSection(
            r, "015", "results/bench/run-1/bench-result.json", "media/vt-vs-reactor.svg");
    assertThat(s)
        .startsWith("## vt-vs-reactor (Phase 015)")
        .contains("**Prediction** (written 2026-11-24T09:59:00Z")
        .contains("| reactor | 200 | 20.00 | 60.00 | 0.00 | 0.50 | 1/1 |")
        .contains("Saturation (highest rate meeting the SLO): reactor 200 rps.")
        .contains("verdict **confirmed**")
        .contains("emulator floci 2.5.0, commit abc1234");
  }
}
