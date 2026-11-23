package dev.keyedge.infra.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * infra/spec/infra-spec.yaml, read once (D-45). Values are read by dotted path; a missing path is
 * an error that names it, and unknown top-level keys are rejected, so a typo in the spec fails the
 * build in Java and in Python alike (the JSON schema is the shared contract).
 */
public final class InfraSpec {
  public static final Path DEFAULT = Path.of("infra/spec/infra-spec.yaml");
  private static final Set<String> TOP_LEVEL =
      Set.of(
          "version",
          "envs",
          "names",
          "table",
          "secrets",
          "contract",
          "service",
          "cluster",
          "loadBalancer",
          "alarms",
          "ci",
          "nag",
          "images",
          "certificate");

  private final Map<String, Object> root;

  private InfraSpec(Map<String, Object> root) {
    this.root = root;
  }

  public static InfraSpec load(Path file) {
    try {
      Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file));
      if (!(doc instanceof Map<?, ?> m)) {
        throw new IllegalArgumentException(file + " is not a mapping");
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) m;
      for (String key : map.keySet()) {
        if (!TOP_LEVEL.contains(key)) {
          throw new IllegalArgumentException("unknown top-level key in " + file + ": " + key);
        }
      }
      if (!Integer.valueOf(1).equals(map.get("version"))) {
        throw new IllegalArgumentException("spec version must be 1");
      }
      return new InfraSpec(map);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static InfraSpec load() {
    return load(DEFAULT);
  }

  public Object get(String path) {
    Object cur = root;
    for (String part : path.split("\\.", -1)) {
      if (!(cur instanceof Map<?, ?> m) || !m.containsKey(part)) {
        throw new IllegalArgumentException("spec has no '" + path + "'");
      }
      cur = m.get(part);
    }
    if (cur == null) {
      throw new IllegalArgumentException("spec value '" + path + "' is empty");
    }
    return cur;
  }

  public String str(String path) {
    return String.valueOf(get(path));
  }

  public int num(String path) {
    if (get(path) instanceof Number n) {
      return n.intValue();
    }
    throw new IllegalArgumentException("spec value '" + path + "' is not a number");
  }

  /** A value that differs by mode: {@code path.<mode>}. */
  public int num(String path, Mode mode) {
    return num(path + "." + mode.key());
  }

  public List<String> list(String path) {
    if (get(path) instanceof List<?> l) {
      return l.stream().map(String::valueOf).toList();
    }
    throw new IllegalArgumentException("spec value '" + path + "' is not a list");
  }

  public Map<String, String> map(String path) {
    if (get(path) instanceof Map<?, ?> m) {
      var out = new LinkedHashMap<String, String>();
      m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
      return out;
    }
    throw new IllegalArgumentException("spec value '" + path + "' is not a mapping");
  }
}
