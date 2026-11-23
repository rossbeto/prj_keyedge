package dev.keyedge.infra.config;

import java.util.Locale;

/** {@code local}: deployable to the emulators; {@code prodlike}: synthesised and tested only. */
public enum Mode {
  LOCAL,
  PRODLIKE;

  /** The spec's key for this mode ({@code local}, {@code prodlike}). */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Mode parse(String value) {
    for (Mode m : values()) {
      if (m.key().equals(value)) {
        return m;
      }
    }
    throw new IllegalArgumentException("mode must be local or prodlike, was '" + value + "'");
  }
}
