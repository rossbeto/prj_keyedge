package dev.keyedge.bench.app;

import dev.keyedge.bench.domain.LittlesLaw;
import dev.keyedge.bench.domain.Prediction;
import dev.keyedge.bench.domain.Scenario;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Writes the prediction before any load (D-72). From the scenario's model: requests in flight at
 * each offered rate (L = λW), the rate a concurrency cap allows (cap / W), the rate a CPU budget
 * allows (CPUs / CPU per request); the predicted saturation is the lower of the two.
 */
public final class Predict {
  private final ResultStore store;
  private final Clock clock;

  public Predict(ResultStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public Prediction predict(Scenario scenario, String runId) {
    Map<String, Double> m = scenario.model();
    double latencyS = need(m, "latencyMs") / 1000.0;
    Map<String, Double> n = new LinkedHashMap<>();
    for (int rate : scenario.rates()) {
      n.put("in_flight@" + rate, round(LittlesLaw.concurrency(rate, latencyS)));
    }
    double capRate = LittlesLaw.maxRate(need(m, "concurrencyCap"), latencyS);
    double cpuRate = need(m, "sutCpus") * 1000.0 / need(m, "cpuMsPerRequest");
    double saturation = Math.min(capRate, cpuRate);
    n.put("cap_bound_rps", round(capRate));
    n.put("cpu_bound_rps", round(cpuRate));
    n.put("predicted_saturation_rps", round(saturation));
    String statement =
        String.format(
            Locale.ROOT,
            "%s Model: W=%.0f ms, cap %.0f in flight -> %.0f rps; %.1f CPUs at %.2f ms CPU/request -> %.0f rps;"
                + " predicted saturation %.0f rps (%s-bound).",
            scenario.hypothesis(),
            latencyS * 1000,
            need(m, "concurrencyCap"),
            capRate,
            need(m, "sutCpus"),
            need(m, "cpuMsPerRequest"),
            cpuRate,
            saturation,
            cpuRate < capRate ? "CPU" : "concurrency");
    Prediction p = new Prediction(scenario.name(), runId, clock.instant(), statement, n);
    store.save(p);
    return p;
  }

  private static double need(Map<String, Double> m, String key) {
    Double v = m.get(key);
    if (v == null) {
      throw new IllegalArgumentException("scenario model needs " + key);
    }
    return v;
  }

  private static double round(double v) {
    return Math.round(v * 10) / 10.0;
  }
}
