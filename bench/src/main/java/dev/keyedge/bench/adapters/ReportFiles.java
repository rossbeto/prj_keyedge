package dev.keyedge.bench.adapters;

import dev.keyedge.bench.app.Notebook;
import dev.keyedge.bench.app.SvgChart;
import dev.keyedge.bench.app.SvgChart.Point;
import dev.keyedge.bench.domain.BenchResult;
import dev.keyedge.bench.domain.StepResult;
import dev.keyedge.bench.domain.Steps;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * `make report` / `make docs-check`: renders the generated blocks of docs/lab-notebook.md and
 * README.md and the charts in docs/media from the results files; in check mode writes nothing and
 * lists every difference (stale block, stale chart, prose verdict disagreeing with its file).
 */
public final class ReportFiles {
  static final List<String> BENCH_SCENARIOS =
      List.of("vt-vs-reactor", "native-overhead", "fargate-size");
  private final Path root;

  public ReportFiles(Path root) {
    this.root = root.toAbsolutePath();
  }

  /** Returns the differences found (check) or applied (write). */
  public List<String> run(boolean check) {
    Map<String, String> files = new LinkedHashMap<>();
    String notebook = read("docs/lab-notebook.md");
    Map<String, String> phases = Notebook.phases(notebook);
    List<Notebook.LabEntry> entries = labEntries();
    Map<String, BenchResult> latest = new LinkedHashMap<>();
    for (String s : BENCH_SCENARIOS) {
      latestBench(s).ifPresent(r -> latest.put(s, r));
    }
    latest.forEach(
        (s, r) -> entries.add(new Notebook.LabEntry(s, Map.of(benchPath(r), r.verdict()))));
    String nb = Notebook.replaceBlock(notebook, "notebook:index", Notebook.index(entries, phases));
    for (String s : BENCH_SCENARIOS) {
      BenchResult r = latest.get(s);
      String body =
          r == null
              ? "## " + s + " (Phase 015)\n\nNot run yet: `make bench S=" + s + "`.\n"
              : Notebook.benchSection(r, "015", benchPath(r), "media/" + s + ".svg");
      nb = Notebook.replaceBlock(nb, "bench:" + s, body);
      if (r != null) {
        files.put("docs/media/" + s + ".svg", chart(r));
      }
    }
    files.put("docs/lab-notebook.md", nb);
    files.put("docs/media/native-vs-jvm.svg", nativeVsJvm());
    files.put("docs/media/control-plane-outage.svg", outage());
    files.put(
        "README.md", Notebook.replaceBlock(read("README.md"), "bench", readme(entries, latest)));

    List<String> diffs = new ArrayList<>();
    Map<String, String> verdicts = new TreeMap<>();
    entries.forEach(e -> verdicts.put(e.id(), e.verdict()));
    diffs.addAll(Notebook.verdictMismatches(nb, verdicts));
    for (var f : files.entrySet()) {
      Path p = root.resolve(f.getKey());
      String current = Files.exists(p) ? read(f.getKey()) : "";
      if (!current.equals(f.getValue())) {
        diffs.add(f.getKey() + (check ? " is stale: run `make report`" : " updated"));
        if (!check) {
          write(p, f.getValue());
        }
      }
    }
    return diffs;
  }

  private List<Notebook.LabEntry> labEntries() {
    Map<String, Map<String, String>> byId = new TreeMap<>();
    List<Path> paths = new ArrayList<>(list("results/labs"));
    paths.add(root.resolve("results/ecs/task-start-order.json"));
    paths.add(root.resolve("results/ecs/drain-on-deploy.json"));
    for (Path p : paths) {
      if (!Files.exists(p)) {
        continue;
      }
      JsonNode n = Json.JSON.readTree(p.toFile());
      String id = n.path("experiment").asString("");
      String verdict = n.path("verdict").asString("");
      if (!id.isEmpty() && !verdict.isEmpty()) {
        byId.computeIfAbsent(id, k -> new TreeMap<>()).put(root.relativize(p).toString(), verdict);
      }
    }
    List<Notebook.LabEntry> out = new ArrayList<>();
    byId.forEach((id, v) -> out.add(new Notebook.LabEntry(id, v)));
    return out;
  }

  private Optional<BenchResult> latestBench(String scenario) {
    return list("results/bench").stream()
        .map(d -> d.resolve("bench-result.json"))
        .filter(Files::exists)
        .map(f -> Json.JSON.readValue(f.toFile(), BenchResult.class))
        .filter(r -> r.scenario().equals(scenario))
        .max(Comparator.comparing(BenchResult::startedAt));
  }

  private static String benchPath(BenchResult r) {
    return "results/bench/" + r.runId() + "/bench-result.json";
  }

