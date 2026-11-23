package dev.keyedge.infra;

import dev.keyedge.infra.config.InfraConfig;
import dev.keyedge.infra.config.InfraSpec;
import dev.keyedge.infra.config.Mode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.assertions.Template;

/** One synth per mode for the whole test run (jsii is slow to start): templates by stack name. */
final class Synth {
  private static final Map<Mode, Map<String, Template>> CACHE = new ConcurrentHashMap<>();

  private Synth() {}

  static Map<String, Template> templates(Mode mode) {
    return CACHE.computeIfAbsent(
        mode,
        m -> {
          var app = InfraApp.newApp(Map.of("mode", m.key(), "env", m.key()));
          var cfg = InfraConfig.of(m, m.key(), InfraSpec.load());
          var out = new LinkedHashMap<String, Template>();
          for (Stack s : InfraApp.build(app, cfg)) {
            out.put(s.getStackName(), Template.fromStack(s));
          }
          return out;
        });
  }

  static Template of(Mode mode, String stack) {
    Template t = templates(mode).get(stack);
    if (t == null) {
      throw new IllegalArgumentException("no stack " + stack);
    }
    return t;
  }
}
