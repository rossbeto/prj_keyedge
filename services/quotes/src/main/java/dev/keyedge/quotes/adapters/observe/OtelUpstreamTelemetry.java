package dev.keyedge.quotes.adapters.observe;

import dev.keyedge.quotes.ports.UpstreamTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Per upstream call: an OpenTelemetry CLIENT span (child of the captured request context) whose
 * context is injected as W3C headers, {@code keyedge_upstream_calls_total{upstream,model,outcome}},
 * and a JFR event. The JDK HttpClient is not instrumented by Micronaut, hence this.
 */
public final class OtelUpstreamTelemetry implements UpstreamTelemetry {
  private final OpenTelemetry otel;
  private final Tracer tracer;
  private final Supplier<MeterRegistry> registry;
  private final String model;

  private record Captured(Context context) implements Parent {}

  public OtelUpstreamTelemetry(OpenTelemetry otel, Supplier<MeterRegistry> registry, String model) {
    this.otel = otel;
    this.tracer = otel.getTracer("dev.keyedge.quotes");
    this.registry = registry;
    this.model = model;
  }

  @Override
  public Parent capture() {
    return new Captured(Context.current());
  }

  @Override
  public Call start(String upstream, Parent parent) {
    Context ctx = parent instanceof Captured c ? c.context() : Context.current();
    Span span =
        tracer
            .spanBuilder("GET " + upstream)
            .setSpanKind(SpanKind.CLIENT)
            .setParent(ctx)
            .setAttribute("keyedge.upstream", upstream)
            .setAttribute("keyedge.fanout.model", model)
            .startSpan();
    Map<String, String> headers = new HashMap<>();
    otel.getPropagators()
        .getTextMapPropagator()
        .inject(
            ctx.with(span), headers, (carrier, k, v) -> headers.put(k, v)); // carrier == headers
    var event = new UpstreamCallEvent();
    event.begin();
    return new Call() {
      @Override
      public Map<String, String> headers() {
        return headers;
      }

      @Override
      public void end(String outcome) {
        if (!outcome.equals("ok")) {
          span.setStatus(StatusCode.ERROR, outcome);
        }
        span.setAttribute("keyedge.outcome", outcome);
        span.end();
        Counter.builder("keyedge.upstream.calls")
            .description("Upstream HTTP calls by outcome (each retry attempt counts)")
            .tag("upstream", upstream)
            .tag("model", model)
            .tag("outcome", outcome)
            .register(registry.get())
            .increment();
        event.end();
        if (event.shouldCommit()) {
          event.upstream = upstream;
          event.model = model;
          event.outcome = outcome;
          event.commit();
        }
      }
    };
  }
}
