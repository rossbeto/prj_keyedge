package dev.keyedge.testkit.fixtures.bad.adapters.a;

import dev.keyedge.testkit.fixtures.bad.adapters.b.AdapterB;

/** Breaks: adaptersIndependent (a -> b). */
public final class AdapterA {
  public Object other() {
    return new AdapterB();
  }
}
