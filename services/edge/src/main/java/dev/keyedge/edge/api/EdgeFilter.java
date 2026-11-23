package dev.keyedge.edge.api;

import dev.keyedge.edge.app.AdmitRequest;
import dev.keyedge.edge.app.MeterUsage;
import dev.keyedge.edge.domain.Admission;
import dev.keyedge.keyformat.PrincipalHeader;
import dev.keyedge.problem.Problem;
import dev.keyedge.problem.ProblemType;
import dev.keyedge.problem.micronaut.ProblemResponses;
import dev.keyedge.ratelimit.Ticker;
import dev.keyedge.ratelimit.concurrency.ConcurrencyLimiter;
import dev.keyedge.ratelimit.concurrency.Permit;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.client.ProxyHttpClient;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import io.micronaut.tracing.opentelemetry.OpenTelemetryPropagationContext;
import io.micronaut.tracing.opentelemetry.utils.OpenTelemetryReactorPropagation;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import java.net.URI;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Every request except the sidecar's own health: decide on a virtual thread (blocking-style code:
 * cache, verify call, limits), then stream the request to the app on 127.0.0.1 and the response
 * back (Reactor only at this byte boundary, D-11). The API key never reaches the app; a signed
 * principal does, and any inbound {@code X-Keyedge-*} header is removed so it cannot be spoofed.
 *
 * <p>A concurrency gate (fixed or adaptive, Phase 008) comes first: over the limit, the request is
 * shed with 503 {@code overloaded} before any work. The permit's latency sample is the app's
 * response time; an app 5xx or a failed proxy call is a drop (congestion signal); rejections at the
 * edge release without a signal.
 */
@Filter("/**")
public class EdgeFilter implements HttpServerFilter {
  private final AdmitRequest admit;
  private final MeterUsage usage;
  private final ProxyHttpClient proxy;
  private final Scheduler decisions;
  private final Gate gate;
  private final java.util.function.Supplier<URI> app;
  private final byte[] principalKey;
  private final InstantSource clock;
  private final ConcurrencyLimiter limiter;
  private final OpenTelemetry otel;
  private final Ticker ticker;
  private final LongAdder shed = new LongAdder();
  private static final long DROPPED = -2;
  private static final long NO_SIGNAL = -1;

  EdgeFilter(EdgeRuntime runtime) {
    this.limiter = runtime.limiter();
    this.otel = runtime.otel();
    this.ticker = runtime.ticker();
    this.admit = runtime.admit();
    this.usage = runtime.usage();
    this.proxy = runtime.proxy();
    this.decisions = runtime.decisions();
    this.gate = runtime.gate();
    this.app = runtime.app();
    this.principalKey = runtime.principalKey();
    this.clock = runtime.clock();
  }

  /** After Micronaut's tracing filter, so the server span exists when we decide and forward. */
  @Override
  public int getOrder() {
    return ServerFilterPhase.TRACING.after();
  }

  @Override
  public Publisher<MutableHttpResponse<?>> doFilter(
      HttpRequest<?> request, ServerFilterChain chain) {
    if (request.getPath().startsWith("/health") || request.getPath().startsWith("/prometheus")) {
      return chain.proceed(request);
    }
    String method = request.getMethodName();
    String path = request.getPath();
    Optional<String> auth = request.getHeaders().getAuthorization();
    Optional<Permit> slot = limiter.tryAcquire();
    if (slot.isEmpty()) {
      shed.increment();
      return Mono.just(
          ProblemResponses.of(
              Problem.of(ProblemType.OVERLOADED, null).with(Problem.RETRY_AFTER_SECONDS, 1L)));
    }
    Permit permit = slot.get();
    var outcome = new AtomicLong(NO_SIGNAL);

    gate.enter();
    // The trace context (server span) is in Micronaut's propagated context, not in a ThreadLocal we
    // can rely on, and the decision hops to a virtual thread: capture it here, trace the forward.
    return Mono.deferContextual(view -> {
      Context trace = traceOf(view);
      return Mono.fromCallable(() -> admit.admit(method, path, auth))
          .subscribeOn(decisions)
          .flatMap(admission -> respond(request, admission, outcome, trace))
          .doFinally(signal -> {
            long o = outcome.get();
            if (o >= 0) {
              permit.onSuccess(o);
            } else if (o == DROPPED) {
              permit.onDropped();
            } else {
              permit.onIgnore();
            }
            gate.leave();
          });
    });
  }

  private static Context traceOf(reactor.util.context.ContextView view) {
    return PropagatedContext.find()
        .flatMap(p -> p.find(OpenTelemetryPropagationContext.class))
        .map(OpenTelemetryPropagationContext::context)
        .orElseGet(() -> OpenTelemetryReactorPropagation.currentContext(view));
  }

