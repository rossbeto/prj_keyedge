package dev.keyedge.observe;

import java.util.regex.Pattern;

/**
 * Removes secrets from text before it is logged: the secret part of an API key (the key id stays,
 * it is what an operator searches for) and bearer credentials. Applied to every message and stack
 * trace by {@link JsonLineEncoder}.
 */
public final class Redactor {
  // ke_<env>_<26-char key id>_<secret ...>: keep "ke_<env>_<id>", drop the rest
  private static final Pattern API_KEY =
      Pattern.compile("\\b(ke_[a-z]{2,8}_[0-9A-Za-z]{26})_[A-Za-z0-9_-]{8,}");
  private static final Pattern BEARER =
      Pattern.compile("(?i)\\b(bearer\\s+)(?!ke_)[A-Za-z0-9._~+/=-]{8,}");
  private static final Pattern BASIC = Pattern.compile("(?i)\\b(basic\\s+)[A-Za-z0-9+/=]{8,}");

  private Redactor() {}

  public static String redact(String text) {
    if (text.indexOf('_') < 0 && text.indexOf(' ') < 0) {
      return text;
    }
    String out = API_KEY.matcher(text).replaceAll("$1_[redacted]");
    out = BEARER.matcher(out).replaceAll("$1[redacted]");
    return BASIC.matcher(out).replaceAll("$1[redacted]");
  }
}
