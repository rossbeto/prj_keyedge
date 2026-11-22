package dev.keyedge.keyformat;

import java.util.Optional;

/** Which environment a key belongs to; visible in the key so a test key never works in live. */
public enum KeyEnvironment {
  LIVE("live"),
  TEST("test");

  private final String label;

  KeyEnvironment(String label) {
    this.label = label;
  }

  /** The 4-character label used in the key text. */
  public String label() {
    return label;
  }

  /** Environment for a label, empty when unknown. */
  public static Optional<KeyEnvironment> ofLabel(CharSequence label) {
    for (KeyEnvironment env : values()) {
      if (env.label.contentEquals(label)) {
        return Optional.of(env);
      }
    }
    return Optional.empty();
  }
}
