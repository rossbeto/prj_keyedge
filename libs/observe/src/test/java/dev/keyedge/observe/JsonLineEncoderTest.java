package dev.keyedge.observe;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JsonLineEncoderTest {
  // a well-formed key with a fake secret (structure only: the redactor matches on shape)
  private static final String KEY =
      "ke_test_01M82SPJ73Q3V73SE9A2WAW08P_" + "x".repeat(43) + "_AbCdEf";
  private static final ScopedValue<String> TENANT = ScopedValue.newInstance();
  private final Logger logger = new LoggerContext().getLogger("t");

  @AfterEach
  void clear() {
    LogFields.clearForTests();
  }

  private String encode(String msg, Throwable error) {
    var event = new LoggingEvent("fqcn", logger, Level.INFO, msg, error, null);
    var encoder = new JsonLineEncoder();
    return new String(encoder.encode(event), StandardCharsets.UTF_8);
  }

  @Test
  void oneEscapedJsonLineWithoutSecrets() {
    String line =
        encode("created " + KEY + " for \"acme\"\nnext", new IllegalStateException("bad " + KEY));
    assertThat(line).endsWith("}\n").doesNotContain("xxxxxxxx");
    assertThat(line)
        .contains("\"level\":\"INFO\"", "\"logger\":\"t\"")
        .contains("ke_test_01M82SPJ73Q3V73SE9A2WAW08P_[redacted]")
        .contains("\\\"acme\\\"\\nnext")
        .contains(
            "\"error\":\"java.lang.IllegalStateException: bad ke_test_01M82SPJ73Q3V73SE9A2WAW08P_[redacted]");
    assertThat(line.trim().split("\n")).hasSize(1);
  }

  @Test
  void bearerAndBasicCredentialsAreRedacted() {
    assertThat(Redactor.redact("Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.payload.sig"))
        .isEqualTo("Authorization: Bearer [redacted]");
    assertThat(Redactor.redact("Authorization: Basic cXVvdGVzOmxvY2Fs"))
        .isEqualTo("Authorization: Basic [redacted]");
    assertThat(Redactor.redact("nothing to see")).isEqualTo("nothing to see");
    assertThat(Redactor.redact("plain")).isEqualTo("plain");
  }

  @Test
  void traceAndScopedFieldsAreReadAtLogTime() {
    LogFields.register(() -> TENANT.isBound() ? Map.of("tenant", TENANT.get()) : Map.of());
    LogFields.register(() -> {
      throw new IllegalStateException("broken source");
    });
    var span =
        Span.wrap(
            SpanContext.create(
                "4bf92f3577b34da6a3ce929d0e0e4736",
                "00f067aa0ba902b7",
                TraceFlags.getSampled(),
                TraceState.getDefault()));
    String line;
    try (var scope = span.makeCurrent()) {
      line = ScopedValue.where(TENANT, "acme").call(() -> encode("in scope", null));
    }
    assertThat(line)
        .contains(
            "\"trace_id\":\"4bf92f3577b34da6a3ce929d0e0e4736\"", "\"span_id\":\"00f067aa0ba902b7\"")
        .contains("\"tenant\":\"acme\"");
    assertThat(encode("outside", null)).doesNotContain("tenant", "trace_id");
  }

  @Test
  void controlCharactersAreEscaped() {
    var sb = new StringBuilder("{");
    JsonLineEncoder.field(sb, "k", "a\u0001b\tc\\", true);
    assertThat(sb).hasToString("{\"k\":\"a\\u0001b\\tc\\\\\"");
    assertThat(new JsonLineEncoder().headerBytes()).isEmpty();
    assertThat(new JsonLineEncoder().footerBytes()).isEmpty();
  }
}
