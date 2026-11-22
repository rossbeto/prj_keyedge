package dev.keyedge.edge.api;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** In-flight requests and the readiness flag the drain flips (D-29). */
public final class Gate {
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicBoolean ready = new AtomicBoolean(true);

  void enter() {
    inFlight.incrementAndGet();
  }

  void leave() {
    inFlight.decrementAndGet();
  }

  public int inFlight() {
    return inFlight.get();
  }

  public boolean ready() {
    return ready.get();
  }

  /** Readiness DOWN: the load balancer stops sending new requests. */
  public void close() {
    ready.set(false);
  }
}
