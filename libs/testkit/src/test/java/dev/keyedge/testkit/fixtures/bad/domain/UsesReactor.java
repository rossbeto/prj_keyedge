package dev.keyedge.testkit.fixtures.bad.domain;

/** Breaks: coreIsFrameworkFree (domain -> reactor). */
public final class UsesReactor {
  public String value() {
    return reactor.fixture.Mono.just("x");
  }
}
