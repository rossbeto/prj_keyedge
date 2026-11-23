package dev.keyedge.observe;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Where request fields come from at log time. A service registers a supplier that reads its own
 * {@code ScopedValue<RequestContext>} (request id, tenant): the encoder calls it on the logging
 * thread, so the fields are exactly those of the scope the log statement runs in — nothing to put
 * into or remove from an MDC, nothing to leak to the next request on a pooled thread.
 */
public final class LogFields {
  private static final List<Supplier<Map<String, String>>> SOURCES = new CopyOnWriteArrayList<>();

  private LogFields() {}

  public static void register(Supplier<Map<String, String>> source) {
    SOURCES.add(source);
  }

  static List<Supplier<Map<String, String>>> sources() {
    return SOURCES;
  }

  static void clearForTests() {
    SOURCES.clear();
  }
}
