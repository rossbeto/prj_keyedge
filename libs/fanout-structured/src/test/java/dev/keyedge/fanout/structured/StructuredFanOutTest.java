package dev.keyedge.fanout.structured;

import dev.keyedge.fanout.FanOut;
import dev.keyedge.fanout.FanOutContract;

/** The same contract; no context carrier: forks inherit scoped values. */
class StructuredFanOutTest extends FanOutContract {
  @Override
  protected FanOut fanOut() {
    return new StructuredFanOut(System::nanoTime);
  }
}
