package dev.keyedge.sim.domain;

import java.time.Duration;
import java.util.Optional;

/** What one call will do. Decided first (pure), then executed by the API layer. */
public sealed interface Behaviour {
  Duration latency();

  /** Answer normally after the latency. */
  record Ok(Duration latency) implements Behaviour {}

  /** Answer with an error status after the latency. */
  record Fail(Duration latency, int status, Optional<Integer> retryAfterSeconds)
      implements Behaviour {}

  /** Start the body, pause, then finish: a read timeout is not a deadline. */
  record SlowClose(Duration latency, Duration pause) implements Behaviour {}
}
