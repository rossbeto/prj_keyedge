package dev.keyedge.fanout.structured;

import dev.keyedge.fanout.FanOut;
import dev.keyedge.fanout.FanOutProvider;

/** Registers {@link StructuredFanOut} as {@code "structured"} (no executor needed). */
public final class StructuredFanOutProvider implements FanOutProvider {
  @Override
  public String name() {
    return "structured";
  }

  @Override
  public FanOut create(Resources r) {
    return new StructuredFanOut(r.nanos(), r.context());
  }
}
