package dev.keyedge.sim.adapters;

import dev.keyedge.sim.domain.LatencyDistribution;
import dev.keyedge.sim.domain.Scenario;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads scenarios from YAML (classpath {@code scenarios/<name>.yaml} or a request body). Safe
 * constructor only.
 */
public final class YamlScenarios implements dev.keyedge.sim.ports.ScenarioSource {
  /** The port view of this loader. */
  public static final YamlScenarios INSTANCE = new YamlScenarios();

  private YamlScenarios() {}

  @Override
  public Scenario named(String name) {
    return load(name);
  }

  @Override
  public Scenario parse(String document) {
    return read(document);
  }

  public static Scenario load(String name) {
    if (!name.matches("[a-z0-9-]{1,40}")) {
      throw new IllegalArgumentException("invalid scenario name");
    }
    try (InputStream in = YamlScenarios.class.getResourceAsStream("/scenarios/" + name + ".yaml")) {
      if (in == null) {
        throw new IllegalArgumentException("unknown scenario " + name);
      }
      return read(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @SuppressWarnings("unchecked")
  public static Scenario read(String yaml) {
    Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
    if (!(root instanceof Map<?, ?> m)) {
      throw new IllegalArgumentException("scenario must be a mapping");
    }
    var map = (Map<String, Object>) m;
    var endpoints = new LinkedHashMap<String, Scenario.Endpoint>();
    var eps = (Map<String, Object>) map.getOrDefault("endpoints", Map.of());
    eps.forEach((name, spec) -> endpoints.put(name, endpoint((Map<String, Object>) spec)));
    Scenario.TokenPolicy token = Scenario.TokenPolicy.DEFAULT;
    if (map.get("token") instanceof Map<?, ?> t) {
      var tm = (Map<String, Object>) t;
      token =
          new Scenario.TokenPolicy(
              num(tm.getOrDefault("ttlSeconds", 300)),
              tm.get("issueLatency") instanceof Map<?, ?> l
                  ? latency((Map<String, Object>) l)
                  : new LatencyDistribution.Fixed(20));
    }
    return new Scenario(
        String.valueOf(map.getOrDefault("name", "custom")),
        num(map.getOrDefault("seed", 1)),
        endpoints,
        token);
  }

  @SuppressWarnings("unchecked")
  private static Scenario.Endpoint endpoint(Map<String, Object> spec) {
    LatencyDistribution latency =
        spec.get("latency") instanceof Map<?, ?> l
            ? latency((Map<String, Object>) l)
            : new LatencyDistribution.Fixed(5);
    List<Scenario.ErrorRule> errors = new ArrayList<>();
    for (Object o : (List<Object>) spec.getOrDefault("errors", List.of())) {
      var e = (Map<String, Object>) o;
      errors.add(
          new Scenario.ErrorRule(
              (int) num(e.get("status")),
              dbl(e.get("rate")),
              Optional.ofNullable(e.get("retryAfterSeconds")).map(v -> (int) num(v))));
    }
    Optional<Scenario.SlowCloseRule> slow =
        spec.get("slowClose") instanceof Map<?, ?> s
            ? Optional.of(
                new Scenario.SlowCloseRule(
                    dbl(((Map<String, Object>) s).get("rate")),
                    num(((Map<String, Object>) s).get("pauseMs"))))
            : Optional.empty();
    return new Scenario.Endpoint(latency, errors, slow);
  }

  private static LatencyDistribution latency(Map<String, Object> l) {
    return switch (String.valueOf(l.get("kind"))) {
      case "fixed" -> new LatencyDistribution.Fixed(num(l.get("ms")));
      case "uniform" -> new LatencyDistribution.Uniform(num(l.get("minMs")), num(l.get("maxMs")));
      case "lognormal" ->
          new LatencyDistribution.LogNormal(num(l.get("medianMs")), num(l.get("p99Ms")));
      case "bimodal" ->
          new LatencyDistribution.Bimodal(
              num(l.get("fastMs")), num(l.get("slowMs")), dbl(l.get("slowRate")));
      default -> throw new IllegalArgumentException("unknown latency kind " + l.get("kind"));
    };
  }

  private static long num(@org.jspecify.annotations.Nullable Object o) {
    if (o instanceof Number n) {
      return n.longValue();
    }
    throw new IllegalArgumentException("expected a number, got " + o);
  }

  private static double dbl(@org.jspecify.annotations.Nullable Object o) {
    if (o instanceof Number n) {
      return n.doubleValue();
    }
    throw new IllegalArgumentException("expected a number, got " + o);
  }
}
