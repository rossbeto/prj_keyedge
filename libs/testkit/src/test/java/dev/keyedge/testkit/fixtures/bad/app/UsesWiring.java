package dev.keyedge.testkit.fixtures.bad.app;

import dev.keyedge.testkit.fixtures.bad.wiring.Factory;

/** Breaks: layers (app -> wiring). */
public final class UsesWiring {
  public Object make() {
    return new Factory();
  }
}
