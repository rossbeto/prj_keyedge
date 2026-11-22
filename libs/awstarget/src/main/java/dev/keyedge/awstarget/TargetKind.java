package dev.keyedge.awstarget;

import java.util.Locale;

/** The three possible AWS endpoints. Only the two emulators are used by any phase of the lab. */
public enum TargetKind {
  FLOCI("floci"),
  AWS_EMULATOR("aws-emulator"),
  AWS("aws");

  private final String id;

  TargetKind(String id) {
    this.id = id;
  }

  /** The value used in {@code KEYEDGE_AWS_TARGET}. */
  public String id() {
    return id;
  }

  /** Parses {@code floci}, {@code aws-emulator} or {@code aws} (case-insensitive). */
  public static TargetKind of(String id) {
    String normalised = id.trim().toLowerCase(Locale.ROOT);
    for (TargetKind kind : values()) {
      if (kind.id.equals(normalised)) {
        return kind;
      }
    }
    throw new IllegalArgumentException(
        "unknown KEYEDGE_AWS_TARGET '" + id + "' (floci | aws-emulator | aws)");
  }
}
