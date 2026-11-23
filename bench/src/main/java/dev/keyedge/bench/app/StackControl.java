package dev.keyedge.bench.app;

import dev.keyedge.bench.domain.Scenario;
import java.util.List;

/** The running compose stack: recreate a variant, switch the simulator, credentials, CPU time. */
public interface StackControl {
  /** Recreates edge + quotes for the variant (flavor, env, limits) and waits until healthy. */
  void prepare(Scenario.Variant variant);

  /** Switches upstream-sim to a scenario (healthy, slow-tail, ...). */
  void simulate(String simScenario);

  /** Enough API keys for {@code maxRate} plus the direct-path principal. */
  Auth credentials(int maxRate);

  /** CPU time used so far by the system under test (edge + quotes containers), in microseconds. */
  long sutCpuMicros();

  /** Running Gradle daemons (builds skew a bench sharing the CPUs), as "pid cmdline" lines. */
  List<String> competingBuilds();
}
