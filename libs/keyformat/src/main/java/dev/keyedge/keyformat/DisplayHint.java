package dev.keyedge.keyformat;

/** What a user sees in a key list: environment and the last 4 characters of the secret. */
public record DisplayHint(String value) {
  /** Hint for a key. */
  public static DisplayHint of(KeyEnvironment env, KeySecret secret) {
    String text = secret.text();
    return new DisplayHint(
        KeyFormat.PREFIX + env.label() + "_…" + text.substring(text.length() - 4));
  }
}
