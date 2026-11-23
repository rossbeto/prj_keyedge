package dev.keyedge.bench.app;

import dev.keyedge.bench.domain.BenchResult;
import dev.keyedge.bench.domain.Evaluation;
import dev.keyedge.bench.domain.Hypotheses;
import dev.keyedge.bench.domain.Prediction;
import dev.keyedge.bench.domain.Saturation;
import dev.keyedge.bench.domain.Scenario;
import dev.keyedge.bench.domain.StepResult;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One scenario run: refuses without a prediction, recreates each variant, warms it up (discarded),
 * offers every rate {@code repetitions} times, marks generator-bound or dirty steps invalid, finds
 * saturation per variant and applies the scenario's verdict rule.
 */
public final class RunScenario {
  private final LoadGenerator load;
  private final StackControl stack;
  private final ResultStore store;
  private final Environment env;
  private final Clock clock;
  private final Consumer<String> log;

  public RunScenario(
      LoadGenerator load,
      StackControl stack,
      ResultStore store,
      Environment env,
      Clock clock,
      Consumer<String> log) {
    this.load = load;
    this.stack = stack;
    this.store = store;
    this.env = env;
    this.clock = clock;
    this.log = log;
  }

  public BenchResult run(Scenario scenario, String runId) {
    Prediction prediction =
        store
            .prediction(runId)
            .filter(p -> p.scenario().equals(scenario.name()))
            .orElseThrow(() ->
                new IllegalStateException(
                    "no prediction for run "
                        + runId
                        + " ("
                        + scenario.name()
                        + "): run `bench predict` first"));
    Instant started = clock.instant();
    List<String> notes = new ArrayList<>();
    stack.competingBuilds().forEach(b -> notes.add("Gradle running during the bench: " + b));
    var git = env.git();
    if (git.dirty()) {
      notes.add("uncommitted: " + String.join(", ", env.dirtyFiles()));
    }
    var fingerprint = env.fingerprint();
    if (!fingerprint.complete()) {
      notes.add("emulator version unknown: run flagged");
    }
    Auth auth = stack.credentials(scenario.maxRate());
    List<StepResult> steps = new ArrayList<>();
    for (Scenario.Variant v : scenario.variants()) {
      steps.addAll(runVariant(scenario, v, auth, git.dirty()));
    }
    Map<String, Integer> saturation = new LinkedHashMap<>();
    for (Scenario.Variant v : scenario.variants()) {
      saturation.put(
          v.name(),
          Saturation.find(
                  steps.stream().filter(s -> s.variant().equals(v.name())).toList(), scenario.slo())
              .orElse(0));
    }
    Evaluation e = Hypotheses.evaluate(scenario, steps, saturation);
    BenchResult result =
        new BenchResult(
            BenchResult.SCHEMA,
            runId,
            scenario.name(),
            scenario.hypothesis(),
            started,
            git,
            fingerprint,
            prediction,
            scenario.slo(),
            steps,
            saturation,
            e.verdict().label(),
            e.detail(),
            e.findings(),
            notes);
    store.save(result);
    return result;
  }

  private List<StepResult> runVariant(
      Scenario scenario, Scenario.Variant v, Auth auth, boolean dirty) {
    log.accept("variant " + v.name() + ": recreate");
    stack.prepare(v);
    stack.simulate(scenario.simScenario());
    String target = v.targetOr(scenario.target());
    String path = v.pathOr(scenario.path());
    if (scenario.warmupSeconds() > 0) {
      load.run(
          new LoadGenerator.LoadRequest(
              target, path, scenario.rates().getFirst(), scenario.warmupSeconds(), auth));
    }
    List<StepResult> steps = new ArrayList<>();
    for (int rate : scenario.rates()) {
      for (int rep = 1; rep <= scenario.repetitions(); rep++) {
        StepResult s =
            measure(
                v.name(),
                rep,
                new LoadGenerator.LoadRequest(target, path, rate, scenario.stepSeconds(), auth),
                dirty);
        log.accept(
            String.format(
                java.util.Locale.ROOT,
                "  %s %d rps #%d: p50 %.1f p99 %.1f ms, errors %d/%d%s",
                v.name(),
                rate,
                rep,
                s.p50Ms(),
                s.p99Ms(),
                s.errors(),
                s.requests(),
                s.valid() ? "" : " INVALID (" + s.invalidReason() + ")"));
        steps.add(s);
      }
    }
    return steps;
  }

  private StepResult measure(
      String variant, int rep, LoadGenerator.LoadRequest request, boolean dirty) {
    long cpu0 = stack.sutCpuMicros();
    var out = load.run(request);
    long cpu1 = stack.sutCpuMicros();
    Double cpuPerReq =
        out.requests() > 0 && cpu0 >= 0 && cpu1 >= cpu0
            ? Math.round((cpu1 - cpu0) / 1000.0 / out.requests() * 1000) / 1000.0
            : null;
    StepResult s =
        new StepResult(
            variant,
            request.rate(),
            rep,
            out.achievedRps(),
            out.p50Ms(),
            out.p90Ms(),
            out.p99Ms(),
            out.maxMs(),
            out.requests(),
            out.errors(),
            out.shed(),
            out.droppedIterations(),
            cpuPerReq,
            true,
            null);
    if (out.droppedIterations() > 0) {
      return s.invalid("generator-bound: " + out.droppedIterations() + " dropped iterations");
    }
    return dirty ? s.invalid("uncommitted changes in the working tree") : s;
  }
}
