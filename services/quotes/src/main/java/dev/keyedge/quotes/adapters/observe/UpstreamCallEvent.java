package dev.keyedge.quotes.adapters.observe;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * One upstream call of a quote fan-out (the plan's FanOutEvent): which upstream, model, outcome.
 */
@Name("dev.keyedge.FanOut")
@Label("Fan-out upstream call")
@Category({"keyedge", "quotes"})
@StackTrace(false)
public final class UpstreamCallEvent extends Event {
  @Label("Upstream")
  public String upstream = "";

  @Label("Concurrency model")
  public String model = ""; // structured | future | reactor

  @Label("Outcome")
  public String outcome = "";
}
