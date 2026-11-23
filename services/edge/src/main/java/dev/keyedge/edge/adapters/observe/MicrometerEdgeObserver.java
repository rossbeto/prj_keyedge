package dev.keyedge.edge.adapters.observe;

import dev.keyedge.edge.domain.Admission;
import dev.keyedge.edge.ports.AdmissionObserver;
import dev.keyedge.edge.ports.VerifyObserver;
import dev.keyedge.keyformat.KeyId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;

/**
 * Counters and timers with bounded tags only (result kinds, never key ids or tenants:
 * MetricsCardinalityTest). Names: {@code keyedge_edge_admission_total{result}}, {@code
 * keyedge_verify_cache_loads_total{kind,outcome}}, {@code keyedge_edge_decision_seconds}.
 */
public final class MicrometerEdgeObserver implements AdmissionObserver, VerifyObserver {
  private final java.util.function.Supplier<MeterRegistry> registrySource;
  private volatile @org.jspecify.annotations.Nullable MeterRegistry registry;

  /**
   * The registry is resolved on first use: the registry itself depends (through meter binders) on
   * beans that use this observer.
   */
  public MicrometerEdgeObserver(java.util.function.Supplier<MeterRegistry> registry) {
    this.registrySource = registry;
  }

  private MeterRegistry registry() {
    MeterRegistry r = registry;
    if (r == null) {
      r = registrySource.get();
      registry = r;
    }
    return r;
  }

  @Override
  public void decided(Admission admission, Duration took) {
    Timer.builder("keyedge.edge.decision")
        .description("Time to decide (route, key, verdict, scope, limit), excluding the app")
        .publishPercentileHistogram()
        .register(registry())
        .record(took);
    String result =
        switch (admission) {
          case Admission.Admitted(var p, var stale) -> stale ? "admitted_stale" : "admitted";
          case Admission.Rejected r -> r.problem().name().toLowerCase(Locale.ROOT);
        };
    Counter.builder("keyedge.edge.admission")
        .description("Admission decisions by result")
        .tag("result", result)
        .register(registry())
        .increment();
  }

  @Override
  public void loaded(KeyId keyId, long nanos, String kind, String outcome) {
    Timer.builder("keyedge.verify.cache.loads")
        .description("Verdict-cache calls to the control plane")
        .tag("kind", kind)
        .tag("outcome", outcome.toLowerCase(Locale.ROOT))
        .register(registry())
        .record(Duration.ofNanos(nanos));
  }
}
