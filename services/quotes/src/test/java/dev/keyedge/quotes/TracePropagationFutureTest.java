package dev.keyedge.quotes;

/** The same propagation checks with the futures fan-out adapter. */
class TracePropagationFutureTest extends TracePropagationTest {
  @Override
  String mode() {
    return "FUTURE";
  }
}
