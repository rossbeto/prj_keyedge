package dev.keyedge.infra;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keyedge.infra.config.InfraConfig;
import dev.keyedge.infra.config.InfraSpec;
import dev.keyedge.infra.config.Mode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The cdk-nag gate and the snapshots. A full synth (validation plugins run on {@code app.synth()}):
 * no unacknowledged violation, and the acknowledged rules per mode match the recorded list, so a
 * new acknowledgment shows up in review. Templates must match the committed snapshots (update:
 * {@code ./gradlew :infra:java:test -Pcdk -PupdateSnapshots=true}).
 */
class NagAndSnapshotTest {
  private static final Path SNAPSHOTS = Path.of("infra/java/src/test/resources/snapshots");
  private static final boolean UPDATE = Boolean.getBoolean("updateSnapshots");
  private static final Pattern RULE = Pattern.compile("\"ruleName\"\\s*:\\s*\"([^\"]+)\"");

  @ParameterizedTest
  @EnumSource(Mode.class)
  void nagPassesWithTheRecordedAcknowledgments(Mode mode, @TempDir Path out) throws IOException {
    var app = InfraApp.newApp(Map.of("mode", mode.key(), "env", mode.key()), out.toString());
    InfraApp.build(app, InfraConfig.of(mode, mode.key(), InfraSpec.load()));
    app.synth(); // throws if a validation plugin reports an unacknowledged error
    String report = Files.readString(out.resolve("validation-report.json"));
    @SuppressWarnings("unchecked")
    var doc = (Map<String, Object>) new org.yaml.snakeyaml.Yaml().load(report); // JSON is YAML
    @SuppressWarnings("unchecked")
    var plugin = ((List<Map<String, Object>>) doc.get("pluginReports")).get(0);
    assertThat(plugin.get("pluginName")).isEqualTo("AwsSolutions");
    assertThat(plugin.get("conclusion")).isEqualTo("success");
    assertThat((List<?>) plugin.get("violations")).isEmpty();
    int suppressedAt = report.indexOf("suppressedViolations");
    var counts = new TreeMap<String, Integer>();
    Matcher m = RULE.matcher(report.substring(Math.max(0, suppressedAt)));
    while (m.find()) {
      counts.merge(m.group(1), 1, Integer::sum);
    }
    var lines = new StringBuilder();
    counts.forEach((rule, n) -> lines.append(rule).append(' ').append(n).append('\n'));
    compare("nag-acknowledged-" + mode.key() + ".txt", lines.toString());
  }

  @ParameterizedTest
  @EnumSource(Mode.class)
  void templatesMatchTheSnapshots(Mode mode) throws IOException {
    for (var e : Synth.templates(mode).entrySet()) {
      String json = pretty(e.getValue().toJSON());
      compare(mode.key() + "/" + e.getKey() + ".template.json", json);
    }
  }

  private static String pretty(Map<String, Object> template) {
    return new org.yaml.snakeyaml.Yaml()
        .dump(new TreeMap<>(template)); // stable, diff-friendly text
  }

  private static void compare(String name, String actual) throws IOException {
    Path file = SNAPSHOTS.resolve(name);
    if (UPDATE || !Files.exists(file)) {
      Files.createDirectories(file.getParent());
      Files.writeString(file, actual);
      if (!UPDATE) {
        throw new AssertionError(
            "snapshot " + file + " was missing and has been written: review and commit it");
      }
      return;
    }
    assertThat(actual)
        .as("snapshot " + name + " (update with -PupdateSnapshots=true)")
        .isEqualTo(Files.readString(file));
  }

  static List<String> unused() {
    return List.of();
  }
}
