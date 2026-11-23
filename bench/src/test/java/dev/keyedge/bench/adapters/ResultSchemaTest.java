package dev.keyedge.bench.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.bench.Fixtures;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class ResultSchemaTest {
  final JsonNode schema =
      Json.JSON.readTree(Path.of("results/schema/bench-result.v1.json").toFile());

  @Test
  void everyCommittedResultValidates() {
    assertThat(
            SchemaCheck.validateAll(
                Path.of("results/bench"), Path.of("results/schema/bench-result.v1.json")))
        .isEmpty();
  }

  @Test
  void whatTheBenchWritesValidates() {
    var r =
        Fixtures.result(
            "bench-smoke",
            List.of(Fixtures.step("jvm", 20, 10, 0), Fixtures.step("jvm", 20, 10, 0).invalid("x")),
            "confirmed");
    assertThat(SchemaCheck.validate(schema, Json.JSON.valueToTree(r))).isEmpty();
  }

  @Test
  void violationsAreNamedWithTheirPath() {
    var r = Fixtures.result("bench-smoke", List.of(Fixtures.step("jvm", 20, 10, 0)), "maybe");
    ObjectNode doc = Json.JSON.valueToTree(r);
    doc.put("extra", 1);
    doc.remove("notes");
    ((ObjectNode) doc.get("steps").get(0)).put("requests", -1);
    ((ObjectNode) doc.get("git")).put("dirty", "no");
    assertThat(SchemaCheck.validate(schema, doc))
        .contains(
            "$: missing notes",
            "$: unexpected property extra",
            "$.verdict: not one of [\"confirmed\",\"refuted\",\"inconclusive\"]",
            "$.steps[0].requests: below minimum 0",
            "$.git.dirty: expected type \"boolean\"");
  }
}
