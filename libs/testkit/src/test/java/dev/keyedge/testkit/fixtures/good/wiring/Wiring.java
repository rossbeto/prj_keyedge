package dev.keyedge.testkit.fixtures.good.wiring;

import dev.keyedge.testkit.fixtures.good.adapters.x.MapRepo;
import dev.keyedge.testkit.fixtures.good.api.Controller;
import dev.keyedge.testkit.fixtures.good.app.LoadThing;
import java.time.Instant;

/** Composition root: the only place allowed to read the clock. */
public final class Wiring {
  public Controller controller() {
    return new Controller(new LoadThing(new MapRepo()));
  }

  public Instant startedAt() {
    return Instant.now();
  }
}
