package dev.keyedge.bench.app;

import static dev.keyedge.bench.Fixtures.scenario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.keyedge.bench.domain.BenchResult;
import dev.keyedge.bench.domain.Fingerprint;
import dev.keyedge.bench.domain.Prediction;
import dev.keyedge.bench.domain.Scenario;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RunScenarioTest {
  final Clock clock = Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC);
  final MemoryStore store = new MemoryStore();
  final FakeStack stack = new FakeStack();
  final List<LoadGenerator.LoadRequest> offered = new ArrayList<>();
  long dropped;
  boolean dirty;
  String emulatorVersion = "2.5.0";

  final LoadGenerator load =
      r -> {
        offered.add(r);
        double p99 = r.rate() >= 300 ? 400 : 20;
        return new LoadGenerator.LoadOutcome(r.rate(), 5, 10, p99, p99, r.rate() * 5L, 0, dropped);
      };
  final Environment env =
      new Environment() {
        @Override
        public Fingerprint fingerprint() {
          return dev.keyedge.bench.Fixtures.fingerprint(emulatorVersion);
        }

        @Override
        public BenchResult.Git git() {
          return new BenchResult.Git("abc", dirty);
        }
      };

  RunScenario runner() {
    return new RunScenario(load, stack, store, env, clock, s -> {});
  }

  @Test
  void refusesWithoutAPredictionOrWithAnotherScenarios() {
    Scenario s = scenario("vt-vs-reactor", List.of(100), "reactor");
    assertThatThrownBy(() -> runner().run(s, "r1")).hasMessageContaining("bench predict");
    store.save(new Prediction("other", "r1", clock.instant(), "x", Map.of()));
    assertThatThrownBy(() -> runner().run(s, "r1")).hasMessageContaining("bench predict");
    assertThat(offered).isEmpty();
  }

  @Test
  void recreatesEachVariantWarmsUpAndFindsSaturation() {
    Scenario s =
        new Scenario(
            "vt-vs-reactor",
            "h",
            "edge",
            "/p",
            "healthy",
            List.of(100, 200, 300),
            5,
            3,
            2,
            dev.keyedge.bench.Fixtures.SLO,
            scenario("x", List.of(1), "v").model(),
            List.of(
                new Scenario.Variant("structured", "jvm", Map.of(), null, null, null, null),
                new Scenario.Variant("reactor", "jvm", Map.of(), "/reactive", null, null, null)));
    new Predict(store, clock).predict(s, "r1");
    BenchResult r = runner().run(s, "r1");
    assertThat(stack.prepared).containsExactly("structured", "reactor");
    assertThat(stack.simulated).containsExactly("healthy", "healthy");
    assertThat(offered).hasSize(2 * (1 + 3 * 2)); // warm-up + rates x repetitions, per variant
    assertThat(offered.get(7).path()).isEqualTo("/reactive");
    assertThat(r.saturationRps()).containsEntry("structured", 200).containsEntry("reactor", 200);
    assertThat(r.steps()).allMatch(st -> st.cpuPerReqMs() != null);
    assertThat(r.verdict()).isEqualTo("confirmed");
    assertThat(store.saved).isNotNull();
    assertThat(r.notes()).isEmpty();
  }

  @Test
  void droppedIterationsAndDirtyTreesMakeStepsInvalid() {
    Scenario s = scenario("bench-smoke", List.of(20), "jvm");
    new Predict(store, clock).predict(s, "r1");
    dropped = 3;
    assertThat(runner().run(s, "r1").steps())
        .allMatch(st -> !st.valid() && st.invalidReason().contains("dropped"));
    dropped = 0;
    dirty = true;
    assertThat(runner().run(s, "r1").steps())
        .allMatch(st -> st.invalidReason().contains("uncommitted"));
  }

  @Test
  void anUnknownEmulatorVersionAndBusyBuildsAreNoted() {
    Scenario s = scenario("bench-smoke", List.of(20), "jvm");
    new Predict(store, clock).predict(s, "r1");
    emulatorVersion = Fingerprint.UNKNOWN;
    stack.builds = List.of("123 GradleDaemon");
    assertThat(runner().run(s, "r1").notes())
        .anyMatch(n -> n.contains("emulator version unknown"))
        .anyMatch(n -> n.contains("Gradle"));
  }

  @Test
  void predictionUsesLittlesLawAndTheLowerBound() {
    Prediction p =
        new Predict(store, clock).predict(scenario("vt-vs-reactor", List.of(400), "v"), "r9");
    assertThat(p.numbers())
        .containsEntry("in_flight@400", 16.0)
        .containsEntry("cap_bound_rps", 12500.0)
        .containsEntry("cpu_bound_rps", 2000.0)
        .containsEntry("predicted_saturation_rps", 2000.0);
    assertThat(p.statement()).contains("CPU-bound");
    assertThat(store.prediction("r9")).isPresent();
    var noModel =
        new Scenario(
            "x",
            "h",
            "edge",
            "/p",
            "healthy",
            List.of(1),
            1,
            0,
            1,
            dev.keyedge.bench.Fixtures.SLO,
            Map.of(),
            List.of(new Scenario.Variant("v", "jvm", Map.of(), null, null, null, null)));
    assertThatThrownBy(() -> new Predict(store, clock).predict(noModel, "r"))
        .hasMessageContaining("latencyMs");
  }

  @Test
  void authNeverPrintsItsSecrets() {
    var a = new Auth(List.of("ke_secret"), "t|k|s", "c2VjcmV0");
    assertThat(a.toString()).doesNotContain("ke_secret").doesNotContain("c2VjcmV0");
  }

  static final class MemoryStore implements ResultStore {
    final Map<String, Prediction> predictions = new HashMap<>();
    BenchResult saved;

    @Override
    public Optional<Prediction> prediction(String runId) {
      return Optional.ofNullable(predictions.get(runId));
    }

    @Override
    public void save(Prediction p) {
      predictions.put(p.runId(), p);
    }

    @Override
    public void save(BenchResult r) {
      saved = r;
    }
  }

  static final class FakeStack implements StackControl {
    final List<String> prepared = new ArrayList<>();
    final List<String> simulated = new ArrayList<>();
    List<String> builds = List.of();
    long cpu;

    @Override
    public void prepare(Scenario.Variant v) {
      prepared.add(v.name());
    }

    @Override
    public void simulate(String s) {
      simulated.add(s);
    }

    @Override
    public Auth credentials(int maxRate) {
      return new Auth(List.of("k"), "t|k|s", "a2V5");
    }

    @Override
    public long sutCpuMicros() {
      return cpu += 500_000;
    }

    @Override
    public List<String> competingBuilds() {
      return builds;
    }
  }
}
