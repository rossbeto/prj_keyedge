package dev.keyedge.observe;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.encoder.EncoderBase;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One JSON object per line: {@code ts, level, logger, thread, msg, trace_id, span_id} plus the
 * registered {@link LogFields} and {@code error} (stack trace). Message and stack trace pass
 * through {@link Redactor}. No JSON library: the encoder must work before anything else is
 * initialised and in native images without reflection.
 */
public final class JsonLineEncoder extends EncoderBase<ILoggingEvent> {
  private static final byte[] EMPTY = new byte[0];

  @Override
  public byte[] headerBytes() {
    return EMPTY;
  }

  @Override
  public byte[] footerBytes() {
    return EMPTY;
  }

  @Override
  public byte[] encode(ILoggingEvent event) {
    var sb = new StringBuilder(256);
    sb.append('{');
    field(sb, "ts", Instant.ofEpochMilli(event.getTimeStamp()).toString(), true);
    field(sb, "level", event.getLevel().toString(), false);
    field(sb, "logger", event.getLoggerName(), false);
    field(sb, "thread", event.getThreadName(), false);
    field(sb, "msg", Redactor.redact(event.getFormattedMessage()), false);
    SpanContext span = Span.current().getSpanContext();
    if (span.isValid()) {
      field(sb, "trace_id", span.getTraceId(), false);
      field(sb, "span_id", span.getSpanId(), false);
    }
    for (Supplier<Map<String, String>> source : LogFields.sources()) {
      try {
        source.get().forEach((k, v) -> field(sb, k, Redactor.redact(v), false));
      } catch (RuntimeException e) {
        // a broken field source must never break logging
      }
    }
    IThrowableProxy error = event.getThrowableProxy();
    if (error != null) {
      field(sb, "error", Redactor.redact(stack(error)), false);
    }
    sb.append("}\n");
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String stack(IThrowableProxy t) {
    var sb = new StringBuilder();
    for (IThrowableProxy cur = t; cur != null; cur = cur.getCause()) {
      if (cur != t) {
        sb.append("Caused by: ");
      }
      sb.append(cur.getClassName()).append(": ").append(cur.getMessage()).append('\n');
      StackTraceElementProxy[] frames = cur.getStackTraceElementProxyArray();
      for (int i = 0; i < Math.min(frames.length, 30); i++) {
        sb.append("\tat ").append(frames[i].getStackTraceElement()).append('\n');
      }
    }
    return sb.toString();
  }

  static void field(StringBuilder sb, String key, String value, boolean first) {
    if (!first) {
      sb.append(',');
    }
    sb.append('"').append(key).append("\":\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }
}
