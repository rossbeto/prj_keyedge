package dev.keyedge.bench.cli;

import dev.keyedge.bench.adapters.ComposeStack;
import dev.keyedge.bench.adapters.FsResultStore;
import dev.keyedge.bench.adapters.HostEnvironment;
import dev.keyedge.bench.adapters.Json;
import dev.keyedge.bench.adapters.K6Docker;
import dev.keyedge.bench.adapters.ReportFiles;
import dev.keyedge.bench.app.Predict;
import dev.keyedge.bench.app.RunScenario;
import dev.keyedge.bench.domain.BenchResult;
import dev.keyedge.bench.domain.Scenario;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code bench predict|run|report|fingerprint|validate} (Phase 015). Configuration from the
 * environment `make` passes: KEYEDGE_AWS_TARGET, BENCH_SUT_CPUS, BENCH_SUPPORT_CPUS, BENCH_K6_CPUS.
 */
@Command(
    name = "bench",
    mixinStandardHelpOptions = true,
    subcommands = {
      BenchCli.PredictCmd.class,
      BenchCli.RunCmd.class,
      BenchCli.ReportCmd.class,
      BenchCli.FingerprintCmd.class,
      BenchCli.ValidateCmd.class
    })
public final class BenchCli {
  static final Path RESULTS = Path.of("results/bench");

  private BenchCli() {}

  public static void main(String[] args) {
    System.exit(new CommandLine(BenchCli.class).execute(args));
  }

  static String env(String name, String dflt) {
    String v = System.getenv(name);
    return v == null || v.isBlank() ? dflt : v;
  }

  static Scenario scenario(String name) {
    return Json.YAML.readValue(Path.of("bench/scenarios", name + ".yaml").toFile(), Scenario.class);
  }

  @Command(name = "predict", description = "Write the prediction of a run (before any load)")
  static final class PredictCmd implements Callable<Integer> {
    @Option(names = "--scenario", required = true)
    String scenario = "";

    @Option(names = "--run", required = true)
    String run = "";

    @Override
    public Integer call() {
      var p =
          new Predict(new FsResultStore(RESULTS), Clock.systemUTC())
              .predict(scenario(scenario), run);
      System.out.println("prediction written: " + p.statement());
      return 0;
    }
  }

  @Command(name = "run", description = "Run a scenario whose prediction exists")
  static final class RunCmd implements Callable<Integer> {
    @Option(names = "--scenario", required = true)
    String scenario = "";

    @Option(names = "--run", required = true)
    String run = "";

    @Override
    public Integer call() {
      String target = env("KEYEDGE_AWS_TARGET", "floci");
      Map<String, String> containerEnv =
          System.getenv().entrySet().stream()
              .filter(e -> e.getKey().startsWith("KEYEDGE_"))
              .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
      var runner =
          new RunScenario(
              new K6Docker(Path.of("bench/k6"), env("BENCH_K6_CPUS", ""), "keyedge-edge-1"),
              new ComposeStack(
                  containerEnv, env("BENCH_SUT_CPUS", ""), env("BENCH_SUPPORT_CPUS", ""), target),
              new FsResultStore(RESULTS),
              new HostEnvironment(target),
              Clock.systemUTC(),
              System.out::println);
      BenchResult r = runner.run(scenario(scenario), run);
      System.out.println(r.scenario() + ": " + r.verdict() + " — " + r.verdictDetail());
      System.out.println("saturation: " + r.saturationRps());
      return 0;
    }
  }

  @Command(name = "report", description = "Render the notebook blocks, README block and charts")
  static final class ReportCmd implements Callable<Integer> {
    @Option(
        names = "--check",
        description = "Write nothing; fail if anything is stale or inconsistent")
    boolean check;

    @Override
    public Integer call() {
      List<String> diffs = new ReportFiles(Path.of("")).run(check);
      diffs.forEach(System.out::println);
      if (check && !diffs.isEmpty()) {
        return 1;
      }
      System.out.println(check ? "docs-check: OK" : "report: " + diffs.size() + " file(s) updated");
      return 0;
    }
  }

  @Command(name = "fingerprint", description = "Print the environment fingerprint")
  static final class FingerprintCmd implements Callable<Integer> {
    @Override
    public Integer call() {
      System.out.println(
          Json.JSON.writeValueAsString(
              new HostEnvironment(env("KEYEDGE_AWS_TARGET", "floci")).fingerprint()));
      return 0;
    }
  }

  @Command(
      name = "validate",
      description = "Validate every results/bench/*/bench-result.json against schema v1")
  static final class ValidateCmd implements Callable<Integer> {
    @Override
    public Integer call() {
      var errors =
          dev.keyedge.bench.adapters.SchemaCheck.validateAll(
              RESULTS, Path.of("results/schema/bench-result.v1.json"));
      errors.forEach(System.out::println);
      System.out.println(errors.isEmpty() ? "results valid" : errors.size() + " problem(s)");
      return errors.isEmpty() ? 0 : 1;
    }
  }
}
