package dev.keyedge.infra.constructs;

import java.util.Map;
import java.util.TreeMap;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.constructs.Construct;

/**
 * The names the services read at start-up (D-34), as SSM String parameters under {@code
 * /keyedge/<env>/}. The construct exposes what it wrote so a test can compare it with what the
 * services read.
 */
public final class SsmContract extends Construct {
  private final Map<String, String> written = new TreeMap<>();

  public SsmContract(Construct scope, String id, Map<String, String> parameters) {
    super(scope, id);
    new TreeMap<>(parameters)
        .forEach((name, value) -> {
          StringParameter.Builder.create(this, idFor(name))
              .parameterName(name)
              .stringValue(value)
              .description("keyedge contract")
              .build();
          written.put(name, value);
        });
  }

  private static String idFor(String parameterName) {
    return parameterName.substring(parameterName.lastIndexOf('/') + 1);
  }

  public Map<String, String> written() {
    return Map.copyOf(written);
  }
}
