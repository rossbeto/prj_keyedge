package dev.keyedge.problem;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * An RFC 9457 problem. Extension members are written at the top level of the JSON object (as the
 * RFC requires), so they may not reuse the standard member names.
 */
public record Problem(
    URI type,
    String title,
    int status,
    @Nullable String detail,
    @Nullable URI instance,
    Map<String, Object> extensions) {
  private static final Set<String> RESERVED =
      Set.of("type", "title", "status", "detail", "instance");

  /**
   * Extension carrying a retry hint in seconds; adapters also send it as a {@code Retry-After}
   * header.
   */
  public static final String RETRY_AFTER_SECONDS = "retryAfterSeconds";

  public Problem {
    if (status < 400 || status > 599) {
      throw new IllegalArgumentException("problem status must be 4xx or 5xx");
    }
    for (String key : extensions.keySet()) {
      if (RESERVED.contains(key)) {
        throw new IllegalArgumentException("extension may not reuse member '" + key + "'");
      }
    }
    extensions = Map.copyOf(extensions);
  }

  /** A problem of a known type with an optional human-readable detail. */
  public static Problem of(ProblemType type, @Nullable String detail) {
    return new Problem(type.uri(), type.title(), type.status(), detail, null, Map.of());
  }

  /** The same problem with one more extension member. */
  public Problem with(String key, Object value) {
    var map = new LinkedHashMap<>(extensions);
    map.put(key, value);
    return new Problem(type, title, status, detail, instance, map);
  }

  /** The same problem with an {@code instance} URI (e.g. the request path). */
  public Problem at(URI instanceUri) {
    return new Problem(type, title, status, detail, instanceUri, extensions);
  }

  /** The JSON members in RFC order, extensions flattened, absent members omitted. */
  public Map<String, Object> members() {
    var m = new LinkedHashMap<String, Object>();
    m.put("type", type.toString());
    m.put("title", title);
    m.put("status", status);
    if (detail != null) {
      m.put("detail", detail);
    }
    if (instance != null) {
      m.put("instance", instance.toString());
    }
    m.putAll(new java.util.TreeMap<>(extensions));
    return m;
  }
}
