package dev.keyedge.testkit.fixtures.good.api;

import dev.keyedge.testkit.fixtures.good.app.LoadThing;

/** A driving adapter. */
public final class Controller {
  private final LoadThing loadThing;

  public Controller(LoadThing loadThing) {
    this.loadThing = loadThing;
  }

  public String get(String id) {
    return loadThing.run(id).id();
  }
}
