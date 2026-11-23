package dev.keyedge.edge.adapters.observe.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/** A call from the verdict cache to the control plane. */
@Name("dev.keyedge.VerifyCacheMiss")
@Label("Verify cache load")
@Category({"keyedge", "edge"})
@StackTrace(false)
public final class VerifyCacheMissEvent extends Event {
  @Label("Key id")
  public String keyId = "";

  @Label("Kind")
  public String kind = ""; // miss | refresh | revalidate

  @Label("Outcome")
  public String outcome = "";

  @Label("Load time")
  @Timespan(Timespan.NANOSECONDS)
  public long loadNanos;
}
