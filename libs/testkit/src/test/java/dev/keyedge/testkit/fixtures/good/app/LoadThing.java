package dev.keyedge.testkit.fixtures.good.app;

import dev.keyedge.testkit.fixtures.good.domain.Thing;
import dev.keyedge.testkit.fixtures.good.ports.Repo;

/** A use case. */
public final class LoadThing {
  private final Repo repo;

  public LoadThing(Repo repo) {
    this.repo = repo;
  }

  public Thing run(String id) {
    return new Thing(repo.load(id));
  }
}
