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
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.client.ProxyHttpClient;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
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
  private final URI app;
  private final byte[] principalKey;
  private final InstantSource clock;
  private final ConcurrencyLimiter limiter;
  private final Ticker ticker;
  private final LongAdder shed = new LongAdder();
  private static final long DROPPED = -2;
  private static final long NO_SIGNAL = -1;

  EdgeFilter(EdgeRuntime runtime) {
    this.limiter = runtime.limiter();
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

  @Override
  public Publisher<MutableHttpResponse<?>> doFilter(
      HttpRequest<?> request, ServerFilterChain chain) {
    if (request.getPath().startsWith("/health")) {
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
    return Mono.fromCallable(() -> admit.admit(method, path, auth))
        .subscribeOn(decisions)
        .flatMap(admission -> respond(request, admission, outcome))
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
  }

  /** Requests shed by the concurrency gate, for metrics. */
  public long shed() {
    return shed.sum();
  }

  private Mono<MutableHttpResponse<?>> respond(
      HttpRequest<?> request, Admission admission, AtomicLong outcome) {
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
        long start = ticker.nanos();
        yield Mono.from(proxy.proxy(toApp(request, principal, stale)))
            .doOnNext(
                r -> outcome.set(r.getStatus().getCode() >= 500 ? DROPPED : ticker.nanos() - start))
            .doOnError(e -> outcome.set(DROPPED));
      }
    };
  }

  private MutableHttpRequest<?> toApp(
      HttpRequest<?> request, PrincipalHeader.Principal principal, boolean stale) {
    MutableHttpRequest<?> out =
        request
            .mutate()
            .uri(b -> b.scheme(app.getScheme()).host(app.getHost()).port(app.getPort()));
    List<String> strip =
        out.getHeaders().names().stream()
            .filter(n ->
                n.toLowerCase(Locale.ROOT).startsWith("x-keyedge-")
                    || n.equalsIgnoreCase(HttpHeaders.AUTHORIZATION))
            .toList();
    strip.forEach(out.getHeaders()::remove);
    var signed =
        PrincipalHeader.sign(
            principalKey, principal, clock.instant(), request.getMethodName(), request.getPath());
    out.getHeaders().add(PrincipalHeader.PRINCIPAL, signed.principal());
    out.getHeaders().add(PrincipalHeader.SIGNATURE, signed.signature());
    out.getHeaders().add("X-Forwarded-Proto", request.isSecure() ? "https" : "http");
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
      URI app,
      byte[] principalKey,
      InstantSource clock,
      Duration drainTimeout,
      ConcurrencyLimiter limiter,
      Ticker ticker) {}
}
