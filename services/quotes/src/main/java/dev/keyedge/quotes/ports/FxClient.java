package dev.keyedge.quotes.ports;

import dev.keyedge.fanout.CallException;
import dev.keyedge.fanout.Deadline;
import dev.keyedge.quotes.domain.CurrencyCode;
import dev.keyedge.quotes.domain.Upstream;

/** {@code GET /fx/{from}/{to}}. */
public interface FxClient {
  Upstream.FxRate rate(CurrencyCode from, CurrencyCode to, Deadline deadline)
      throws CallException, InterruptedException;
}
