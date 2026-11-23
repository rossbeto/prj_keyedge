package dev.keyedge.quotes.ports;

import dev.keyedge.fanout.CallException;
import dev.keyedge.fanout.Deadline;
import dev.keyedge.quotes.domain.Sku;
import dev.keyedge.quotes.domain.Upstream;

/** {@code GET /pricing/{sku}} (OAuth client-credentials token required). */
public interface PricingClient {
  Upstream.Price price(Sku sku, Deadline deadline) throws CallException, InterruptedException;
}
