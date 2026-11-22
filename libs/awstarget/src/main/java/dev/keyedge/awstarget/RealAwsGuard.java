package dev.keyedge.awstarget;

import java.util.Map;

/** Refuses the real-AWS target unless explicitly allowed (D-33): no phase of the lab uses it. */
public final class RealAwsGuard {
  /** The opt-in variable. */
  public static final String ALLOW = "KEYEDGE_ALLOW_REAL_AWS";

  private RealAwsGuard() {}

  public static void check(Map<String, String> env) {
    if (!"yes".equals(env.get(ALLOW))) {
      throw new IllegalStateException(
          "KEYEDGE_AWS_TARGET=aws talks to a real AWS account; refused unless " + ALLOW + "=yes");
    }
  }
}
