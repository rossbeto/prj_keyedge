package dev.keyedge.edge.domain;

import dev.keyedge.keyformat.Scope;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Which scope a route needs. Routes not listed are not exposed through the sidecar at all (deny by
 * default): a new app endpoint is invisible until someone decides its scope.
 */
public final class RouteScopes {
  /** One rule: method (or {@code *}) + path prefix → required scope. */
  public record Rule(String method, String pathPrefix, Scope scope) {}

  private final List<Rule> rules;

  public RouteScopes(List<Rule> rules) {
    this.rules = List.copyOf(rules);
  }

  /** The scope for the longest matching prefix, if the route is exposed. */
  public Optional<Scope> required(String method, String path) {
    String m = method.toUpperCase(Locale.ROOT);
    return rules.stream()
        .filter(r ->
            (r.method().equals("*") || r.method().equalsIgnoreCase(m))
                && matches(path, r.pathPrefix()))
        .max(java.util.Comparator.comparingInt(r -> r.pathPrefix().length()))
        .map(Rule::scope);
  }

  private static boolean matches(String path, String prefix) {
    return path.equals(prefix) || path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/");
  }
}