  /** Requests shed by the concurrency gate, for metrics. */
  public long shed() {
    return shed.sum();
  }

  private Mono<MutableHttpResponse<?>> respond(
      HttpRequest<?> request, Admission admission, AtomicLong outcome, Context trace) {
    Span server = Span.fromContext(trace);
    server.setAttribute("keyedge.admission", admissionLabel(admission));
    return switch (admission) {
      case Admission.Rejected(var type, var retryAfter, var principal) -> {
        principal.ifPresent(p -> usage.record(p.tenant(), p.keyId(), false));
        Problem problem = Problem.of(type, null);
        if (retryAfter.isPresent()) {
          problem =
              problem.with(
                  Problem.RETRY_AFTER_SECONDS,
                  Math.max(1, (long) Math.ceil(retryAfter.get().toMillis() / 1000.0)));
        }
        yield Mono.just(ProblemResponses.of(problem));
      }
      case Admission.Admitted(var principal, var stale) -> {
        usage.record(principal.tenant(), principal.keyId(), true);
        server.setAttribute("keyedge.key_id", principal.keyId().toString()); // never the secret
        server.setAttribute("keyedge.verify.stale", stale);
        Span forward =
            otel.getTracer("dev.keyedge.edge")
                .spanBuilder("forward " + request.getMethodName())
                .setSpanKind(SpanKind.CLIENT)
                .setParent(trace)
                .startSpan();
        long start = ticker.nanos();
        yield Mono.from(proxy.proxy(toApp(request, principal, stale, trace.with(forward))))
            .doOnNext(r -> {
              int code = r.getStatus().getCode();
              forward.setAttribute("http.response.status_code", code);
              if (code >= 500) {
                forward.setStatus(StatusCode.ERROR);
              }
              outcome.set(code >= 500 ? DROPPED : ticker.nanos() - start);
            })
            .doOnError(e -> {
              forward.setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
              outcome.set(DROPPED);
            })
            .doFinally(signal -> forward.end());
      }
    };
  }

  private static String admissionLabel(Admission a) {
    return switch (a) {
      case Admission.Admitted(var p, var stale) -> stale ? "admitted_stale" : "admitted";
      case Admission.Rejected r -> r.problem().slug();
    };
  }

  private MutableHttpRequest<?> toApp(
      HttpRequest<?> request, PrincipalHeader.Principal principal, boolean stale, Context trace) {
    MutableHttpRequest<?> out =
        request
            .mutate()
            .uri(b -> {
              URI to = app.get(); // resolved lazily (ECS task metadata), then cached
              b.scheme(to.getScheme()).host(to.getHost()).port(to.getPort());
            });
    List<String> strip =
        out.getHeaders().names().stream()
            .filter(n ->
                n.toLowerCase(Locale.ROOT).startsWith("x-keyedge-")
                    || n.equalsIgnoreCase(HttpHeaders.AUTHORIZATION)
                    // the proxy client writes the trace context of its own (child) span
                    || n.equalsIgnoreCase("traceparent")
                    || n.equalsIgnoreCase("tracestate"))
            .toList();
    strip.forEach(out.getHeaders()::remove);
    var signed =
        PrincipalHeader.sign(
            principalKey, principal, clock.instant(), request.getMethodName(), request.getPath());
    out.getHeaders().add(PrincipalHeader.PRINCIPAL, signed.principal());
    out.getHeaders().add(PrincipalHeader.SIGNATURE, signed.signature());
    out.getHeaders().add("X-Forwarded-Proto", request.isSecure() ? "https" : "http");
    otel.getPropagators()
        .getTextMapPropagator()
        .inject(trace, out, (carrier, k, v) -> out.getHeaders().set(k, v));
    if (stale) {
      out.getHeaders().add(STALE_HEADER, "1"); // admitted on a verdict past expiry (fail-static)
    }
    return out;
  }

  /** Told to the app when the verdict was served stale (inbound copies are stripped). */
  public static final String STALE_HEADER = "X-Keyedge-Stale";

  /** Bundles what the filter needs (built in wiring). */
  public record EdgeRuntime(
      AdmitRequest admit,
      MeterUsage usage,
      ProxyHttpClient proxy,
      Scheduler decisions,
      Gate gate,
      java.util.function.Supplier<URI> app,
      byte[] principalKey,
      InstantSource clock,
      Duration drainTimeout,
      ConcurrencyLimiter limiter,
      Ticker ticker,
      OpenTelemetry otel) {}
}