  private static String chart(BenchResult r) {
    List<Integer> rates =
        r.steps().stream().map(StepResult::offeredRps).distinct().sorted().toList();
    List<String> variants = r.steps().stream().map(StepResult::variant).distinct().toList();
    if (rates.size() == 1) { // fargate-size: one rate, p99 per variant
      Map<String, Map<String, Double>> groups = new LinkedHashMap<>();
      for (String v : variants) {
        Steps.median(r.steps(), v, rates.getFirst(), StepResult::p99Ms)
            .ifPresent(p -> groups.put(v, Map.of("p99", round(p))));
      }
      return SvgChart.bars(
          r.scenario() + ": p99 at " + rates.getFirst() + " rps by edge size", "p99 ms", groups);
    }
    Map<String, List<Point>> series = new LinkedHashMap<>();
    if (r.scenario().equals("vt-vs-reactor")) { // the verdict turned on errors, not latency
      for (String v : variants) {
        List<Point> pts = new ArrayList<>();
        for (int rate : rates) {
          Steps.median(r.steps(), v, rate, s -> s.errorRatio() * 100)
              .ifPresent(y -> pts.add(new Point(rate, round(y))));
        }
        series.put(v, pts);
      }
      return SvgChart.lines(
          r.scenario() + ": errors vs offered load (median of 3)",
          "offered load (req/s)",
          "% errors",
          series,
          variants);
    }
    for (String v : variants) {
      for (String q : List.of("p50", "p99")) {
        List<Point> pts = new ArrayList<>();
        for (int rate : rates) {
          Steps.median(r.steps(), v, rate, q.equals("p50") ? StepResult::p50Ms : StepResult::p99Ms)
              .ifPresent(y -> pts.add(new Point(rate, round(y))));
        }
        series.put(v + " " + q, pts);
      }
    }
    return SvgChart.lines(
        r.scenario() + ": latency vs offered load (dashed: p99)",
        "offered load (req/s)",
        "ms",
        series,
        variants);
  }

  private String nativeVsJvm() {
    JsonNode n = Json.JSON.readTree(root.resolve("results/labs/native-vs-jvm.json").toFile());
    Map<String, Map<String, Double>> groups = new LinkedHashMap<>();
    for (String[] m :
        new String[][] {
          {"start to ready", "start_to_ready_median_ms"},
          {"RSS idle", "rss_mib_idle"},
          {"RSS after load", "rss_mib_after_load"},
          {"p99 at 200 rps", "p99_ms"},
          {"CPU during load", "cpu_seconds_during_load"}
        }) {
      double jvm = n.path("jvm").path(m[1]).asDouble(0);
      double nat = n.path("native").path(m[1]).asDouble(0);
      if (jvm > 0) {
        Map<String, Double> g = new LinkedHashMap<>();
        g.put("jvm", 100.0);
        g.put("native", round(nat / jvm * 100));
        groups.put(m[0], g);
      }
    }
    return SvgChart.bars(
        "native-vs-jvm: the native edge as % of the JVM edge", "% of JVM (JVM = 100)", groups);
  }

  private String outage() {
    JsonNode n =
        Json.JSON.readTree(root.resolve("results/labs/control-plane-outage.json").toFile());
    Map<String, Map<String, Double>> groups = new LinkedHashMap<>();
    for (String policy : List.of("fail_static", "fail_closed")) {
      Map<String, Double> g = new LinkedHashMap<>();
      g.put(
          "warm keys",
          round(n.path(policy).path("warm_admitted_rate_after_ttl").asDouble(0) * 100));
      g.put(
          "cold keys",
          round(n.path(policy).path("cold_admitted_rate_after_ttl").asDouble(0) * 100));
      groups.put(policy.replace('_', '-'), g);
    }
    return SvgChart.bars(
        "control-plane-outage: requests admitted while keys is down", "% admitted", groups);
  }

  private static String readme(List<Notebook.LabEntry> entries, Map<String, BenchResult> latest) {
    long confirmed = entries.stream().filter(e -> e.verdict().equals("confirmed")).count();
    long refuted = entries.stream().filter(e -> e.verdict().equals("refuted")).count();
    long inconclusive = entries.stream().filter(e -> e.verdict().equals("inconclusive")).count();
    StringBuilder b = new StringBuilder();
    b.append(entries.size())
        .append(" experiments with a hypothesis written before the run: ")
        .append(confirmed)
        .append(" confirmed, ")
        .append(refuted)
        .append(" refuted, ")
        .append(inconclusive)
        .append(" inconclusive ([lab notebook](docs/lab-notebook.md)).\n\n");
    b.append("| Bench | Verdict | Finding | Results |\n|---|---|---|---|\n");
    for (String s : BENCH_SCENARIOS) {
      BenchResult r = latest.get(s);
      if (r == null) {
        b.append("| ").append(s).append(" | not run | — | — |\n");
      } else {
        b.append("| ")
            .append(s)
            .append(" | **")
            .append(r.verdict())
            .append("** | ")
            .append(r.verdictDetail().replace("|", "/"))
            .append(" | [bench-result.json](")
            .append(benchPath(r))
            .append(") |\n");
      }
    }
    b.append("\n![native vs JVM](docs/media/native-vs-jvm.svg)\n");
    return b.toString();
  }

  private List<Path> list(String dir) {
    Path d = root.resolve(dir);
    if (!Files.isDirectory(d)) {
      return List.of();
    }
    try (Stream<Path> s = Files.list(d)) {
      return s.sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String read(String rel) {
    try {
      return Files.readString(root.resolve(rel));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void write(Path p, String content) {
    try {
      Files.createDirectories(p.getParent());
      Files.writeString(p, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static double round(double v) {
    return Math.round(v * 10) / 10.0;
  }
}
