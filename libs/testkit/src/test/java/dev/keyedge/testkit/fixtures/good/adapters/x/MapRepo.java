package dev.keyedge.testkit.fixtures.good.adapters.x;

import dev.keyedge.testkit.fixtures.good.ports.Repo;

/** An adapter. */
public final class MapRepo implements Repo {
  @Override
  public String load(String id) {
    return id;
  }
}
